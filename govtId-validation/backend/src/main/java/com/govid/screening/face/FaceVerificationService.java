package com.govid.screening.face;

import com.govid.screening.domain.ModuleResult;
import com.govid.screening.domain.RiskFlag;
import com.govid.screening.domain.ScreeningModule;
import com.govid.screening.domain.Severity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Module 4 - Face Verification.
 *
 * <p>Answers one question: is the person standing at the desk the person the document was
 * issued to? This is the check that catches identity impersonation, where the document
 * itself is entirely genuine and the traveller is not its owner.
 *
 * <h2>Why there are three answers and not two</h2>
 *
 * <p>Above {@code match-threshold} the faces are accepted; below {@code mismatch-threshold}
 * they are treated as different people; in between the case goes to an officer instead of
 * being decided. Biometric comparison is probabilistic, and the honest response to an
 * ambiguous score is to say so.
 *
 * <p>But the score is not the only thing that can make a comparison untrustworthy. A
 * blurred capture, a face turned away from the camera, a portrait photographed off a phone
 * screen, two people in frame - each of these produces a number that looks exactly like a
 * good measurement and is not one. So a positive identification additionally requires that
 * nothing about the comparison undermines it. Anything else is reported as inconclusive
 * with the reason attached, because an officer who is told "uncertain, the capture is
 * blurred" can fix it, and one who is told "82% match" cannot.
 *
 * <p>The asymmetry is deliberate. A false accept at a checkpoint lets an impostor through
 * on someone else's passport. A false uncertain costs an officer a few seconds. Those are
 * not the same mistake and the thresholds are not set as though they were.
 */
@Service
public class FaceVerificationService {

    private static final Logger log = LoggerFactory.getLogger(FaceVerificationService.class);

    private final List<FaceVerifier> verifiers;
    private final double matchThreshold;
    private final double mismatchThreshold;
    private final double uncertainMargin;

    public FaceVerificationService(
            List<FaceVerifier> verifiers,
            @Value("${screening.face.match-threshold:0.46}") double matchThreshold,
            @Value("${screening.face.mismatch-threshold:0.28}") double mismatchThreshold,
            @Value("${screening.face.uncertain-margin:0.03}") double uncertainMargin) {
        this.verifiers = verifiers;
        this.matchThreshold = matchThreshold;
        this.mismatchThreshold = mismatchThreshold;
        this.uncertainMargin = uncertainMargin;
    }

