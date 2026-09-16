package com.govid.screening.ocr;

import com.govid.screening.domain.MrzData;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Parser for the ICAO 9303 Machine Readable Zone.
 *
 * <p>Supports the five layouts seen at a border checkpoint:
 * <ul>
 *   <li>TD1 - 3 lines of 30 characters (national ID cards, residence permits)</li>
 *   <li>TD2 - 2 lines of 36 characters (older ID cards, some travel documents)</li>
 *   <li>TD3 - 2 lines of 44 characters (passports)</li>
 *   <li>MRV-A - 2 lines of 44 characters (visa sticker, full size)</li>
 *   <li>MRV-B - 2 lines of 36 characters (visa sticker, reduced size)</li>
 * </ul>
 *
 * <p>Every printed check digit is recomputed. A mismatch is the single strongest
 * cheap signal that a document number, date of birth or expiry date was altered,
 * because a forger who edits the human-readable page rarely recomputes the MRZ.
 */
public final class MrzParser {

    /** Weights cycle 7, 3, 1 across the field being checked. */
    private static final int[] WEIGHTS = {7, 3, 1};

    private MrzParser() {
    }

    /**
     * Fields recovered from an MRZ, alongside the check-digit report.
     *
     * @param optionalData layout-specific trailing data (personal number on TD3,
     *                     optional data fields on TD1/TD2)
     */
    public record MrzParseResult(
            MrzData mrz,
            String documentCode,
            String issuingState,
            String surname,
            String givenNames,
            String documentNumber,
            String nationality,
            LocalDate dateOfBirth,
            String sex,
            LocalDate dateOfExpiry,
            String optionalData) {
    }

    // ------------------------------------------------------------------
    // Check digit
    // ------------------------------------------------------------------

    /**
     * Computes the ICAO 9303 check digit for a field.
     *
     * <p>Digits score their face value, letters score 10-35 (A=10 ... Z=35) and the
     * filler {@code <} scores 0. Each character is multiplied by the next weight in
     * the repeating 7-3-1 cycle and the sum is taken modulo 10.
     */
    public static int checkDigit(String field) {
        int sum = 0;
        for (int i = 0; i < field.length(); i++) {
            sum += charValue(field.charAt(i)) * WEIGHTS[i % WEIGHTS.length];
        }
        return sum % 10;
    }

    private static int charValue(char c) {
        if (c >= '0' && c <= '9') {
            return c - '0';
        }
        if (c >= 'A' && c <= 'Z') {
            return c - 'A' + 10;
        }
        if (c == '<') {
            return 0;
        }
        // Any character the OCR could not resolve contributes nothing but will almost
        // certainly make the check digit fail, which is the behaviour we want.
        return 0;
    }

    /**
     * Verifies one printed check digit.
     *
     * @return {@code true} when the printed digit matches the recomputed value.
     *         A non-numeric printed digit is always a failure.
     */
    private static boolean verify(String field, char printed) {
        if (printed < '0' || printed > '9') {
            return false;
        }
        return checkDigit(field) == printed - '0';
    }

    // ------------------------------------------------------------------
    // Line detection
    // ------------------------------------------------------------------

    /**
     * Pulls MRZ candidate lines out of a raw OCR blob.
     *
     * <p>MRZ lines are recognisable by their fixed lengths and their restricted
     * alphabet ({@code A-Z}, {@code 0-9}, {@code <}). Everything else on the page is
     * discarded.
     */
    public static List<String> extractCandidateLines(String rawText) {
        if (rawText == null || rawText.isBlank()) {
            return List.of();
        }
        List<String> candidates = new ArrayList<>();
        for (String line : rawText.split("\\R")) {
            String normalised = line.replaceAll("\\s+", "").toUpperCase();
            if (normalised.length() < 28) {
                continue;
            }
            if (!normalised.matches("[A-Z0-9<]+")) {
                continue;
            }
            // A real MRZ line is padded with filler characters; a line of pure letters
            // is far more likely to be a heading the OCR picked up.
            if (normalised.indexOf('<') < 0) {
                continue;
            }
            candidates.add(normalised);
        }
        return candidates;
    }

    // ------------------------------------------------------------------
    // Entry point
    // ------------------------------------------------------------------

