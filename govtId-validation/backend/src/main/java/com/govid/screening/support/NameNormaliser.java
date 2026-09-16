package com.govid.screening.support;

import java.text.Normalizer;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Normalises names so that two spellings of the same person compare equal.
 *
 * <p>The MRZ is restricted to A-Z, so the issuing authority transliterates the printed
 * name into it. Some of those transliterations expand one character into two - German
 * {@code Ü} becomes {@code UE}, {@code ß} becomes {@code SS} - and simply stripping the
 * diacritic would turn {@code MÜLLER} into {@code MULLER}, which does not equal the
 * {@code MUELLER} in the MRZ.
 *
 * <p>Getting this wrong is not a cosmetic problem. Under-normalising raises a forgery
 * finding against every traveller with a non-English name; over-normalising hides a real
 * one. So multi-character transliterations are applied from the ICAO Doc 9303 Part 3
 * table first, and only then are any remaining diacritics folded away.
 */
public final class NameNormaliser {

    /** Expansions that must run before diacritics are stripped. */
    private static final Map<String, String> TRANSLITERATIONS = new LinkedHashMap<>();

    static {
        TRANSLITERATIONS.put("Ä", "AE");
        TRANSLITERATIONS.put("Ö", "OE");
        TRANSLITERATIONS.put("Ü", "UE");
        TRANSLITERATIONS.put("ß", "SS");
        TRANSLITERATIONS.put("Å", "AA");
        TRANSLITERATIONS.put("Æ", "AE");
        TRANSLITERATIONS.put("Ø", "OE");
        TRANSLITERATIONS.put("Þ", "TH");
        TRANSLITERATIONS.put("Ð", "D");
        TRANSLITERATIONS.put("Đ", "D");
        TRANSLITERATIONS.put("Ł", "L");
        TRANSLITERATIONS.put("Œ", "OE");
        TRANSLITERATIONS.put("İ", "I");
    }

    private NameNormaliser() {
    }

    /**
     * Reduces a name to the form the MRZ would carry: upper case, A-Z and digits only.
     *
     * @return the normalised name, never {@code null} for a non-null input
     */
    public static String normalise(String value) {
        if (value == null) {
            return null;
        }
        String upper = value.toUpperCase(Locale.ROOT);
        for (Map.Entry<String, String> entry : TRANSLITERATIONS.entrySet()) {
            upper = upper.replace(entry.getKey(), entry.getValue());
        }
        // Anything still carrying a diacritic (É, Ñ, Ç) transliterates to its base letter.
        String folded = Normalizer.normalize(upper, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "");
        return folded.replaceAll("[^A-Z0-9]", "");
    }

    /** Whether two readings of the same name genuinely disagree. */
    public static boolean differs(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        return !normalise(a).equals(normalise(b));
    }
}
