package com.govid.screening.domain;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.Map;

/**
 * Append-only investigation trail. Every screening, every officer override and every
 * watchlist change lands here so a case can be reconstructed after the fact.
 */
@Document(collection = "audit_events")
public class AuditEvent {

    @Id
    private String id;

    @Indexed
    private String caseId;

    private String actor;
    private String action;
    private String detail;
    private Map<String, Object> data;

    @Indexed
    private Instant occurredAt = Instant.now();

    public AuditEvent() {
    }

    public AuditEvent(String caseId, String actor, String action, String detail, Map<String, Object> data) {
        this.caseId = caseId;
        this.actor = actor;
        this.action = action;
        this.detail = detail;
        this.data = data;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getCaseId() { return caseId; }
    public void setCaseId(String caseId) { this.caseId = caseId; }

    public String getActor() { return actor; }
    public void setActor(String actor) { this.actor = actor; }

    public String getAction() { return action; }
    public void setAction(String action) { this.action = action; }

    public String getDetail() { return detail; }
    public void setDetail(String detail) { this.detail = detail; }

    public Map<String, Object> getData() { return data; }
    public void setData(Map<String, Object> data) { this.data = data; }

    public Instant getOccurredAt() { return occurredAt; }
    public void setOccurredAt(Instant occurredAt) { this.occurredAt = occurredAt; }
}