    /** Parses MRZ lines that have already been isolated and normalised. */
    public static Optional<MrzParseResult> parse(List<String> rawLines) {
        List<String> lines = rawLines.stream()
                .map(l -> l == null ? "" : l.replaceAll("\\s+", "").toUpperCase())
                .filter(l -> !l.isEmpty())
                .toList();

        if (lines.size() >= 3 && lines.get(0).length() == 30
                && lines.get(1).length() == 30 && lines.get(2).length() == 30) {
            return Optional.of(parseTd1(lines.subList(0, 3)));
        }
        if (lines.size() >= 2 && lines.get(0).length() == 44 && lines.get(1).length() == 44) {
            return Optional.of(lines.get(0).charAt(0) == 'V'
                    ? parseMrv(lines.subList(0, 2), "MRV-A")
                    : parseTd3(lines.subList(0, 2)));
        }
        if (lines.size() >= 2 && lines.get(0).length() == 36 && lines.get(1).length() == 36) {
            return Optional.of(lines.get(0).charAt(0) == 'V'
                    ? parseMrv(lines.subList(0, 2), "MRV-B")
                    : parseTd2(lines.subList(0, 2)));
        }
        return Optional.empty();
    }

    /** Convenience overload that first isolates MRZ lines from a raw OCR blob. */
    public static Optional<MrzParseResult> parseFromText(String rawText) {
        List<String> candidates = extractCandidateLines(rawText);
        if (candidates.isEmpty()) {
            return Optional.empty();
        }
        // Try the longest run of same-length lines at the end of the document, which is
        // where the MRZ physically sits.
        for (int start = 0; start < candidates.size(); start++) {
            Optional<MrzParseResult> result = parse(candidates.subList(start, candidates.size()));
            if (result.isPresent()) {
                return result;
            }
        }
        return Optional.empty();
    }

    // ------------------------------------------------------------------
    // TD3 - passports, 2 x 44
    // ------------------------------------------------------------------

    private static MrzParseResult parseTd3(List<String> lines) {
        String l1 = lines.get(0);
        String l2 = lines.get(1);

        String documentNumberRaw = l2.substring(0, 9);
        String birthRaw = l2.substring(13, 19);
        String expiryRaw = l2.substring(21, 27);
        String personalRaw = l2.substring(28, 42);

        Map<String, Boolean> checks = new LinkedHashMap<>();
        checks.put("documentNumber", verify(documentNumberRaw, l2.charAt(9)));
        checks.put("dateOfBirth", verify(birthRaw, l2.charAt(19)));
        checks.put("dateOfExpiry", verify(expiryRaw, l2.charAt(27)));
        checks.put("personalNumber", verify(personalRaw, l2.charAt(42)));

        String compositeField = l2.substring(0, 10) + l2.substring(13, 20) + l2.substring(21, 43);
        boolean composite = verify(compositeField, l2.charAt(43));

        String[] names = splitNames(l1.substring(5));
        return new MrzParseResult(
                new MrzData("TD3", lines, checks, composite),
                trim(l1.substring(0, 2)),
                trim(l1.substring(2, 5)),
                names[0],
                names[1],
                trim(documentNumberRaw),
                trim(l2.substring(10, 13)),
                parseDate(birthRaw, false),
                sex(l2.charAt(20)),
                parseDate(expiryRaw, true),
                trim(personalRaw));
    }

    // ------------------------------------------------------------------
    // TD2 - 2 x 36
    // ------------------------------------------------------------------

    private static MrzParseResult parseTd2(List<String> lines) {
        String l1 = lines.get(0);
        String l2 = lines.get(1);

        String documentNumberRaw = l2.substring(0, 9);
        String birthRaw = l2.substring(13, 19);
        String expiryRaw = l2.substring(21, 27);
        String optionalRaw = l2.substring(28, 35);

        Map<String, Boolean> checks = new LinkedHashMap<>();
        checks.put("documentNumber", verify(documentNumberRaw, l2.charAt(9)));
        checks.put("dateOfBirth", verify(birthRaw, l2.charAt(19)));
        checks.put("dateOfExpiry", verify(expiryRaw, l2.charAt(27)));

        String compositeField = l2.substring(0, 10) + l2.substring(13, 20) + l2.substring(21, 35);
        boolean composite = verify(compositeField, l2.charAt(35));

        String[] names = splitNames(l1.substring(5));
        return new MrzParseResult(
                new MrzData("TD2", lines, checks, composite),
                trim(l1.substring(0, 2)),
                trim(l1.substring(2, 5)),
                names[0],
                names[1],
                trim(documentNumberRaw),
                trim(l2.substring(10, 13)),
                parseDate(birthRaw, false),
                sex(l2.charAt(20)),
                parseDate(expiryRaw, true),
                trim(optionalRaw));
    }

    // ------------------------------------------------------------------
    // TD1 - 3 x 30
    // ------------------------------------------------------------------

