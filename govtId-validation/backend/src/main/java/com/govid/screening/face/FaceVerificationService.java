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
 * <p>Two thresholds rather than one. Above {@code match-threshold} the faces are accepted;
 * below {@code mismatch-threshold} they are treated as different people; in between the
 * case is referred to an officer instead of being decided. Biometric comparison is
 * probabilistic, and the honest response to an ambiguous score is to say so.
 */
@Service
public class FaceVerificationService {

    private static final Logger log = LoggerFactory.getLogger(FaceVerificationService.class);

    private final List<FaceVerifier> verifiers;
    private final double matchThreshold;
    private final double mismatchThreshold;

    public FaceVerificationService(
            List<FaceVerifier> verifiers,
            @Value("${screening.face.match-threshold:0.75}") double matchThreshold,
            @Value("${screening.face.mismatch-threshold:0.55}") double mismatchThreshold) {
        this.verifiers = verifiers;
        this.matchThreshold = matchThreshold;
        this.mismatchThreshold = mismatchThreshold;
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
            if (match.similarity() < mismatchThreshold) {
                flags.add(RiskFlag.of("FACE_MISMATCH", ScreeningModule.FACE_VERIFICATION,
                        Severity.CRITICAL,
                        "The traveller does not match the portrait on the document "
                                + "(similarity %.2f, below the %.2f mismatch threshold)."
                                        .formatted(match.similarity(), mismatchThreshold),
                        Map.of("similarity", match.similarity(),
                                "threshold", mismatchThreshold,
                                "engine", match.engine())));
            } else if (match.similarity() < matchThreshold) {
                flags.add(RiskFlag.of("FACE_INCONCLUSIVE", ScreeningModule.FACE_VERIFICATION,
                        Severity.MEDIUM,
                        "The face comparison is inconclusive (similarity %.2f, between the "
                                + "%.2f and %.2f thresholds). An officer should compare visually."
                                        .formatted(match.similarity(), mismatchThreshold, matchThreshold),
                        Map.of("similarity", match.similarity(), "engine", match.engine())));
            }
        }

        return new ModuleResult(ScreeningModule.FACE_VERIFICATION, ModuleResult.Status.COMPLETED,
                elapsed(start), flags, details,
                "Compared by " + verifier.name());
    }

    private static long elapsed(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }
}
