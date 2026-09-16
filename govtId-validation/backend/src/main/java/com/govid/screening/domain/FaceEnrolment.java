package com.govid.screening.domain;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.List;

/**
 * A reference face registered against a known traveller.
 *
 * <p>This is what makes a repeat crossing fast: the second time someone presents the same
 * passport, their face is already on file and the comparison does not have to start from
 * the printed portrait, which is the weakest image in the whole pipeline.
 *
 * <p>The embedding is stored L2-normalised so identification is a dot product. It is not
 * the photograph - an embedding cannot be turned back into a face - but it is still
 * biometric data about an identifiable person, so it carries who enrolled it and when, and
 * it is deactivated rather than deleted when it is withdrawn.
 */
@Document(collection = "face_enrolments")
public class FaceEnrolment {

    @Id
    private String id;

    /** Stable identifier for the person. Several enrolments may share one. */
    @Indexed
    private String subjectId;

    private String displayName;

    /** Normalised document number, when the enrolment was taken from a document. */
    @Indexed
    private String documentNumberKey;

    private String nationality;

    /** L2-normalised face embedding. */
    private List<Double> embedding;

    private int dimension;

    /** Quality score of the image this embedding came from, in [0, 1]. */
    private double qualityScore;

    /** Whether the reference image passed the quality gate, or was enrolled over it. */
    private boolean qualityAccepted = true;

    private String engine;

    private boolean active = true;

    private Instant enrolledAt = Instant.now();
    private String enrolledBy;
    private String notes;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getSubjectId() { return subjectId; }
    public void setSubjectId(String subjectId) { this.subjectId = subjectId; }

    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }

    public String getDocumentNumberKey() { return documentNumberKey; }
    public void setDocumentNumberKey(String documentNumberKey) {
        this.documentNumberKey = documentNumberKey;
    }

    public String getNationality() { return nationality; }
    public void setNationality(String nationality) { this.nationality = nationality; }

    public List<Double> getEmbedding() { return embedding; }
    public void setEmbedding(List<Double> embedding) { this.embedding = embedding; }

    public int getDimension() { return dimension; }
    public void setDimension(int dimension) { this.dimension = dimension; }

    public double getQualityScore() { return qualityScore; }
    public void setQualityScore(double qualityScore) { this.qualityScore = qualityScore; }

    public boolean isQualityAccepted() { return qualityAccepted; }
    public void setQualityAccepted(boolean qualityAccepted) {
        this.qualityAccepted = qualityAccepted;
    }

    public String getEngine() { return engine; }
    public void setEngine(String engine) { this.engine = engine; }

    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }

    public Instant getEnrolledAt() { return enrolledAt; }
    public void setEnrolledAt(Instant enrolledAt) { this.enrolledAt = enrolledAt; }

    public String getEnrolledBy() { return enrolledBy; }
    public void setEnrolledBy(String enrolledBy) { this.enrolledBy = enrolledBy; }

    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }
}
