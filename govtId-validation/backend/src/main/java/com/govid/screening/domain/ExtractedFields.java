package com.govid.screening.domain;

import java.time.LocalDate;

/**
 * Fields recovered from a document by Module 1. Every field is nullable: OCR on a
 * damaged or partially visible document legitimately returns gaps, and Module 2
 * treats a missing mandatory field as a finding rather than an error.
 */
public class ExtractedFields {

    // --- Identity (passport / national ID / licence / permit) ---
    private String surname;
    private String givenNames;
    private String documentNumber;
    private String issuingState;   // ISO 3166-1 alpha-3
    private String nationality;    // ISO 3166-1 alpha-3
    private LocalDate dateOfBirth;
    private LocalDate dateOfIssue;
    private LocalDate dateOfExpiry;
    private String sex;            // M / F / X
    private String personalNumber;

    // --- Visa specific ---
    private String visaNumber;
    private String visaType;       // e.g. B1/B2, TOURIST, WORK
    private String entryType;      // SINGLE / DOUBLE / MULTIPLE
    private Integer stayDurationDays;
    private LocalDate validFrom;
    private LocalDate validUntil;

    // --- Printed-page values, captured independently of the MRZ ---
    // A genuine document says the same thing twice. A forger who edits the printed data
    // page rarely re-encodes the MRZ (and vice versa), so disagreement between these and
    // the fields above is direct evidence of text manipulation.
    private String printedSurname;
    private String printedGivenNames;
    private String printedDocumentNumber;
    private LocalDate printedDateOfBirth;
    private LocalDate printedDateOfExpiry;

    // --- Provenance ---
    private MrzData mrz;
    private String rawText;
    private Double ocrConfidence;  // 0.0 - 1.0
    private String engine;         // which OcrEngine produced this

    public String fullName() {
        if (surname == null && givenNames == null) {
            return null;
        }
        if (surname == null) {
            return givenNames;
        }
        if (givenNames == null) {
            return surname;
        }
        return givenNames + " " + surname;
    }

    public String getSurname() { return surname; }
    public void setSurname(String surname) { this.surname = surname; }

    public String getGivenNames() { return givenNames; }
    public void setGivenNames(String givenNames) { this.givenNames = givenNames; }

    public String getDocumentNumber() { return documentNumber; }
    public void setDocumentNumber(String documentNumber) { this.documentNumber = documentNumber; }

    public String getIssuingState() { return issuingState; }
    public void setIssuingState(String issuingState) { this.issuingState = issuingState; }

    public String getNationality() { return nationality; }
    public void setNationality(String nationality) { this.nationality = nationality; }

    public LocalDate getDateOfBirth() { return dateOfBirth; }
    public void setDateOfBirth(LocalDate dateOfBirth) { this.dateOfBirth = dateOfBirth; }

    public LocalDate getDateOfIssue() { return dateOfIssue; }
    public void setDateOfIssue(LocalDate dateOfIssue) { this.dateOfIssue = dateOfIssue; }

    public LocalDate getDateOfExpiry() { return dateOfExpiry; }
    public void setDateOfExpiry(LocalDate dateOfExpiry) { this.dateOfExpiry = dateOfExpiry; }

    public String getSex() { return sex; }
    public void setSex(String sex) { this.sex = sex; }

    public String getPersonalNumber() { return personalNumber; }
    public void setPersonalNumber(String personalNumber) { this.personalNumber = personalNumber; }

    public String getVisaNumber() { return visaNumber; }
    public void setVisaNumber(String visaNumber) { this.visaNumber = visaNumber; }

    public String getVisaType() { return visaType; }
    public void setVisaType(String visaType) { this.visaType = visaType; }

    public String getEntryType() { return entryType; }
    public void setEntryType(String entryType) { this.entryType = entryType; }

    public Integer getStayDurationDays() { return stayDurationDays; }
    public void setStayDurationDays(Integer stayDurationDays) { this.stayDurationDays = stayDurationDays; }

    public LocalDate getValidFrom() { return validFrom; }
    public void setValidFrom(LocalDate validFrom) { this.validFrom = validFrom; }

    public LocalDate getValidUntil() { return validUntil; }
    public void setValidUntil(LocalDate validUntil) { this.validUntil = validUntil; }

    public String getPrintedSurname() { return printedSurname; }
    public void setPrintedSurname(String printedSurname) { this.printedSurname = printedSurname; }

    public String getPrintedGivenNames() { return printedGivenNames; }
    public void setPrintedGivenNames(String printedGivenNames) { this.printedGivenNames = printedGivenNames; }

    public String getPrintedDocumentNumber() { return printedDocumentNumber; }
    public void setPrintedDocumentNumber(String printedDocumentNumber) { this.printedDocumentNumber = printedDocumentNumber; }

    public LocalDate getPrintedDateOfBirth() { return printedDateOfBirth; }
    public void setPrintedDateOfBirth(LocalDate printedDateOfBirth) { this.printedDateOfBirth = printedDateOfBirth; }

    public LocalDate getPrintedDateOfExpiry() { return printedDateOfExpiry; }
    public void setPrintedDateOfExpiry(LocalDate printedDateOfExpiry) { this.printedDateOfExpiry = printedDateOfExpiry; }

    public MrzData getMrz() { return mrz; }
    public void setMrz(MrzData mrz) { this.mrz = mrz; }

    public String getRawText() { return rawText; }
    public void setRawText(String rawText) { this.rawText = rawText; }

    public Double getOcrConfidence() { return ocrConfidence; }
    public void setOcrConfidence(Double ocrConfidence) { this.ocrConfidence = ocrConfidence; }

    public String getEngine() { return engine; }
    public void setEngine(String engine) { this.engine = engine; }
}
