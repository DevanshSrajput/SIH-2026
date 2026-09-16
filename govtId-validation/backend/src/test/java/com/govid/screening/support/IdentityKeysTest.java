package com.govid.screening.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class IdentityKeysTest {

    private static final LocalDate DOB = LocalDate.of(1974, 8, 12);

    @Test
    @DisplayName("keys the same person identically across transliterations and punctuation")
    void samePersonKeysIdentically() {
        String fromMrz = IdentityKeys.identityKey("MUELLER", "HANS PETER", DOB);
        String fromPage = IdentityKeys.identityKey("Müller", "Hans-Peter", DOB);

        assertThat(fromPage).isEqualTo(fromMrz);
    }

    @Test
    @DisplayName("keys different people differently")
    void differentPeopleKeyDifferently() {
        assertThat(IdentityKeys.identityKey("ERIKSSON", "ANNA", DOB))
                .isNotEqualTo(IdentityKeys.identityKey("ANDERSSON", "ANNA", DOB));

        assertThat(IdentityKeys.identityKey("ERIKSSON", "ANNA", DOB))
                .isNotEqualTo(IdentityKeys.identityKey("ERIKSSON", "ANNA", DOB.plusYears(1)));
    }

    @Test
    @DisplayName("refuses to build an identity key without a date of birth")
    void requiresDateOfBirth() {
        assertThat(IdentityKeys.identityKey("ERIKSSON", "ANNA", null)).isNull();
        assertThat(IdentityKeys.identityKey(null, "ANNA", DOB)).isNull();
    }

    @Test
    @DisplayName("normalises document numbers but rejects ones too short to identify anything")
    void documentNumberKeying() {
        assertThat(IdentityKeys.documentNumberKey("l898902-c3")).isEqualTo("L898902C3");
        assertThat(IdentityKeys.documentNumberKey("L89")).isNull();
        assertThat(IdentityKeys.documentNumberKey((String) null)).isNull();
    }

    @Test
    @DisplayName("expands ICAO multi-character transliterations before folding diacritics")
    void appliesIcaoTransliteration() {
        assertThat(NameNormaliser.normalise("Müller")).isEqualTo("MUELLER");
        assertThat(NameNormaliser.normalise("Straße")).isEqualTo("STRASSE");
        assertThat(NameNormaliser.normalise("Ångström")).isEqualTo("AANGSTROEM");
        assertThat(NameNormaliser.normalise("José")).isEqualTo("JOSE");
        assertThat(NameNormaliser.normalise("O'Brien")).isEqualTo("OBRIEN");
    }
}