    public ModuleResult verify(byte[] documentImage, byte[] liveCapture) {
        long start = System.nanoTime();

        if (liveCapture == null || liveCapture.length == 0) {
            return ModuleResult.skipped(ScreeningModule.FACE_VERIFICATION,
                    "No live capture was supplied, so the document portrait could not be "
                            + "compared against the traveller.");
        }

        Optional<FaceVerifier> selected = verifiers.stream()
                .filter(FaceVerifier::isAvailable)
                .findFirst();

        if (selected.isEmpty()) {
            return ModuleResult.skipped(ScreeningModule.FACE_VERIFICATION,
                    "No face matcher is configured. Set screening.face.service-url to enable "
                            + "Module 4. No similarity score is estimated without one.");
        }

        FaceVerifier verifier = selected.get();
        FaceVerifier.FaceMatchResult match;
        try {
            match = verifier.compare(documentImage, liveCapture);
        } catch (Exception e) {
            log.warn("Face verifier {} failed", verifier.name(), e);
            return ModuleResult.failed(ScreeningModule.FACE_VERIFICATION, elapsed(start),
                    "Face matcher " + verifier.name() + " failed: " + e.getMessage());
        }

        List<RiskFlag> flags = new ArrayList<>();
        Map<String, Object> details = new LinkedHashMap<>(match.details());
        details.put("engine", match.engine());
        details.put("similarity", match.similarity());
        details.put("matchThreshold", matchThreshold);
        details.put("mismatchThreshold", mismatchThreshold);

        if (!match.documentFaceFound()) {
            flags.add(RiskFlag.of("FACE_NOT_FOUND_ON_DOCUMENT", ScreeningModule.FACE_VERIFICATION,
                    Severity.HIGH,
                    "No portrait could be located on the document image. On a document that "
                            + "should carry one, this points to a removed or destroyed photograph.",
                    Map.of("engine", match.engine())));
        }
        if (!match.liveFaceFound()) {
            flags.add(RiskFlag.of("FACE_NOT_FOUND_IN_CAPTURE", ScreeningModule.FACE_VERIFICATION,
                    Severity.MEDIUM,
                    "No face could be located in the live capture. Re-capture the traveller.",
                    Map.of("engine", match.engine())));
        }

        if (match.documentFaceFound() && match.liveFaceFound()) {
            FaceDecision decision = resolveDecision(match);
            details.put("decision", decision.name());
            if (match.hasConfidence()) {
                details.put("confidence", match.confidence());
            }
            if (!match.reasons().isEmpty()) {
                details.put("reasons", match.reasons());
            }
            if (!match.blockers().isEmpty()) {
                details.put("blockers", match.blockers());
            }
            if (match.summary() != null) {
                details.put("summary", match.summary());
            }
            if (match.recaptureAdvice() != null) {
                details.put("recaptureAdvice", match.recaptureAdvice());
            }

            flags.addAll(flagsFor(decision, match));
        }

        return new ModuleResult(ScreeningModule.FACE_VERIFICATION, ModuleResult.Status.COMPLETED,
                elapsed(start), flags, details,
                "Compared by " + verifier.name());
    }

    /**
     * Takes the matcher's verdict when it has one, and falls back to the configured
     * thresholds when it does not.
     *
     * <p>A matcher that assessed quality and liveness for itself knows things this service
     * cannot reconstruct from a single number, so its verdict is not second-guessed. The
     * one thing this service will do is refuse to upgrade: a matcher that says UNCERTAIN
     * is never turned into a MATCH by a threshold comparison here.
     */
    private FaceDecision resolveDecision(FaceVerifier.FaceMatchResult match) {
        FaceDecision decision = match.decision() != null
                ? match.decision()
                : fromScore(match.similarity());

        // A matcher that could not landmark-align the crops is reporting a score from a
        // rougher measurement than the thresholds were calibrated on. Unaligned crops
        // shift every embedding in the same direction, which pulls unrelated faces
        // together - so such a score may support a doubt, but never an identification.
        if (decision == FaceDecision.MATCH
                && Boolean.FALSE.equals(match.details().get("alignmentUsed"))) {
            log.debug("Downgrading MATCH to UNCERTAIN: {} could not align the face crops",
                    match.engine());
            return FaceDecision.UNCERTAIN;
        }
        return decision;
    }

    /**
     * The three-band rule applied to a bare score.
     *
     * <p>A score within {@code uncertain-margin} of either threshold is reported as
     * inconclusive even though it technically falls on the decisive side. The thresholds
     * are estimates from a finite sample, not physical constants, and treating a value
     * that sits on top of one as though it were a clear result overstates what the
     * measurement supports.
     */
    private FaceDecision fromScore(double similarity) {
        if (similarity >= matchThreshold) {
            return similarity - matchThreshold < uncertainMargin
                    ? FaceDecision.UNCERTAIN : FaceDecision.MATCH;
        }
        if (similarity < mismatchThreshold) {
            return mismatchThreshold - similarity < uncertainMargin
                    ? FaceDecision.UNCERTAIN : FaceDecision.NO_MATCH;
        }
        return FaceDecision.UNCERTAIN;
    }