    private static MrzParseResult parseTd1(List<String> lines) {
        String l1 = lines.get(0);
        String l2 = lines.get(1);
        String l3 = lines.get(2);

        String documentNumberRaw = l1.substring(5, 14);
        String optional1 = l1.substring(15, 30);
        String birthRaw = l2.substring(0, 6);
        String expiryRaw = l2.substring(8, 14);
        String optional2 = l2.substring(18, 29);

        Map<String, Boolean> checks = new LinkedHashMap<>();
        checks.put("documentNumber", verify(documentNumberRaw, l1.charAt(14)));
        checks.put("dateOfBirth", verify(birthRaw, l2.charAt(6)));
        checks.put("dateOfExpiry", verify(expiryRaw, l2.charAt(14)));

        String compositeField = l1.substring(5, 30) + l2.substring(0, 7)
                + l2.substring(8, 15) + l2.substring(18, 29);
        boolean composite = verify(compositeField, l2.charAt(29));

        String[] names = splitNames(l3);
        String optional = trim(optional1) + trim(optional2);
        return new MrzParseResult(
                new MrzData("TD1", lines, checks, composite),
                trim(l1.substring(0, 2)),
                trim(l1.substring(2, 5)),
                names[0],
                names[1],
                trim(documentNumberRaw),
                trim(l2.substring(15, 18)),
                parseDate(birthRaw, false),
                sex(l2.charAt(7)),
                parseDate(expiryRaw, true),
                optional.isEmpty() ? null : optional);
    }

    // ------------------------------------------------------------------
    // MRV-A / MRV-B - visa stickers
    // ------------------------------------------------------------------

    /**
     * Machine readable visas carry no composite check digit, so {@code composite} is
     * reported as {@code null} rather than {@code false}.
     */
    private static MrzParseResult parseMrv(List<String> lines, String format) {
        String l1 = lines.get(0);
        String l2 = lines.get(1);

        String documentNumberRaw = l2.substring(0, 9);
        String birthRaw = l2.substring(13, 19);
        String expiryRaw = l2.substring(21, 27);
        String optionalRaw = l2.substring(28);

        Map<String, Boolean> checks = new LinkedHashMap<>();
        checks.put("documentNumber", verify(documentNumberRaw, l2.charAt(9)));
        checks.put("dateOfBirth", verify(birthRaw, l2.charAt(19)));
        checks.put("dateOfExpiry", verify(expiryRaw, l2.charAt(27)));

        String[] names = splitNames(l1.substring(5));
        return new MrzParseResult(
                new MrzData(format, lines, checks, null),
                trim(l1.substring(0, 2)),
                trim(l1.substring(2, 5)),
                names[0],
                names[1],
                trim(documentNumberRaw),
                trim(l2.substring(10, 13)),
                parseDate(birthRaw, false),
                sex(l2.charAt(20)),
                parseDate(expiryRaw, true),
                trim(optionalRaw));
    }

    // ------------------------------------------------------------------
    // Field helpers
    // ------------------------------------------------------------------

    /**
     * Splits an ICAO name field into surname and given names.
     * The two identifiers are separated by {@code <<} and words within each by {@code <}.
     */
    private static String[] splitNames(String nameField) {
        String field = nameField.replaceAll("<+$", "");
        int separator = field.indexOf("<<");
        String surname;
        String given;
        if (separator < 0) {
            surname = field;
            given = "";
        } else {
            surname = field.substring(0, separator);
            given = field.substring(separator + 2);
        }
        return new String[]{cleanName(surname), cleanName(given)};
    }

    private static String cleanName(String value) {
        String cleaned = value.replace('<', ' ').replaceAll("\\s+", " ").trim();
        return cleaned.isEmpty() ? null : cleaned;
    }

    private static String trim(String value) {
        String cleaned = value.replace("<", "").trim();
        return cleaned.isEmpty() ? null : cleaned;
    }

    private static String sex(char c) {
        return switch (c) {
            case 'M' -> "M";
            case 'F' -> "F";
            default -> "X";
        };
    }

    /**
     * Converts a {@code YYMMDD} MRZ date to a calendar date.
     *
     * <p>The MRZ has no century. A date of birth is resolved into the past; an expiry
     * date is resolved forward, treating a two-digit year below 70 as 20xx. Returns
     * {@code null} for an unparseable date so Module 2 can raise it as a finding.
     */
    static LocalDate parseDate(String yymmdd, boolean futureLeaning) {
        if (yymmdd == null || !yymmdd.matches("\\d{6}")) {
            return null;
        }
        int yy = Integer.parseInt(yymmdd.substring(0, 2));
        int month = Integer.parseInt(yymmdd.substring(2, 4));
        int day = Integer.parseInt(yymmdd.substring(4, 6));

        int currentTwoDigitYear = LocalDate.now().getYear() % 100;
        int year;
        if (futureLeaning) {
            year = yy < 70 ? 2000 + yy : 1900 + yy;
        } else {
            year = yy > currentTwoDigitYear ? 1900 + yy : 2000 + yy;
        }

        try {
            return LocalDate.of(year, month, day);
        } catch (java.time.DateTimeException e) {
            // Impossible calendar date, e.g. month 13 or 30 February. Reported as a
            // missing date so Module 2 raises it rather than the pipeline aborting.
            return null;
        }
    }
}
