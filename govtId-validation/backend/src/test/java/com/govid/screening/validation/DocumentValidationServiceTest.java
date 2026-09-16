package com.govid.screening.validation;

import com.govid.screening.domain.DocumentType;
import com.govid.screening.domain.ExtractedFields;
import com.govid.screening.domain.ModuleResult;
import com.govid.screening.domain.MrzData;
import com.govid.screening.domain.RiskFlag;
import com.govid.screening.domain.Severity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DocumentValidationServiceTest {

    /** Fixed so expiry and validity rules assert against a known "today". */
    private static final LocalDate TODAY = LocalDate.of(2026, 8, 27);

    private final DocumentValidationService service = new DocumentValidationService(
            Clock.fixed(Instant.parse("2026-08-27T00:00:00Z"), ZoneOffset.UTC));

    /** A document that passes every check, used as the baseline to perturb. */
    private ExtractedFields validPassport() {
        ExtractedFields fields = new ExtractedFields();
        fields.setSurname("ERIKSSON");
        fields.setGivenNames("ANNA MARIA");
        fields.setDocumentNumber("L898902C3");
        fields.setIssuingState("SWE");
        fields.setNationality("SWE");
        fields.setDateOfBirth(LocalDate.of(1974, 8, 12));
        fields.setDateOfExpiry(TODAY.plusYears(3));
        fields.setSex("F");
        fields.setMrz(new MrzData("TD3", List.of("line1", "line2"),
                Map.of("documentNumber", true, "dateOfBirth", true, "dateOfExpiry", true), true));
        return fields;
    }

    private static List<String> codes(ModuleResult result) {
        return result.flags().stream().map(RiskFlag::code).toList();
    }

    @Test
    @DisplayName("raises nothing on a well-formed, in-date passport")
    void cleanPassportProducesNoFindings() {
        ModuleResult result = service.validate(DocumentType.PASSPORT, validPassport());

        assertThat(result.status()).isEqualTo(ModuleResult.Status.COMPLETED);
        assertThat(result.flags()).isEmpty();
    }

    @Test
    @DisplayName("reports each MRZ check digit that failed")
    void reportsFailedCheckDigits() {
        ExtractedFields fields = validPassport();
        fields.setMrz(new MrzData("TD3", List.of("line1", "line2"),
                Map.of("documentNumber", true, "dateOfBirth", false, "dateOfExpiry", true), true));

        ModuleResult result = service.validate(DocumentType.PASSPORT, fields);

        assertThat(codes(result)).contains("MRZ_CHECKDIGIT_MISMATCH");
        assertThat(result.flags())
                .filteredOn(flag -> flag.code().equals("MRZ_CHECKDIGIT_MISMATCH"))
                .singleElement()
                .satisfies(flag -> {
                    assertThat(flag.severity()).isEqualTo(Severity.HIGH);
                    assertThat(flag.evidence()).containsEntry("field", "dateOfBirth");
                });
    }

    @Test
    @DisplayName("reports a broken composite check digit")
    void reportsCompositeFailure() {
        ExtractedFields fields = validPassport();
        fields.setMrz(new MrzData("TD3", List.of("line1", "line2"),
                Map.of("documentNumber", true, "dateOfBirth", true, "dateOfExpiry", true), false));

        assertThat(codes(service.validate(DocumentType.PASSPORT, fields)))
                .contains("MRZ_COMPOSITE_MISMATCH");
    }

    @Test
    @DisplayName("flags an expired document with the number of days elapsed")
    void flagsExpiredDocument() {
        ExtractedFields fields = validPassport();
        fields.setDateOfExpiry(TODAY.minusDays(30));

        ModuleResult result = service.validate(DocumentType.PASSPORT, fields);

        assertThat(codes(result)).contains("DOCUMENT_EXPIRED");
        assertThat(result.flags())
                .filteredOn(flag -> flag.code().equals("DOCUMENT_EXPIRED"))
                .singleElement()
                .satisfies(flag -> assertThat(flag.evidence()).containsEntry("daysExpired", 30L));
    }

    @Test
    @DisplayName("flags a document inside the six-month remaining-validity window")
    void flagsExpiringSoon() {
        ExtractedFields fields = validPassport();
        fields.setDateOfExpiry(TODAY.plusDays(60));

        assertThat(codes(service.validate(DocumentType.PASSPORT, fields)))
                .contains("DOCUMENT_EXPIRING_SOON");
    }

    @Test
    @DisplayName("treats a future date of birth as decisive")
    void flagsFutureDateOfBirth() {
        ExtractedFields fields = validPassport();
        fields.setDateOfBirth(TODAY.plusYears(1));

        ModuleResult result = service.validate(DocumentType.PASSPORT, fields);

        assertThat(result.flags())
                .filteredOn(flag -> flag.code().equals("DOB_IN_FUTURE"))
                .singleElement()
                .satisfies(flag -> assertThat(flag.severity()).isEqualTo(Severity.CRITICAL));
    }

    @Test
    @DisplayName("treats an expiry preceding the date of birth as decisive")
    void flagsExpiryBeforeBirth() {
        ExtractedFields fields = validPassport();
        fields.setDateOfBirth(LocalDate.of(1974, 8, 12));
        fields.setDateOfExpiry(LocalDate.of(1970, 1, 1));

        assertThat(codes(service.validate(DocumentType.PASSPORT, fields)))
                .contains("EXPIRY_BEFORE_BIRTH");
    }

    @Test
    @DisplayName("rejects an issuing state that is not a real country code")
    void flagsUnknownIssuingState() {
        ExtractedFields fields = validPassport();
        fields.setIssuingState("ZZZ");

        assertThat(codes(service.validate(DocumentType.PASSPORT, fields)))
                .contains("UNKNOWN_ISSUING_STATE");
    }

    @Test
    @DisplayName("accepts the ICAO special codes for stateless and refugee travellers")
    void acceptsIcaoSpecialCodes() {
        ExtractedFields fields = validPassport();
        fields.setNationality("XXB");
        fields.setIssuingState("D");

        assertThat(codes(service.validate(DocumentType.PASSPORT, fields)))
                .doesNotContain("UNKNOWN_NATIONALITY", "UNKNOWN_ISSUING_STATE");
    }

    @Test
    @DisplayName("treats a data page disagreeing with the MRZ as decisive")
    void flagsDataPageMismatch() {
        ExtractedFields fields = validPassport();
        fields.setPrintedDateOfBirth(LocalDate.of(1984, 8, 12));
        fields.setPrintedDocumentNumber("L898902C9");

        List<String> found = codes(service.validate(DocumentType.PASSPORT, fields));

        assertThat(found).contains("DATAPAGE_MRZ_DOB_MISMATCH", "DATAPAGE_MRZ_DOCNUMBER_MISMATCH");
    }

    @Test
    @DisplayName("does not flag an ICAO transliteration as a name mismatch")
    void toleratesTransliteratedName() {
        ExtractedFields fields = validPassport();
        fields.setSurname("MUELLER");
        fields.setPrintedSurname("Müller");

        assertThat(codes(service.validate(DocumentType.PASSPORT, fields)))
                .doesNotContain("DATAPAGE_MRZ_SURNAME_MISMATCH");
    }

    @Test
    @DisplayName("does flag a genuinely different surname")
    void flagsDifferentSurname() {
        ExtractedFields fields = validPassport();
        fields.setSurname("ERIKSSON");
        fields.setPrintedSurname("ANDERSSON");

        assertThat(codes(service.validate(DocumentType.PASSPORT, fields)))
                .contains("DATAPAGE_MRZ_SURNAME_MISMATCH");
    }

    @Test
    @DisplayName("flags a visa whose validity window is inverted and already past")
    void flagsInvalidVisaWindow() {
        ExtractedFields fields = new ExtractedFields();
        fields.setSurname("ERIKSSON");
        fields.setVisaNumber("V1234567");
        fields.setDateOfBirth(LocalDate.of(1974, 8, 12));
        fields.setValidFrom(TODAY.minusDays(10));
        fields.setValidUntil(TODAY.minusDays(40));
        fields.setDateOfExpiry(TODAY.minusDays(40));
        fields.setEntryType("MULTIPLE");

        List<String> found = codes(service.validate(DocumentType.VISA, fields));

        assertThat(found).contains("VISA_VALID_FROM_AFTER_UNTIL", "VISA_EXPIRED");
    }

    @Test
    @DisplayName("flags a permitted stay longer than the visa is valid for")
    void flagsStayExceedingValidity() {
        ExtractedFields fields = new ExtractedFields();
        fields.setSurname("ERIKSSON");
        fields.setVisaNumber("V1234567");
        fields.setDateOfBirth(LocalDate.of(1974, 8, 12));
        fields.setValidFrom(TODAY.minusDays(5));
        fields.setValidUntil(TODAY.plusDays(25));
        fields.setDateOfExpiry(TODAY.plusDays(25));
        fields.setStayDurationDays(90);

        assertThat(codes(service.validate(DocumentType.VISA, fields)))
                .contains("VISA_STAY_EXCEEDS_VALIDITY");
    }

    @Test
    @DisplayName("reports missing mandatory fields rather than failing")
    void reportsMissingFields() {
        ModuleResult result = service.validate(DocumentType.PASSPORT, new ExtractedFields());

        assertThat(result.status()).isEqualTo(ModuleResult.Status.COMPLETED);
        assertThat(codes(result)).contains("MANDATORY_FIELD_MISSING");
    }

    @Test
    @DisplayName("skips cleanly when Module 1 produced nothing")
    void skipsWithoutFields() {
        ModuleResult result = service.validate(DocumentType.PASSPORT, null);

        assertThat(result.status()).isEqualTo(ModuleResult.Status.SKIPPED);
        assertThat(result.flags()).isEmpty();
    }
}
