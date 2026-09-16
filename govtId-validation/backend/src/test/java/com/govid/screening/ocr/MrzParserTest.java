package com.govid.screening.ocr;

import com.govid.screening.domain.MrzData;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class MrzParserTest {

    /**
     * The ICAO 9303 specimen passport for the fictional state of Utopia. Every printed
     * check digit in it is correct, which makes it the right baseline: anything the parser
     * reports as failing on this input is a bug in the parser, not a finding.
     */
    private static final String TD3_LINE_1 = "P<UTOERIKSSON<<ANNA<MARIA" + "<".repeat(19);
    private static final String TD3_LINE_2 = "L898902C36UTO7408122F1204159ZE184226B<<<<<10";

    @Test
    @DisplayName("parses every field of a TD3 passport MRZ")
    void parsesTd3Fields() {
        MrzParser.MrzParseResult result = MrzParser.parse(List.of(TD3_LINE_1, TD3_LINE_2))
                .orElseThrow();

        assertThat(result.mrz().format()).isEqualTo("TD3");
        assertThat(result.documentCode()).isEqualTo("P");
        assertThat(result.issuingState()).isEqualTo("UTO");
        assertThat(result.surname()).isEqualTo("ERIKSSON");
        assertThat(result.givenNames()).isEqualTo("ANNA MARIA");
        assertThat(result.documentNumber()).isEqualTo("L898902C3");
        assertThat(result.nationality()).isEqualTo("UTO");
        assertThat(result.dateOfBirth()).isEqualTo(LocalDate.of(1974, 8, 12));
        assertThat(result.sex()).isEqualTo("F");
        assertThat(result.dateOfExpiry()).isEqualTo(LocalDate.of(2012, 4, 15));
    }

    @Test
    @DisplayName("accepts every check digit on a genuine MRZ")
    void acceptsGenuineCheckDigits() {
        MrzData mrz = MrzParser.parse(List.of(TD3_LINE_1, TD3_LINE_2)).orElseThrow().mrz();

        assertThat(mrz.failedCheckDigits()).isEmpty();
        assertThat(mrz.composite()).isTrue();
        assertThat(mrz.allCheckDigitsValid()).isTrue();
    }

    @Test
    @DisplayName("catches a date of birth altered without recomputing its check digit")
    void catchesAlteredDateOfBirth() {
        // Move the year of birth from 1974 to 1984, exactly as someone adding ten years to
        // an identity would, and leave the printed check digit alone.
        String tampered = TD3_LINE_2.substring(0, 13) + "840812" + TD3_LINE_2.substring(19);

        MrzData mrz = MrzParser.parse(List.of(TD3_LINE_1, tampered)).orElseThrow().mrz();

        assertThat(mrz.failedCheckDigits()).contains("dateOfBirth");
        assertThat(mrz.allCheckDigitsValid()).isFalse();
    }

    @Test
    @DisplayName("catches a document number altered without recomputing its check digit")
    void catchesAlteredDocumentNumber() {
        String tampered = "L898902C46UTO7408122F1204159ZE184226B<<<<<10";

        MrzData mrz = MrzParser.parse(List.of(TD3_LINE_1, tampered)).orElseThrow().mrz();

        assertThat(mrz.failedCheckDigits()).contains("documentNumber");
    }

    @Test
    @DisplayName("catches an edit that keeps the field check digit but breaks the composite")
    void catchesCompositeOnlyBreak() {
        // Replace the trailing composite digit only. Each field still self-checks, so the
        // composite is the sole remaining witness that the zone was touched.
        String tampered = TD3_LINE_2.substring(0, 43) + "7";

        MrzData mrz = MrzParser.parse(List.of(TD3_LINE_1, tampered)).orElseThrow().mrz();

        assertThat(mrz.failedCheckDigits()).isEmpty();
        assertThat(mrz.composite()).isFalse();
        assertThat(mrz.allCheckDigitsValid()).isFalse();
    }

    @Test
    @DisplayName("computes the ICAO 7-3-1 weighted check digit")
    void computesCheckDigit() {
        assertThat(MrzParser.checkDigit("L898902C3")).isEqualTo(6);
        assertThat(MrzParser.checkDigit("740812")).isEqualTo(2);
        assertThat(MrzParser.checkDigit("120415")).isEqualTo(9);
    }

    @Test
    @DisplayName("finds the MRZ inside a full page of OCR text")
    void findsMrzInPageText() {
        String page = """
                REPUBLIC OF UTOPIA
                PASSPORT
                Surname: ERIKSSON
                Given names: ANNA MARIA
                Passport No: L898902C3
                %s
                %s
                """.formatted(TD3_LINE_1, TD3_LINE_2);

        Optional<MrzParser.MrzParseResult> result = MrzParser.parseFromText(page);

        assertThat(result).isPresent();
        assertThat(result.get().surname()).isEqualTo("ERIKSSON");
    }

    @Test
    @DisplayName("reports no MRZ rather than guessing when the zone is absent")
    void returnsEmptyWhenNoMrz() {
        assertThat(MrzParser.parseFromText("DRIVING LICENCE\nJOHN SMITH\n12 OAK STREET"))
                .isEmpty();
    }

    @Test
    @DisplayName("parses a TD1 identity card across three lines")
    void parsesTd1() {
        // ICAO 9303 Part 5 specimen.
        List<String> lines = List.of(
                "I<UTOD231458907<<<<<<<<<<<<<<<",
                "7408122F1204159UTO<<<<<<<<<<<6",
                "ERIKSSON<<ANNA<MARIA<<<<<<<<<<");

        MrzParser.MrzParseResult result = MrzParser.parse(lines).orElseThrow();

        assertThat(result.mrz().format()).isEqualTo("TD1");
        assertThat(result.surname()).isEqualTo("ERIKSSON");
        assertThat(result.givenNames()).isEqualTo("ANNA MARIA");
        assertThat(result.documentNumber()).isEqualTo("D23145890");
        assertThat(result.dateOfBirth()).isEqualTo(LocalDate.of(1974, 8, 12));
        assertThat(result.nationality()).isEqualTo("UTO");
    }

    @Test
    @DisplayName("rejects an impossible calendar date instead of throwing")
    void rejectsImpossibleDate() {
        assertThat(MrzParser.parseDate("741332", false)).isNull();
        assertThat(MrzParser.parseDate("74AB12", false)).isNull();
    }
}
