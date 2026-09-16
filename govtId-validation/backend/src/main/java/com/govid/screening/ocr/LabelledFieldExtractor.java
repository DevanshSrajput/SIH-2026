package com.govid.screening.ocr;

import com.govid.screening.domain.ExtractedFields;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Recovers fields from the printed, human-readable part of a document.
 *
 * <p>Visas carry no standard machine-readable encoding of their own terms - entry count,
 * stay duration and visa class exist only as printed labels - so those fields have to be
 * read from the page. The same applies to any document whose MRZ is missing or damaged.
 *
 * <p>Values found here are treated as lower-trust than MRZ values: they are only written
 * into fields the MRZ did not already fill.
 */
public final class LabelledFieldExtractor {

    private static final Pattern VISA_NUMBER = Pattern.compile(
            "VISA\\s*(?:NO|NUMBER|NUM|#)\\.?\\s*[:\\-]?\\s*([A-Z0-9]{6,15})");

    private static final Pattern VISA_TYPE = Pattern.compile(
            "(?:VISA\\s*)?(?:TYPE|CLASS|CATEGORY)\\s*[:\\-]?\\s*([A-Z0-9][A-Z0-9/\\-]{0,14})");

    private static final Pattern ENTRIES = Pattern.compile(
            "(?:NUMBER\\s+OF\\s+ENTRIES|ENTRIES|ENTRY)\\s*[:\\-]?\\s*(SINGLE|DOUBLE|MULTIPLE|MULT|M|S)\\b");

    private static final Pattern STAY_DURATION = Pattern.compile(
            "(?:DURATION\\s+OF\\s+STAY|LENGTH\\s+OF\\s+STAY|STAY)\\s*[:\\-]?\\s*(\\d{1,3})");

    private static final Pattern STAY_DAYS_SUFFIX = Pattern.compile(
            "(\\d{1,3})\\s*DAYS?\\b");

    private static final Pattern VALID_FROM = Pattern.compile(
            "(?:VALID\\s*FROM|FROM|ISSUE\\s*DATE|DATE\\s*OF\\s*ISSUE)\\s*[:\\-]?\\s*" + DatePatterns.GROUP);

    private static final Pattern VALID_UNTIL = Pattern.compile(
            "(?:VALID\\s*UNTIL|VALID\\s*TO|UNTIL|EXPIR\\w*\\s*(?:DATE)?|DATE\\s*OF\\s*EXPIRY)"
                    + "\\s*[:\\-]?\\s*" + DatePatterns.GROUP);

    private static final Pattern DATE_OF_BIRTH = Pattern.compile(
            "(?:DATE\\s*OF\\s*BIRTH|BIRTH\\s*DATE|D\\.?O\\.?B\\.?)\\s*[:\\-]?\\s*" + DatePatterns.GROUP);

    private static final Pattern SURNAME = Pattern.compile(
            "(?:SURNAME|LAST\\s*NAME|FAMILY\\s*NAME)\\s*[:\\-]?\\s*([A-Z][A-Z '\\-]{1,40})");

    private static final Pattern GIVEN_NAMES = Pattern.compile(
            "(?:GIVEN\\s*NAMES?|FIRST\\s*NAMES?|FORENAMES?)\\s*[:\\-]?\\s*([A-Z][A-Z '\\-]{1,40})");

    private static final Pattern DOCUMENT_NUMBER = Pattern.compile(
            "(?:PASSPORT\\s*(?:NO|NUMBER)|DOCUMENT\\s*(?:NO|NUMBER)|ID\\s*(?:NO|NUMBER)"
                    + "|LICENCE\\s*(?:NO|NUMBER)|LICENSE\\s*(?:NO|NUMBER))\\.?\\s*[:\\-]?\\s*([A-Z0-9]{5,20})");

    private LabelledFieldExtractor() {
    }

