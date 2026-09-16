package com.govid.screening.pipeline;

import com.govid.screening.domain.DocumentType;
import com.govid.screening.domain.RiskFlag;
import com.govid.screening.domain.ScreeningCase;
import com.govid.screening.domain.Severity;
import com.govid.screening.domain.Verdict;
import com.govid.screening.domain.WatchlistEntry;
import com.govid.screening.ocr.MrzParser;
import com.govid.screening.repository.AuditEventRepository;
import com.govid.screening.repository.ScreeningCaseRepository;
import com.govid.screening.repository.WatchlistRepository;
import com.govid.screening.watchlist.WatchlistService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises the whole pipeline against an embedded MongoDB: a document goes in, a
 * persisted case with a score and an audit trail comes out.
 */
@SpringBootTest
class ScreeningPipelineIntegrationTest {

    private static final DateTimeFormatter YYMMDD = DateTimeFormatter.ofPattern("yyMMdd");

    @Autowired
    private ScreeningService screeningService;

    @Autowired
    private ScreeningCaseRepository caseRepository;

    @Autowired
    private WatchlistRepository watchlistRepository;

    @Autowired
    private AuditEventRepository auditRepository;

    @Autowired
    private WatchlistService watchlistService;

    @BeforeEach
    void reset() {
        caseRepository.deleteAll();
        watchlistRepository.deleteAll();
        auditRepository.deleteAll();
    }

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

    /**
     * Builds a well-formed TD3 MRZ with correct check digits.
     *
     * <p>Check digits are computed with {@link MrzParser#checkDigit}, which is itself
     * verified against the published ICAO specimen values in {@code MrzParserTest}, so
     * this fixture cannot silently agree with a broken implementation.
     */
    private static String buildTd3(String surname, String givenNames, String documentNumber,
                                   String state, LocalDate dateOfBirth, String sex,
                                   LocalDate expiry) {
        String nameField = (surname + "<<" + givenNames.replace(" ", "<"));
        String line1 = ("P<" + state + nameField);
        line1 = line1 + "<".repeat(44 - line1.length());

        String number = documentNumber + "<".repeat(9 - documentNumber.length());
        String dob = dateOfBirth.format(YYMMDD);
        String exp = expiry.format(YYMMDD);
        String personal = "<".repeat(14);

        String line2 = number + MrzParser.checkDigit(number)
                + state
                + dob + MrzParser.checkDigit(dob)
                + sex
                + exp + MrzParser.checkDigit(exp)
                + personal + MrzParser.checkDigit(personal);

        String composite = line2.substring(0, 10) + line2.substring(13, 20) + line2.substring(21, 43);
        line2 = line2 + MrzParser.checkDigit(composite);

        return line1 + "\n" + line2;
    }

