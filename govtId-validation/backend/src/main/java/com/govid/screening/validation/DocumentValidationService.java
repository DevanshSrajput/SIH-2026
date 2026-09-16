package com.govid.screening.validation;

import com.govid.screening.domain.DocumentType;
import com.govid.screening.domain.ExtractedFields;
import com.govid.screening.domain.ModuleResult;
import com.govid.screening.domain.MrzData;
import com.govid.screening.domain.RiskFlag;
import com.govid.screening.domain.ScreeningModule;
import com.govid.screening.domain.Severity;
import com.govid.screening.support.NameNormaliser;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.Period;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Module 2 - Document Validation.
 *
 * <p>Decides whether the extracted values are internally consistent and conform to the
 * standards the issuing authority had to follow. Every check here is deterministic and
 * explainable: an officer can be told exactly which rule fired and why, and the same
 * inputs always produce the same finding.
 *
 * <p>The highest-value checks are the ones a forger cannot easily satisfy:
 * <ul>
 *   <li><b>MRZ check digits</b> - editing a printed date or number without recomputing
 *       the checksum leaves an arithmetic contradiction in the document.</li>
 *   <li><b>Data page vs MRZ agreement</b> - a genuine document encodes the same identity
 *       twice; a partially altered one does not.</li>
 *   <li><b>Chronological coherence</b> - expiry before birth, issue after expiry, or a
 *       validity period longer than the issuing standard allows.</li>
 * </ul>
 */
@Service
public class DocumentValidationService {

    /** Flag a document that is still valid but close to expiry. */
    private static final int EXPIRING_SOON_DAYS = 180;

    /** Longest validity an ordinary passport is issued for, plus tolerance. */
    private static final int MAX_PASSPORT_VALIDITY_YEARS = 10;

    private static final int MAX_PLAUSIBLE_AGE_YEARS = 120;

    private final Clock clock;

    public DocumentValidationService(Clock clock) {
        this.clock = clock;
    }

    public ModuleResult validate(DocumentType documentType, ExtractedFields fields) {
        long start = System.nanoTime();

        if (fields == null) {
            return ModuleResult.skipped(ScreeningModule.DOCUMENT_VALIDATION,
                    "No extracted fields; Module 1 did not produce a readable document.");
        }

        List<RiskFlag> flags = new ArrayList<>();
        Map<String, Object> details = new LinkedHashMap<>();
        LocalDate today = LocalDate.now(clock);

        checkMrzCheckDigits(fields, flags, details);
        checkDataPageAgainstMrz(fields, flags);
        checkMandatoryFields(documentType, fields, flags);
        checkCodes(fields, flags);
        checkDocumentNumberFormat(documentType, fields, flags);
        checkChronology(documentType, fields, flags, today, details);

        if (documentType == DocumentType.VISA) {
            checkVisa(fields, flags, today);
        }

        details.put("checksApplied", true);
        details.put("findingCount", flags.size());

        return new ModuleResult(ScreeningModule.DOCUMENT_VALIDATION, ModuleResult.Status.COMPLETED,
                elapsed(start), flags, details,
                flags.isEmpty() ? "All standards checks passed" : flags.size() + " finding(s)");
    }

    // ------------------------------------------------------------------
    // MRZ arithmetic
    // ------------------------------------------------------------------

    private void checkMrzCheckDigits(ExtractedFields fields, List<RiskFlag> flags,
                                     Map<String, Object> details) {
        MrzData mrz = fields.getMrz();
        if (mrz == null) {
            details.put("mrzChecked", false);
            return;
        }
        details.put("mrzChecked", true);
        details.put("mrzFormat", mrz.format());
        details.put("mrzCheckDigits", mrz.checkDigits());

        for (String field : mrz.failedCheckDigits()) {
            flags.add(RiskFlag.of("MRZ_CHECKDIGIT_MISMATCH", ScreeningModule.DOCUMENT_VALIDATION,
                    Severity.HIGH,
                    "The MRZ check digit for " + humanise(field) + " does not match the printed value. "
                            + "This field was almost certainly altered after the document was issued.",
                    Map.of("field", field, "mrzFormat", mrz.format())));
        }

        if (Boolean.FALSE.equals(mrz.composite())) {
            flags.add(RiskFlag.of("MRZ_COMPOSITE_MISMATCH", ScreeningModule.DOCUMENT_VALIDATION,
                    Severity.HIGH,
                    "The MRZ composite check digit does not match. The machine readable zone "
                            + "as a whole is not self-consistent.",
                    Map.of("mrzFormat", mrz.format())));
        }
    }

