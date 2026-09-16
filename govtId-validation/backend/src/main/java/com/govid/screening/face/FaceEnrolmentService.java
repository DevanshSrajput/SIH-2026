package com.govid.screening.face;

import com.govid.screening.domain.AuditEvent;
import com.govid.screening.domain.FaceEnrolment;
import com.govid.screening.repository.AuditEventRepository;
import com.govid.screening.repository.FaceEnrolmentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Registers reference faces for known travellers and identifies a capture against them.
 *
 * <h2>Why 1:N is not 1:1 run repeatedly</h2>
 *
 * <p>Verification asks "is this the person on this document?" and gets one comparison to
 * answer it. Identification asks "who is this?" and compares against everyone enrolled, so
 * every extra subject is another chance for a coincidental high score. Run a threshold
 * calibrated for 1:1 against a gallery of ten thousand and it will name someone eventually.
 *
 * <p>Two rules follow. Identification uses a stricter threshold than verification, and the
 * best candidate must also beat the runner-up by a clear margin. A gallery that returns two
 * near-equal candidates has not identified anybody - it has found a pair of lookalikes, and
 * the only correct output is to say so.
 */
@Service
public class FaceEnrolmentService {

    private static final Logger log = LoggerFactory.getLogger(FaceEnrolmentService.class);

    private final FaceEnrolmentRepository repository;
    private final FaceEmbeddingClient embeddingClient;
    private final AuditEventRepository auditRepository;
    private final Clock clock;

    private final double identifyThreshold;
    private final double identifyMargin;

    public FaceEnrolmentService(
            FaceEnrolmentRepository repository,
            FaceEmbeddingClient embeddingClient,
            AuditEventRepository auditRepository,
            Clock clock,
            @Value("${screening.face.identify-threshold:0.52}") double identifyThreshold,
            @Value("${screening.face.identify-margin:0.06}") double identifyMargin) {
        this.repository = repository;
        this.embeddingClient = embeddingClient;
        this.auditRepository = auditRepository;
        this.clock = clock;
        this.identifyThreshold = identifyThreshold;
        this.identifyMargin = identifyMargin;
    }

    public boolean isAvailable() {
        return embeddingClient.isAvailable();
    }

    // ------------------------------------------------------------------
    // Enrolment
    // ------------------------------------------------------------------

    /** What the caller asked to enrol. */
    public record EnrolmentRequest(
            byte[] image,
            String source,
            String subjectId,
            String displayName,
            String documentNumber,
            String nationality,
            String enrolledBy,
            String notes,
            boolean acceptPoorQuality) {
    }