    /** A JPEG with enough texture that the Module 3 detectors actually run on it. */
    private static byte[] syntheticDocumentImage() throws Exception {
        BufferedImage image = new BufferedImage(900, 600, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(new Color(238, 236, 228));
        g.fillRect(0, 0, 900, 600);

        Random random = new Random(42);
        for (int i = 0; i < 6000; i++) {
            g.setColor(new Color(random.nextInt(160), random.nextInt(160), random.nextInt(160)));
            g.fillRect(random.nextInt(900), random.nextInt(600), 3, 3);
        }
        g.dispose();

        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        ImageIO.write(image, "jpg", buffer);
        return buffer.toByteArray();
    }

    private ScreeningCase screen(String mrz) throws Exception {
        return screeningService.screen(new ScreeningService.ScreeningRequest(
                syntheticDocumentImage(), "image/jpeg", null, null,
                DocumentType.PASSPORT, "CHK-1", "LANE-2", "officer-7", mrz));
    }

    private static List<String> codes(ScreeningCase screeningCase) {
        return screeningCase.getRisk().flags().stream().map(RiskFlag::code).toList();
    }

    // ------------------------------------------------------------------
    // Tests
    // ------------------------------------------------------------------

    @Test
    @DisplayName("screens a sound passport end to end and clears it")
    void screensValidPassport() throws Exception {
        String mrz = buildTd3("ERIKSSON", "ANNA MARIA", "L898902C3", "SWE",
                LocalDate.of(1974, 8, 12), "F", LocalDate.now().plusYears(4));

        ScreeningCase result = screen(mrz);

        assertThat(result.getStatus()).isEqualTo(ScreeningCase.Status.COMPLETED);
        assertThat(result.getCaseReference()).startsWith("BRD-");
        assertThat(result.getExtracted().getSurname()).isEqualTo("ERIKSSON");
        assertThat(result.getExtracted().getGivenNames()).isEqualTo("ANNA MARIA");
        assertThat(result.getExtracted().getDocumentNumber()).isEqualTo("L898902C3");
        assertThat(result.getExtracted().getNationality()).isEqualTo("SWE");
        assertThat(result.getRisk().verdict()).isEqualTo(Verdict.CLEAR);
        assertThat(codes(result)).doesNotContain("MRZ_CHECKDIGIT_MISMATCH");

        // All five stages must have reported, whatever their outcome.
        assertThat(result.getModuleResults()).hasSize(5);
        assertThat(caseRepository.findByCaseReference(result.getCaseReference())).isPresent();
    }

    @Test
    @DisplayName("writes an audit trail for every screening")
    void writesAuditTrail() throws Exception {
        String mrz = buildTd3("ERIKSSON", "ANNA MARIA", "L898902C3", "SWE",
                LocalDate.of(1974, 8, 12), "F", LocalDate.now().plusYears(4));

        ScreeningCase result = screen(mrz);

        assertThat(auditRepository.findByCaseIdOrderByOccurredAtAsc(result.getId()))
                .isNotEmpty()
                .anySatisfy(event -> assertThat(event.getAction()).isEqualTo("SCREENED"));
    }

    @Test
    @DisplayName("catches a date of birth altered without recomputing the check digit")
    void catchesAlteredDateOfBirth() throws Exception {
        String sound = buildTd3("ERIKSSON", "ANNA MARIA", "L898902C3", "SWE",
                LocalDate.of(1974, 8, 12), "F", LocalDate.now().plusYears(4));

        // Rewrite the year of birth in place, leaving the printed check digit untouched.
        String[] lines = sound.split("\n");
        String tampered = lines[0] + "\n"
                + lines[1].substring(0, 13) + "840812" + lines[1].substring(19);

        ScreeningCase result = screen(tampered);

        assertThat(codes(result)).contains("MRZ_CHECKDIGIT_MISMATCH");
        assertThat(result.getRisk().verdict()).isNotEqualTo(Verdict.CLEAR);
    }

    @Test
    @DisplayName("flags an expired travel document")
    void flagsExpiredDocument() throws Exception {
        String mrz = buildTd3("ERIKSSON", "ANNA MARIA", "L898902C3", "SWE",
                LocalDate.of(1974, 8, 12), "F", LocalDate.now().minusYears(2));

        ScreeningCase result = screen(mrz);

        assertThat(codes(result)).contains("DOCUMENT_EXPIRED");
    }

    @Test
    @DisplayName("rejects a document that is on the stolen-document watchlist")
    void rejectsWatchlistedDocument() throws Exception {
        WatchlistEntry entry = new WatchlistEntry();
        entry.setDocumentNumberKey("L898902C3");
        entry.setListType(WatchlistEntry.ListType.STOLEN_DOCUMENT);
        entry.setSeverity(Severity.CRITICAL);
        entry.setReason("Reported stolen by the issuing authority");
        entry.setSource("INTERPOL SLTD");
        watchlistService.add(entry, null, null);

        String mrz = buildTd3("ERIKSSON", "ANNA MARIA", "L898902C3", "SWE",
                LocalDate.of(1974, 8, 12), "F", LocalDate.now().plusYears(4));

        ScreeningCase result = screen(mrz);

        assertThat(codes(result)).contains("WATCHLIST_HIT_DOCUMENT");
        assertThat(result.getRisk().verdict()).isEqualTo(Verdict.REJECT);
        assertThat(result.getRisk().score()).isGreaterThanOrEqualTo(70);
    }

    @Test
    @DisplayName("spots one document presented under two different identities")
    void spotsDocumentReuseAcrossIdentities() throws Exception {
        screen(buildTd3("ERIKSSON", "ANNA MARIA", "L898902C3", "SWE",
                LocalDate.of(1974, 8, 12), "F", LocalDate.now().plusYears(4)));

        ScreeningCase second = screen(buildTd3("ANDERSSON", "BRITTA", "L898902C3", "SWE",
                LocalDate.of(1981, 3, 4), "F", LocalDate.now().plusYears(4)));

        assertThat(codes(second)).contains("DOCUMENT_USED_BY_OTHER_IDENTITY");
    }

    @Test
    @DisplayName("spots one person presenting several different documents")
    void spotsMultipleDocumentsForOneIdentity() throws Exception {
        screen(buildTd3("ERIKSSON", "ANNA MARIA", "L898902C3", "SWE",
                LocalDate.of(1974, 8, 12), "F", LocalDate.now().plusYears(4)));

        ScreeningCase second = screen(buildTd3("ERIKSSON", "ANNA MARIA", "P771234X1", "SWE",
                LocalDate.of(1974, 8, 12), "F", LocalDate.now().plusYears(4)));

        assertThat(codes(second)).contains("IDENTITY_USING_MULTIPLE_DOCUMENTS");
    }

    @Test
    @DisplayName("records an officer decision alongside the system recommendation")
    void recordsOfficerDecision() throws Exception {
        ScreeningCase result = screen(buildTd3("ERIKSSON", "ANNA MARIA", "L898902C3", "SWE",
                LocalDate.of(1974, 8, 12), "F", LocalDate.now().plusYears(4)));

        ScreeningCase decided = screeningService.recordDecision(
                result.getId(), Verdict.REVIEW, "officer-7", "Referred for secondary inspection");

        assertThat(decided.getOfficerDecision()).isEqualTo(Verdict.REVIEW);
        assertThat(decided.getRisk().verdict()).isEqualTo(Verdict.CLEAR);
        assertThat(auditRepository.findByCaseIdOrderByOccurredAtAsc(result.getId()))
                .anySatisfy(event -> assertThat(event.getAction()).isEqualTo("DECISION_RECORDED"));
    }

    @Test
    @DisplayName("still produces a case when no OCR engine can read the document")
    void producesCaseWhenOcrUnavailable() throws Exception {
        ScreeningCase result = screeningService.screen(new ScreeningService.ScreeningRequest(
                syntheticDocumentImage(), "image/jpeg", null, null,
                DocumentType.PASSPORT, "CHK-1", "LANE-2", "officer-7", null));

        assertThat(result.getStatus()).isEqualTo(ScreeningCase.Status.COMPLETED);
        // No engine could read it, so the case must not be cleared on partial evidence.
        assertThat(result.getRisk().verdict()).isNotEqualTo(Verdict.CLEAR);
        assertThat(result.getRisk().explanation()).contains("partial evidence");
    }
}