    /**
     * Fills gaps in {@code target} from the printed text.
     * Existing values are never overwritten, so MRZ data always wins.
     */
    public static void enrich(ExtractedFields target, String rawText) {
        if (rawText == null || rawText.isBlank()) {
            return;
        }
        String text = rawText.toUpperCase(Locale.ROOT);

        if (target.getVisaNumber() == null) {
            target.setVisaNumber(firstGroup(VISA_NUMBER, text));
        }
        if (target.getVisaType() == null) {
            target.setVisaType(firstGroup(VISA_TYPE, text));
        }
        if (target.getEntryType() == null) {
            target.setEntryType(normaliseEntryType(firstGroup(ENTRIES, text)));
        }
        if (target.getStayDurationDays() == null) {
            String days = firstGroup(STAY_DURATION, text);
            if (days == null) {
                days = firstGroup(STAY_DAYS_SUFFIX, text);
            }
            target.setStayDurationDays(days == null ? null : Integer.valueOf(days));
        }
        if (target.getValidFrom() == null) {
            target.setValidFrom(DatePatterns.parse(firstGroup(VALID_FROM, text)));
        }
        if (target.getValidUntil() == null) {
            target.setValidUntil(DatePatterns.parse(firstGroup(VALID_UNTIL, text)));
        }
        if (target.getDateOfBirth() == null) {
            target.setDateOfBirth(DatePatterns.parse(firstGroup(DATE_OF_BIRTH, text)));
        }
        if (target.getSurname() == null) {
            target.setSurname(tidy(firstGroup(SURNAME, text)));
        }
        if (target.getGivenNames() == null) {
            target.setGivenNames(tidy(firstGroup(GIVEN_NAMES, text)));
        }
        if (target.getDocumentNumber() == null) {
            target.setDocumentNumber(firstGroup(DOCUMENT_NUMBER, text));
        }
        // A visa page prints its own expiry as "valid until"; carry it across so the
        // expiry checks in Module 2 have something to work with.
        if (target.getDateOfExpiry() == null && target.getValidUntil() != null) {
            target.setDateOfExpiry(target.getValidUntil());
        }
    }

    private static String firstGroup(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        return matcher.find() ? matcher.group(1).trim() : null;
    }

    private static String tidy(String value) {
        if (value == null) {
            return null;
        }
        String cleaned = value.replaceAll("\\s+", " ").trim();
        return cleaned.isEmpty() ? null : cleaned;
    }

    private static String normaliseEntryType(String value) {
        if (value == null) {
            return null;
        }
        return switch (value) {
            case "S", "SINGLE" -> "SINGLE";
            case "DOUBLE" -> "DOUBLE";
            case "M", "MULT", "MULTIPLE" -> "MULTIPLE";
            default -> value;
        };
    }

    /** Date formats seen on travel documents, tried in order of specificity. */
    static final class DatePatterns {

        /** Regex fragment matching any supported printed date, as one capture group. */
        static final String GROUP =
                "(\\d{1,2}[\\s./\\-]{1,3}[A-Z]{3}[\\s./\\-]{1,3}\\d{2,4}"
                        + "|\\d{4}[./\\-]\\d{2}[./\\-]\\d{2}"
                        + "|\\d{1,2}[./\\-]\\d{1,2}[./\\-]\\d{2,4})";

        private static final List<DateTimeFormatter> FORMATS = List.of(
                DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH),
                DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.ENGLISH),
                DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.ENGLISH),
                DateTimeFormatter.ofPattern("d/M/yyyy", Locale.ENGLISH),
                DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale.ENGLISH),
                DateTimeFormatter.ofPattern("d/M/yy", Locale.ENGLISH),
                DateTimeFormatter.ofPattern("dd/MM/yy", Locale.ENGLISH));

        private DatePatterns() {
        }

        /**
         * Parses a printed date, normalising separators first.
         *
         * <p>Ambiguous all-numeric dates are read day-first, the convention on every
         * ICAO travel document. Returns {@code null} when nothing parses, which Module 2
         * reports as a missing date rather than guessing.
         */
        static LocalDate parse(String value) {
            if (value == null) {
                return null;
            }
            String normalised = value.replaceAll("[\\s.\\-]+", "/").replace("//", "/").trim();
            if (normalised.matches("\\d{4}/\\d{2}/\\d{2}")) {
                normalised = normalised.replace('/', '-');
            }
            for (DateTimeFormatter format : FORMATS) {
                try {
                    return LocalDate.parse(normalised, format);
                } catch (java.time.format.DateTimeParseException ignored) {
                    // Try the next layout.
                }
            }
            return null;
        }
    }
}