    // ------------------------------------------------------------------
    // Data page vs MRZ
    // ------------------------------------------------------------------

    /**
     * Compares the printed page against the MRZ.
     *
     * <p>Only raises a finding when both readings exist. A value the OCR recovered from
     * only one of the two is a gap, not a contradiction, and is handled elsewhere.
     */
    private void checkDataPageAgainstMrz(ExtractedFields fields, List<RiskFlag> flags) {
        if (fields.getMrz() == null) {
            return;
        }

        compareText(flags, "DATAPAGE_MRZ_SURNAME_MISMATCH", Severity.HIGH, "surname",
                fields.getSurname(), fields.getPrintedSurname());
        compareText(flags, "DATAPAGE_MRZ_GIVENNAMES_MISMATCH", Severity.MEDIUM, "given names",
                fields.getGivenNames(), fields.getPrintedGivenNames());

        if (differs(fields.getDocumentNumber(), fields.getPrintedDocumentNumber())) {
            flags.add(RiskFlag.of("DATAPAGE_MRZ_DOCNUMBER_MISMATCH", ScreeningModule.DOCUMENT_VALIDATION,
                    Severity.CRITICAL,
                    "The document number printed on the data page does not match the number "
                            + "encoded in the MRZ.",
                    Map.of("mrz", fields.getDocumentNumber(),
                            "printed", fields.getPrintedDocumentNumber())));
        }

        if (fields.getDateOfBirth() != null && fields.getPrintedDateOfBirth() != null
                && !fields.getDateOfBirth().equals(fields.getPrintedDateOfBirth())) {
            flags.add(RiskFlag.of("DATAPAGE_MRZ_DOB_MISMATCH", ScreeningModule.DOCUMENT_VALIDATION,
                    Severity.CRITICAL,
                    "The date of birth printed on the data page does not match the MRZ.",
                    Map.of("mrz", fields.getDateOfBirth().toString(),
                            "printed", fields.getPrintedDateOfBirth().toString())));
        }

        if (fields.getDateOfExpiry() != null && fields.getPrintedDateOfExpiry() != null
                && !fields.getDateOfExpiry().equals(fields.getPrintedDateOfExpiry())) {
            flags.add(RiskFlag.of("DATAPAGE_MRZ_EXPIRY_MISMATCH", ScreeningModule.DOCUMENT_VALIDATION,
                    Severity.CRITICAL,
                    "The expiry date printed on the data page does not match the MRZ.",
                    Map.of("mrz", fields.getDateOfExpiry().toString(),
                            "printed", fields.getPrintedDateOfExpiry().toString())));
        }
    }

    private void compareText(List<RiskFlag> flags, String code, Severity severity, String label,
                             String mrzValue, String printedValue) {
        if (!differs(mrzValue, printedValue)) {
            return;
        }
        flags.add(RiskFlag.of(code, ScreeningModule.DOCUMENT_VALIDATION, severity,
                "The " + label + " printed on the data page does not match the MRZ.",
                Map.of("mrz", mrzValue, "printed", printedValue)));
    }

    /**
     * Whether two readings of the same field genuinely disagree.
     *
     * <p>Comparison goes through {@link NameNormaliser} because the MRZ transliterates
     * names into a restricted alphabet: {@code MUELLER} in the MRZ and {@code MÜLLER} on
     * the page are the same name, and flagging that would bury officers in false hits.
     */
    private static boolean differs(String mrzValue, String printedValue) {
        return NameNormaliser.differs(mrzValue, printedValue);
    }

    // ------------------------------------------------------------------
    // Presence and format
    // ------------------------------------------------------------------

    private void checkMandatoryFields(DocumentType documentType, ExtractedFields fields,
                                      List<RiskFlag> flags) {
        List<String> missing = new ArrayList<>();
        if (fields.getSurname() == null && fields.getGivenNames() == null) {
            missing.add("name");
        }
        if (fields.getDocumentNumber() == null && fields.getVisaNumber() == null) {
            missing.add("document number");
        }
        if (fields.getDateOfBirth() == null) {
            missing.add("date of birth");
        }
        if (fields.getDateOfExpiry() == null && documentType != DocumentType.NATIONAL_ID) {
            missing.add("date of expiry");
        }
        if (fields.getNationality() == null && documentType == DocumentType.PASSPORT) {
            missing.add("nationality");
        }

        for (String field : missing) {
            flags.add(RiskFlag.of("MANDATORY_FIELD_MISSING", ScreeningModule.DOCUMENT_VALIDATION,
                    Severity.MEDIUM,
                    "Mandatory field could not be established: " + field + ".",
                    Map.of("field", field)));
        }
    }