    private List<RiskFlag> flagsFor(FaceDecision decision, FaceVerifier.FaceMatchResult match) {
        List<RiskFlag> flags = new ArrayList<>();
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("similarity", match.similarity());
        evidence.put("engine", match.engine());
        if (match.hasConfidence()) {
            evidence.put("confidence", match.confidence());
        }
        if (!match.blockers().isEmpty()) {
            evidence.put("blockers", match.blockers());
        }

        switch (decision) {
            case NO_MATCH -> {
                Map<String, Object> data = new LinkedHashMap<>(evidence);
                data.put("threshold", mismatchThreshold);
                flags.add(RiskFlag.of("FACE_MISMATCH", ScreeningModule.FACE_VERIFICATION,
                        Severity.CRITICAL,
                        match.summary() != null ? match.summary()
                                : "The traveller does not match the portrait on the document "
                                + "(similarity %.2f, below the %.2f mismatch threshold)."
                                        .formatted(match.similarity(), mismatchThreshold),
                        data));
            }
            case UNCERTAIN -> {
                String explanation = match.summary() != null
                        ? match.summary()
                        : "The face comparison is inconclusive (similarity %.2f, between the "
                        + "%.2f and %.2f thresholds). An officer should compare visually."
                                .formatted(match.similarity(), mismatchThreshold, matchThreshold);
                if (match.recaptureAdvice() != null) {
                    explanation = explanation + " " + match.recaptureAdvice();
                }
                flags.add(RiskFlag.of("FACE_INCONCLUSIVE", ScreeningModule.FACE_VERIFICATION,
                        Severity.MEDIUM, explanation, evidence));
            }
            case MATCH -> {
                // Nothing to flag. The absence of a finding is the finding.
            }
        }

        // Quality and spoofing are reported in their own right, not folded into the
        // similarity verdict: "the capture was a photograph of a screen" is an
        // intelligence signal about the presentation, separate from whether the two
        // faces resemble each other.
        qualityFlag(match).ifPresent(flags::add);
        livenessFlag(match).ifPresent(flags::add);

        return flags;
    }

    private Optional<RiskFlag> qualityFlag(FaceVerifier.FaceMatchResult match) {
        String unusable = unusableQualitySide(match.details());
        if (unusable == null) {
            return Optional.empty();
        }
        return Optional.of(RiskFlag.of("FACE_QUALITY_INSUFFICIENT",
                ScreeningModule.FACE_VERIFICATION, Severity.LOW,
                "The " + unusable + " image is not good enough to compare faces reliably. "
                        + "The comparison result should not be relied on without a re-capture.",
                Map.of("engine", match.engine(), "side", unusable)));
    }

    /** Which side of the comparison failed the matcher's quality gate, if either did. */
    @SuppressWarnings("unchecked")
    private static String unusableQualitySide(Map<String, Object> details) {
        Object quality = details.get("quality");
        if (!(quality instanceof Map<?, ?> byside)) {
            return null;
        }
        boolean documentBad = isUnusable(byside.get("document"));
        boolean liveBad = isUnusable(byside.get("live"));
        if (documentBad && liveBad) {
            return "document and live capture";
        }
        if (documentBad) {
            return "document";
        }
        return liveBad ? "live capture" : null;
    }

    private static boolean isUnusable(Object side) {
        return side instanceof Map<?, ?> map && Boolean.FALSE.equals(map.get("usable"));
    }

    private Optional<RiskFlag> livenessFlag(FaceVerifier.FaceMatchResult match) {
        Object live = match.details().get("livenessLive");
        if (!Boolean.FALSE.equals(live)) {
            return Optional.empty();
        }
        Object score = match.details().get("livenessScore");
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("engine", match.engine());
        if (score != null) {
            data.put("livenessScore", score);
        }
        return Optional.of(RiskFlag.of("FACE_LIVENESS_SUSPECT",
                ScreeningModule.FACE_VERIFICATION, Severity.HIGH,
                "The live capture did not pass the anti-spoofing check. It may be a photograph "
                        + "of a photograph, or a face held up on a phone screen, rather than the "
                        + "traveller standing at the desk.",
                data));
    }

    private static long elapsed(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }

    public double matchThreshold() {
        return matchThreshold;
    }

    public double mismatchThreshold() {
        return mismatchThreshold;
    }
}
