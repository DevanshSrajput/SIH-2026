package com.govid.screening.support;

import com.govid.screening.domain.ExtractedFields;

import java.time.LocalDate;

/**
 * Builds the normalised keys used to recognise the same document or the same person
 * across cases and against the watchlist.
 *
 * <p>Matching has to survive transliteration, punctuation and spacing differences between
 * checkpoints and between source systems, so both keys strip everything that is not a
 * letter or digit. A key is only produced when there is enough underlying data for it to
 * be meaningful - a partial key would create false matches, which in this context means
 * detaining the wrong traveller.
 */
public final class IdentityKeys {

    private IdentityKeys() {
    }

    /** Upper-cased, alphanumeric-only document number, or {@code null} if unusable. */
    public static String documentNumberKey(String documentNumber) {
        if (documentNumber == null) {
            return null;
        }
        String key = normalise(documentNumber);
        // Anything shorter than this cannot identify a document on its own.
        return key.length() < 4 ? null : key;
    }

    /**
     * Person key as {@code SURNAME|GIVENNAMES|YYYY-MM-DD}.
     *
     * <p>Requires a surname and a date of birth. A name alone is far too common to key on
     * safely, and the date of birth is what makes the pair discriminating.
     */
    public static String identityKey(String surname, String givenNames, LocalDate dateOfBirth) {
        if (surname == null || dateOfBirth == null) {
            return null;
        }
        String surnamePart = normalise(surname);
        if (surnamePart.isEmpty()) {
            return null;
        }
        String givenPart = givenNames == null ? "" : normalise(givenNames);
        return surnamePart + "|" + givenPart + "|" + dateOfBirth;
    }

    public static String identityKey(ExtractedFields fields) {
        if (fields == null) {
            return null;
        }
        return identityKey(fields.getSurname(), fields.getGivenNames(), fields.getDateOfBirth());
    }

    public static String documentNumberKey(ExtractedFields fields) {
        if (fields == null) {
            return null;
        }
        String number = fields.getDocumentNumber() != null
                ? fields.getDocumentNumber()
                : fields.getVisaNumber();
        return documentNumberKey(number);
    }

    /**
     * Collapses transliterated spellings together, so the same person keys identically
     * whether the source system wrote MUELLER or MÜLLER.
     */
    private static String normalise(String value) {
        return NameNormaliser.normalise(value);
    }
}