    private void checkCodes(ExtractedFields fields, List<RiskFlag> flags) {
        if (fields.getIssuingState() != null && !CountryCodes.isValid(fields.getIssuingState())) {
            flags.add(RiskFlag.of("UNKNOWN_ISSUING_STATE", ScreeningModule.DOCUMENT_VALIDATION,
                    Severity.HIGH,
                    "The issuing state code is not a recognised country or ICAO code.",
                    Map.of("issuingState", fields.getIssuingState())));
        }
        if (fields.getNationality() != null && !CountryCodes.isValid(fields.getNationality())) {
            flags.add(RiskFlag.of("UNKNOWN_NATIONALITY", ScreeningModule.DOCUMENT_VALIDATION,
                    Severity.HIGH,
                    "The nationality code is not a recognised country or ICAO code.",
                    Map.of("nationality", fields.getNationality())));
        }
        if (fields.getSex() != null && !List.of("M", "F", "X").contains(fields.getSex())) {
            flags.add(RiskFlag.of("INVALID_SEX_CODE", ScreeningModule.DOCUMENT_VALIDATION,
                    Severity.LOW,
                    "The sex field is not one of the permitted values M, F or X.",
                    Map.of("sex", fields.getSex())));
        }
    }

    /**
     * ICAO limits the passport number field to nine characters from a restricted
     * alphabet. A longer or otherwise malformed value means the field was mis-read or
     * the document does not follow the standard it claims to.
     */
    private void checkDocumentNumberFormat(DocumentType documentType, ExtractedFields fields,
                                           List<RiskFlag> flags) {
        String number = fields.getDocumentNumber();
        if (number == null || documentType != DocumentType.PASSPORT) {
            return;
        }
        if (!number.matches("[A-Z0-9]{1,9}")) {
            flags.add(RiskFlag.of("DOCUMENT_NUMBER_FORMAT", ScreeningModule.DOCUMENT_VALIDATION,
                    Severity.MEDIUM,
                    "The passport number does not follow the ICAO format of up to nine "
                            + "alphanumeric characters.",
                    Map.of("documentNumber", number, "length", number.length())));
        }
    }

    // ------------------------------------------------------------------
    // Chronology
    // ------------------------------------------------------------------

    private void checkChronology(DocumentType documentType, ExtractedFields fields,
                                 List<RiskFlag> flags, LocalDate today,
                                 Map<String, Object> details) {
        LocalDate dob = fields.getDateOfBirth();
        LocalDate expiry = fields.getDateOfExpiry();
        LocalDate issue = fields.getDateOfIssue();

        if (dob != null) {
            if (dob.isAfter(today)) {
                flags.add(RiskFlag.of("DOB_IN_FUTURE", ScreeningModule.DOCUMENT_VALIDATION,
                        Severity.CRITICAL,
                        "The date of birth is in the future.",
                        Map.of("dateOfBirth", dob.toString())));
            } else {
                int age = Period.between(dob, today).getYears();
                details.put("age", age);
                if (age > MAX_PLAUSIBLE_AGE_YEARS) {
                    flags.add(RiskFlag.of("DOB_IMPLAUSIBLE", ScreeningModule.DOCUMENT_VALIDATION,
                            Severity.HIGH,
                            "The date of birth implies an age of " + age + " years.",
                            Map.of("dateOfBirth", dob.toString(), "age", age)));
                }
            }
        }

        if (expiry != null) {
            details.put("expiry", expiry.toString());
            if (expiry.isBefore(today)) {
                long daysExpired = ChronoUnit.DAYS.between(expiry, today);
                flags.add(RiskFlag.of("DOCUMENT_EXPIRED", ScreeningModule.DOCUMENT_VALIDATION,
                        Severity.HIGH,
                        "The document expired " + daysExpired + " day(s) ago on " + expiry + ".",
                        Map.of("dateOfExpiry", expiry.toString(), "daysExpired", daysExpired)));
            } else {
                long daysRemaining = ChronoUnit.DAYS.between(today, expiry);
                details.put("daysUntilExpiry", daysRemaining);
                if (daysRemaining <= EXPIRING_SOON_DAYS) {
                    flags.add(RiskFlag.of("DOCUMENT_EXPIRING_SOON", ScreeningModule.DOCUMENT_VALIDATION,
                            Severity.LOW,
                            "The document expires in " + daysRemaining + " day(s). Many states "
                                    + "require at least six months of remaining validity on entry.",
                            Map.of("dateOfExpiry", expiry.toString(), "daysRemaining", daysRemaining)));
                }
            }
        }

        if (dob != null && expiry != null && expiry.isBefore(dob)) {
            flags.add(RiskFlag.of("EXPIRY_BEFORE_BIRTH", ScreeningModule.DOCUMENT_VALIDATION,
                    Severity.CRITICAL,
                    "The expiry date precedes the date of birth. The document cannot be genuine.",
                    Map.of("dateOfBirth", dob.toString(), "dateOfExpiry", expiry.toString())));
        }

        if (issue != null && expiry != null) {
            if (issue.isAfter(expiry)) {
                flags.add(RiskFlag.of("ISSUE_AFTER_EXPIRY", ScreeningModule.DOCUMENT_VALIDATION,
                        Severity.HIGH,
                        "The issue date is later than the expiry date.",
                        Map.of("dateOfIssue", issue.toString(), "dateOfExpiry", expiry.toString())));
            } else if (documentType == DocumentType.PASSPORT
                    && issue.plusYears(MAX_PASSPORT_VALIDITY_YEARS).plusMonths(1).isBefore(expiry)) {
                flags.add(RiskFlag.of("VALIDITY_PERIOD_EXCEEDS_STANDARD",
                        ScreeningModule.DOCUMENT_VALIDATION, Severity.MEDIUM,
                        "The validity period exceeds the " + MAX_PASSPORT_VALIDITY_YEARS
                                + "-year maximum for an ordinary passport.",
                        Map.of("dateOfIssue", issue.toString(), "dateOfExpiry", expiry.toString())));
            }
        }
    }

