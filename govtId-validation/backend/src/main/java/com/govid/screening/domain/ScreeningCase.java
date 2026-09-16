package com.govid.screening.domain;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** One document presented at a checkpoint and everything the platform concluded about it. */
@Document(collection = "screening_cases")
public class ScreeningCase {

    public enum Status { RECEIVED, PROCESSING, COMPLETED, FAILED }

    @Id
    private String id;

    /** Human-quotable reference printed on the officer console, e.g. BRD-8F3K2Q. */
    @Indexed(unique = true)
    private String caseReference;

    private String checkpointId;
    private String officerId;
    private String laneId;

    private DocumentType documentType;
    private Status status = Status.RECEIVED;

    /** GridFS ids for the stored evidence images. */
    private String documentImageId;
    private String liveCaptureImageId;

    private ExtractedFields extracted;
    private List<ModuleResult> moduleResults = new ArrayList<>();
    private RiskAssessment risk;

    /** Set when an officer overrides the recommendation. */
    private Verdict officerDecision;
    private String officerNotes;
    private Instant decidedAt;

    /** Normalised document number, used for duplicate-identity and watchlist lookups. */
    @Indexed
    private String documentNumberKey;

    /** Normalised SURNAME|GIVENNAMES|YYYY-MM-DD, used to spot one person using several documents. */
    @Indexed
    private String identityKey;

    private Instant createdAt = Instant.now();
    private Instant completedAt;
    private long processingMillis;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getCaseReference() { return caseReference; }
    public void setCaseReference(String caseReference) { this.caseReference = caseReference; }

    public String getCheckpointId() { return checkpointId; }
    public void setCheckpointId(String checkpointId) { this.checkpointId = checkpointId; }

    public String getOfficerId() { return officerId; }
    public void setOfficerId(String officerId) { this.officerId = officerId; }

    public String getLaneId() { return laneId; }
    public void setLaneId(String laneId) { this.laneId = laneId; }

    public DocumentType getDocumentType() { return documentType; }
    public void setDocumentType(DocumentType documentType) { this.documentType = documentType; }

    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }

    public String getDocumentImageId() { return documentImageId; }
    public void setDocumentImageId(String documentImageId) { this.documentImageId = documentImageId; }

    public String getLiveCaptureImageId() { return liveCaptureImageId; }
    public void setLiveCaptureImageId(String liveCaptureImageId) { this.liveCaptureImageId = liveCaptureImageId; }

    public ExtractedFields getExtracted() { return extracted; }
    public void setExtracted(ExtractedFields extracted) { this.extracted = extracted; }

    public List<ModuleResult> getModuleResults() { return moduleResults; }
    public void setModuleResults(List<ModuleResult> moduleResults) { this.moduleResults = moduleResults; }

    public RiskAssessment getRisk() { return risk; }
    public void setRisk(RiskAssessment risk) { this.risk = risk; }

    public Verdict getOfficerDecision() { return officerDecision; }
    public void setOfficerDecision(Verdict officerDecision) { this.officerDecision = officerDecision; }

    public String getOfficerNotes() { return officerNotes; }
    public void setOfficerNotes(String officerNotes) { this.officerNotes = officerNotes; }

    public Instant getDecidedAt() { return decidedAt; }
    public void setDecidedAt(Instant decidedAt) { this.decidedAt = decidedAt; }

    public String getDocumentNumberKey() { return documentNumberKey; }
    public void setDocumentNumberKey(String documentNumberKey) { this.documentNumberKey = documentNumberKey; }

    public String getIdentityKey() { return identityKey; }
    public void setIdentityKey(String identityKey) { this.identityKey = identityKey; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getCompletedAt() { return completedAt; }
    public void setCompletedAt(Instant completedAt) { this.completedAt = completedAt; }

    public long getProcessingMillis() { return processingMillis; }
    public void setProcessingMillis(long processingMillis) { this.processingMillis = processingMillis; }
}