    /**
     * Registers one reference face.
     *
     * <p>The quality gate applies here at least as hard as it does at comparison time. A
     * bad enrolment is not a one-off wrong answer - it is a permanent source of wrong
     * answers for every future comparison against that person, and nothing downstream can
     * tell that the reference was the problem. Overriding it has to be a deliberate act,
     * and it is recorded as one.
     */
    public FaceEnrolment enrol(EnrolmentRequest request) {
        if (request.subjectId() == null || request.subjectId().isBlank()) {
            throw new IllegalArgumentException("A subject identifier is required to enrol a face.");
        }
        if (request.image() == null || request.image().length == 0) {
            throw new IllegalArgumentException("A reference image is required.");
        }

        FaceEmbeddingClient.Embedding embedding =
                embeddingClient.embed(request.image(), request.source());

        if (embedding.facesDetected() > 1) {
            throw new IllegalArgumentException(
                    "The reference image contains " + embedding.facesDetected()
                            + " faces. Enrol from an image of one person.");
        }

        if (!embedding.qualityUsable() && !request.acceptPoorQuality()) {
            String issue = embedding.qualityIssues().isEmpty()
                    ? "it is below the quality gate"
                    : embedding.qualityIssues().get(0);
            throw new IllegalArgumentException(
                    "The reference image is not good enough to enrol: " + issue + ". "
                            + (embedding.qualityAdvice() == null ? "" : embedding.qualityAdvice())
                            + " Re-capture, or resubmit with acceptPoorQuality=true to enrol it "
                            + "anyway.");
        }

        FaceEnrolment enrolment = new FaceEnrolment();
        enrolment.setSubjectId(request.subjectId().trim());
        enrolment.setDisplayName(request.displayName());
        enrolment.setDocumentNumberKey(normaliseDocumentNumber(request.documentNumber()));
        enrolment.setNationality(request.nationality());
        enrolment.setEmbedding(embedding.embedding());
        enrolment.setDimension(embedding.dimension());
        enrolment.setQualityScore(embedding.qualityScore());
        enrolment.setQualityAccepted(embedding.qualityUsable());
        enrolment.setEngine(embedding.engine());
        enrolment.setEnrolledBy(request.enrolledBy());
        enrolment.setNotes(request.notes());
        enrolment.setEnrolledAt(Instant.now(clock));

        FaceEnrolment saved = repository.save(enrolment);

        Map<String, Object> data = new HashMap<>();
        data.put("faceEnrolmentId", saved.getId());
        data.put("subjectId", saved.getSubjectId());
        data.put("qualityScore", saved.getQualityScore());
        data.put("qualityOverridden", !embedding.qualityUsable());
        auditRepository.save(new AuditEvent(null, request.enrolledBy(), "FACE_ENROLLED",
                "Enrolled a reference face for " + saved.getSubjectId(), data));

        log.info("Enrolled face {} for subject {} (quality {})",
                saved.getId(), saved.getSubjectId(), String.format("%.2f", saved.getQualityScore()));
        return saved;
    }

    /** Withdraws an enrolment. Kept as a record, like everything else that was evidence. */
    public FaceEnrolment deactivate(String id, String actor) {
        FaceEnrolment enrolment = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Unknown face enrolment " + id));
        enrolment.setActive(false);
        FaceEnrolment saved = repository.save(enrolment);

        auditRepository.save(new AuditEvent(null, actor, "FACE_ENROLMENT_WITHDRAWN",
                "Withdrew the reference face for " + enrolment.getSubjectId(),
                Map.of("faceEnrolmentId", id, "subjectId",
                        String.valueOf(enrolment.getSubjectId()))));
        return saved;
    }

    // ------------------------------------------------------------------
    // Identification
    // ------------------------------------------------------------------

    /** One scored candidate from the gallery. */
    public record Candidate(
            String enrolmentId,
            String subjectId,
            String displayName,
            String nationality,
            String documentNumberKey,
            double similarity) {
    }

    /**
     * The outcome of an identification run.
     *
     * @param decision one of IDENTIFIED, NO_MATCH, AMBIGUOUS, UNCERTAIN, EMPTY_GALLERY
     */
    public record IdentificationResult(
            boolean identified,
            String decision,
            String subjectId,
            String displayName,
            String reason,
            List<Candidate> candidates,
            int gallerySize,
            double threshold,
            double requiredMargin) {
    }