    // ------------------------------------------------------------------
    // Visa terms
    // ------------------------------------------------------------------

    private void checkVisa(ExtractedFields fields, List<RiskFlag> flags, LocalDate today) {
        LocalDate from = fields.getValidFrom();
        LocalDate until = fields.getValidUntil();

        if (from != null && until != null && from.isAfter(until)) {
            flags.add(RiskFlag.of("VISA_VALID_FROM_AFTER_UNTIL", ScreeningModule.DOCUMENT_VALIDATION,
                    Severity.HIGH,
                    "The visa validity window starts after it ends.",
                    Map.of("validFrom", from.toString(), "validUntil", until.toString())));
        }

        if (until != null && until.isBefore(today)) {
            flags.add(RiskFlag.of("VISA_EXPIRED", ScreeningModule.DOCUMENT_VALIDATION,
                    Severity.HIGH,
                    "The visa expired on " + until + ".",
                    Map.of("validUntil", until.toString())));
        }

        if (from != null && from.isAfter(today)) {
            flags.add(RiskFlag.of("VISA_NOT_YET_VALID", ScreeningModule.DOCUMENT_VALIDATION,
                    Severity.MEDIUM,
                    "The visa is not valid until " + from + ".",
                    Map.of("validFrom", from.toString())));
        }

        Integer stay = fields.getStayDurationDays();
        if (stay != null && from != null && until != null) {
            long window = ChronoUnit.DAYS.between(from, until) + 1;
            if (stay > window) {
                flags.add(RiskFlag.of("VISA_STAY_EXCEEDS_VALIDITY", ScreeningModule.DOCUMENT_VALIDATION,
                        Severity.MEDIUM,
                        "The permitted stay of " + stay + " days is longer than the "
                                + window + "-day validity window.",
                        Map.of("stayDurationDays", stay, "validityWindowDays", window)));
            }
        }

        if (fields.getEntryType() != null
                && !List.of("SINGLE", "DOUBLE", "MULTIPLE").contains(fields.getEntryType())) {
            flags.add(RiskFlag.of("VISA_UNKNOWN_ENTRY_TYPE", ScreeningModule.DOCUMENT_VALIDATION,
                    Severity.LOW,
                    "The entry type could not be resolved to SINGLE, DOUBLE or MULTIPLE.",
                    Map.of("entryType", fields.getEntryType())));
        }
    }

    // ------------------------------------------------------------------

    private static String humanise(String field) {
        return switch (field) {
            case "documentNumber" -> "the document number";
            case "dateOfBirth" -> "the date of birth";
            case "dateOfExpiry" -> "the expiry date";
            case "personalNumber" -> "the personal number";
            default -> field;
        };
    }

    private static long elapsed(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }
}
