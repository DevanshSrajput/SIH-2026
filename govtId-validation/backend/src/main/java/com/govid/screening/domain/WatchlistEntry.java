package com.govid.screening.domain;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.time.LocalDate;

/** A blacklisted, stolen, revoked or otherwise flagged document or identity. */
@Document(collection = "watchlist")
public class WatchlistEntry {

    public enum ListType {
        /** Document reported lost or stolen by the issuing authority. */
        STOLEN_DOCUMENT,
        /** Issuing authority revoked or cancelled the document. */
        REVOKED_DOCUMENT,
        /** Person is subject to an entry ban. */
        ENTRY_BAN,
        /** Person is wanted by a law-enforcement agency. */
        WANTED,
        /** Person previously overstayed a visa. */
        VISA_OVERSTAY,
        /** Locally raised watch by checkpoint intelligence. */
        LOCAL_INTEREST
    }

    @Id
    private String id;

    /** Normalised (upper-cased, alphanumeric-only) document number. Nullable for name-only entries. */
    @Indexed
    private String documentNumberKey;

    /** Normalised SURNAME|GIVENNAMES|YYYY-MM-DD. Nullable for document-only entries. */
    @Indexed
    private String identityKey;

    private String displayName;
    private String nationality;
    private LocalDate dateOfBirth;

    private ListType listType;
    private Severity severity = Severity.CRITICAL;
    private String reason;
    private String source;

    private boolean active = true;
    private Instant addedAt = Instant.now();
    private String addedBy;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getDocumentNumberKey() { return documentNumberKey; }
    public void setDocumentNumberKey(String documentNumberKey) { this.documentNumberKey = documentNumberKey; }

    public String getIdentityKey() { return identityKey; }
    public void setIdentityKey(String identityKey) { this.identityKey = identityKey; }

    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }

    public String getNationality() { return nationality; }
    public void setNationality(String nationality) { this.nationality = nationality; }

    public LocalDate getDateOfBirth() { return dateOfBirth; }
    public void setDateOfBirth(LocalDate dateOfBirth) { this.dateOfBirth = dateOfBirth; }

    public ListType getListType() { return listType; }
    public void setListType(ListType listType) { this.listType = listType; }

    public Severity getSeverity() { return severity; }
    public void setSeverity(Severity severity) { this.severity = severity; }

    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }

    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }

    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }

    public Instant getAddedAt() { return addedAt; }
    public void setAddedAt(Instant addedAt) { this.addedAt = addedAt; }

    public String getAddedBy() { return addedBy; }
    public void setAddedBy(String addedBy) { this.addedBy = addedBy; }
}