    public IdentificationResult identify(byte[] image, int limit) {
        FaceEmbeddingClient.Embedding probe = embeddingClient.embed(image, "live");

        if (probe.facesDetected() > 1) {
            return new IdentificationResult(false, "UNCERTAIN", null, null,
                    probe.facesDetected() + " faces are in frame, so it is not certain which "
                            + "person would be identified. Re-capture with one person in view.",
                    List.of(), (int) repository.countByActiveTrue(),
                    identifyThreshold, identifyMargin);
        }

        if (!probe.qualityUsable()) {
            String issue = probe.qualityIssues().isEmpty()
                    ? "the image is below the quality gate"
                    : probe.qualityIssues().get(0);
            return new IdentificationResult(false, "UNCERTAIN", null, null,
                    "No identification was attempted: " + issue + ". "
                            + (probe.qualityAdvice() == null ? "" : probe.qualityAdvice()),
                    List.of(), (int) repository.countByActiveTrue(),
                    identifyThreshold, identifyMargin);
        }

        List<FaceEnrolment> gallery = repository.findByActiveTrue();
        if (gallery.isEmpty()) {
            return new IdentificationResult(false, "EMPTY_GALLERY", null, null,
                    "No faces are enrolled to compare against.",
                    List.of(), 0, identifyThreshold, identifyMargin);
        }

        double[] vector = toArray(probe.embedding());
        List<Candidate> ranked = new ArrayList<>();
        for (FaceEnrolment enrolment : gallery) {
            if (enrolment.getEmbedding() == null
                    || enrolment.getEmbedding().size() != vector.length) {
                // A gallery entry from a different model generation cannot be compared.
                continue;
            }
            double similarity = cosine(vector, toArray(enrolment.getEmbedding()));
            ranked.add(new Candidate(
                    enrolment.getId(),
                    enrolment.getSubjectId(),
                    enrolment.getDisplayName(),
                    enrolment.getNationality(),
                    enrolment.getDocumentNumberKey(),
                    similarity));
        }

        ranked.sort(Comparator.comparingDouble(Candidate::similarity).reversed());
        List<Candidate> top = ranked.subList(0, Math.min(Math.max(limit, 1), ranked.size()));

        if (ranked.isEmpty()) {
            return new IdentificationResult(false, "EMPTY_GALLERY", null, null,
                    "No comparable enrolments: every stored embedding was produced by a "
                            + "different model generation. Re-enrol from the reference images.",
                    List.of(), gallery.size(), identifyThreshold, identifyMargin);
        }

        Candidate best = ranked.get(0);

        if (best.similarity() < identifyThreshold) {
            return new IdentificationResult(false, "NO_MATCH", null, null,
                    "The closest enrolled face scores %.3f, below the %.2f identification "
                            .formatted(best.similarity(), identifyThreshold)
                            + "threshold. This person does not appear to be enrolled.",
                    top, gallery.size(), identifyThreshold, identifyMargin);
        }

        // The runner-up has to be a *different* person to matter. Several enrolments of
        // the same traveller scoring alike is confirmation, not ambiguity.
        Candidate rival = ranked.stream()
                .filter(c -> !c.subjectId().equals(best.subjectId()))
                .findFirst()
                .orElse(null);

        if (rival != null && best.similarity() - rival.similarity() < identifyMargin) {
            return new IdentificationResult(false, "AMBIGUOUS", null, null,
                    "Two enrolled people score within %.3f of each other (%s at %.3f and %s at "
                            .formatted(best.similarity() - rival.similarity(),
                                    label(best), best.similarity(), label(rival))
                            + "%.3f), which is closer than the %.2f margin needed to name one of "
                                    .formatted(rival.similarity(), identifyMargin)
                            + "them. An officer must resolve this.",
                    top, gallery.size(), identifyThreshold, identifyMargin);
        }

        return new IdentificationResult(true, "IDENTIFIED", best.subjectId(), best.displayName(),
                "%s scores %.3f, clear of both the %.2f threshold and the runner-up."
                        .formatted(label(best), best.similarity(), identifyThreshold),
                top, gallery.size(), identifyThreshold, identifyMargin);
    }

    private static String label(Candidate candidate) {
        return candidate.displayName() == null || candidate.displayName().isBlank()
                ? candidate.subjectId() : candidate.displayName();
    }

    private static double[] toArray(List<Double> values) {
        double[] array = new double[values.size()];
        for (int i = 0; i < array.length; i++) {
            array[i] = values.get(i);
        }
        return array;
    }

    /** Both vectors are stored L2-normalised, so the dot product is the cosine. */
    private static double cosine(double[] a, double[] b) {
        double dot = 0.0;
        double normA = 0.0;
        double normB = 0.0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        if (normA == 0 || normB == 0) {
            return 0.0;
        }
        return Math.max(-1.0, Math.min(1.0, dot / (Math.sqrt(normA) * Math.sqrt(normB))));
    }

    private static String normaliseDocumentNumber(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String normalised = raw.toUpperCase().replaceAll("[^A-Z0-9]", "");
        return normalised.isEmpty() ? null : normalised;
    }
}
