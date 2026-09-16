package com.govid.screening.tampering;

import com.govid.screening.domain.ModuleResult;
import com.govid.screening.domain.RiskFlag;
import com.govid.screening.domain.ScreeningModule;
import com.govid.screening.domain.Severity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Module 3 - Tampering Detection.
 *
 * <p>Runs every forensic detector over the same image and collects their findings. The
 * techniques are intentionally uncorrelated - compression history, metadata provenance,
 * sensor noise and pixel duplication each fail differently - so a document that trips
 * several of them at once is far more likely to be genuinely altered than one that trips
 * a single detector.
 *
 * <p>A detector that cannot run on a given image contributes nothing rather than
 * blocking the pipeline, and its absence is recorded in the module details so the officer
 * can see which techniques actually applied.
 */
@Service
public class TamperingService {

    private static final Logger log = LoggerFactory.getLogger(TamperingService.class);

    /** Distinct techniques that must agree before corroboration is claimed. */
    private static final int MIN_CORROBORATING_DETECTORS = 3;

    private final List<TamperingDetector> detectors;

    public TamperingService(List<TamperingDetector> detectors) {
        this.detectors = detectors;
    }

    public ModuleResult analyse(byte[] imageBytes, String contentType) {
        long start = System.nanoTime();

        if (imageBytes == null || imageBytes.length == 0) {
            return ModuleResult.skipped(ScreeningModule.TAMPERING_DETECTION,
                    "No document image was supplied.");
        }

        BufferedImage image = decode(imageBytes);
        List<RiskFlag> flags = new ArrayList<>();
        Map<String, Object> details = new LinkedHashMap<>();

        if (image == null) {
            flags.add(RiskFlag.of("IMAGE_UNDECODABLE", ScreeningModule.TAMPERING_DETECTION,
                    Severity.MEDIUM,
                    "The uploaded file could not be decoded as an image, so pixel-level "
                            + "forensics could not be applied.",
                    Map.of("contentType", String.valueOf(contentType),
                            "bytes", imageBytes.length)));
        } else {
            details.put("widthPixels", image.getWidth());
            details.put("heightPixels", image.getHeight());
        }

        TamperingDetector.ImageEvidence evidence =
                new TamperingDetector.ImageEvidence(imageBytes, contentType, image);

        List<String> ran = new ArrayList<>();
        List<String> silent = new ArrayList<>();
        List<String> corroborating = new ArrayList<>();
        for (TamperingDetector detector : detectors) {
            try {
                List<RiskFlag> found = detector.analyse(evidence);
                ran.add(detector.name());
                if (found.isEmpty()) {
                    silent.add(detector.name());
                } else {
                    flags.addAll(found);
                    // Corroboration is counted per detector, not per finding. Two findings
                    // from one technique are one technique's opinion twice over, and
                    // treating them as independent would manufacture agreement that the
                    // evidence does not support.
                    boolean substantive = found.stream()
                            .anyMatch(flag -> flag.severity().weight() >= Severity.MEDIUM.weight());
                    if (substantive) {
                        corroborating.add(detector.name());
                    }
                }
            } catch (RuntimeException e) {
                log.warn("Tampering detector {} failed", detector.name(), e);
            }
        }

        details.put("detectorsRun", ran);
        details.put("detectorsWithNoFinding", silent);
        details.put("corroboratingDetectors", corroborating);
        details.put("contentType", contentType);

        // Techniques that fail differently agreeing with each other is a much stronger
        // signal than any one of them firing alone, so it is a finding in its own right.
        if (corroborating.size() >= MIN_CORROBORATING_DETECTORS) {
            flags.add(RiskFlag.of("TAMPERING_CORROBORATED", ScreeningModule.TAMPERING_DETECTION,
                    Severity.HIGH,
                    corroborating.size() + " independent forensic techniques each found evidence "
                            + "of alteration on this image.",
                    Map.of("detectors", List.copyOf(corroborating))));
        }

        return new ModuleResult(ScreeningModule.TAMPERING_DETECTION, ModuleResult.Status.COMPLETED,
                elapsed(start), flags, details,
                flags.isEmpty() ? "No tampering indicators found" : flags.size() + " indicator(s)");
    }

    private static BufferedImage decode(byte[] bytes) {
        try {
            return ImageIO.read(new ByteArrayInputStream(bytes));
        } catch (Exception e) {
            log.debug("Image could not be decoded", e);
            return null;
        }
    }

    private static long elapsed(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }
}
