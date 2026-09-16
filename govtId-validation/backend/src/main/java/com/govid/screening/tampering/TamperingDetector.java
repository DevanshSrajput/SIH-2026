package com.govid.screening.tampering;

import com.govid.screening.domain.RiskFlag;

import java.awt.image.BufferedImage;
import java.util.List;

/**
 * One forensic technique applied to the document image in Module 3.
 *
 * <p>Detectors are deliberately independent and each looks for a different physical
 * consequence of tampering. No single technique is conclusive - each has a false-positive
 * mode - so the risk engine weighs their findings together rather than trusting any one.
 */
public interface TamperingDetector {

    String name();

    /**
     * @return findings, or an empty list when this technique saw nothing. A detector that
     *         cannot run on the given image (wrong format, too small) returns empty
     *         rather than throwing.
     */
    List<RiskFlag> analyse(ImageEvidence evidence);

    /**
     * The document image in the forms the detectors need.
     *
     * @param bytes       the file exactly as uploaded, needed for metadata and for
     *                    re-compression analysis
     * @param contentType MIME type as received
     * @param image       decoded pixels, or {@code null} if the bytes could not be decoded
     */
    record ImageEvidence(byte[] bytes, String contentType, BufferedImage image) {

        public boolean isJpeg() {
            return contentType != null && contentType.toLowerCase().contains("jpeg")
                    || contentType != null && contentType.toLowerCase().contains("jpg");
        }

        public boolean hasPixels() {
            return image != null && image.getWidth() > 0 && image.getHeight() > 0;
        }
    }
}
