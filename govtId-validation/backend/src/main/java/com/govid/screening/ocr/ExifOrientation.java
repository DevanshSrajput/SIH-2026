package com.govid.screening.ocr;

import com.drew.imaging.ImageMetadataReader;
import com.drew.metadata.Metadata;
import com.drew.metadata.exif.ExifIFD0Directory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

/**
 * Applies a photograph's EXIF orientation before it is read.
 *
 * <p>A phone writes the sensor image in its native orientation and records how it should
 * be turned for display in EXIF tag 0x0112. Browsers and photo viewers honour that tag,
 * so the officer who uploads a document sees it the right way up. Neither {@code ImageIO}
 * nor Tesseract honours it, so without this step the pipeline reads a sideways page: not
 * a poor read, but pure noise, because the text runs across the scan lines rather than
 * along them. Every field then reports as missing, and Module 2 raises that as risk
 * against a document nothing was ever actually wrong with.
 *
 * <p>This runs on the copy handed to the OCR engines only. The stored evidence image is
 * never rewritten: it is what an audit re-examines months later, and error level analysis
 * in Module 3 reads the original JPEG compression artefacts, which re-encoding destroys.
 */
final class ExifOrientation {

    private static final Logger log = LoggerFactory.getLogger(ExifOrientation.class);

    /**
     * @param image       bytes to read, upright if this class could make them so
     * @param orientation the EXIF value found, or 1 when absent or unreadable
     * @param applied     whether the bytes differ from the ones passed in
     */
    record Result(byte[] image, int orientation, boolean applied) {
    }

    private ExifOrientation() {
    }

    /** Returns the image turned the way its EXIF says it should be viewed. */
    static Result upright(byte[] image) {
        if (image == null || image.length == 0) {
            return new Result(image, 1, false);
        }

        int orientation;
        try {
            Metadata metadata = ImageMetadataReader.readMetadata(new ByteArrayInputStream(image));
            ExifIFD0Directory ifd0 = metadata.getFirstDirectoryOfType(ExifIFD0Directory.class);
            orientation = ifd0 == null || !ifd0.containsTag(ExifIFD0Directory.TAG_ORIENTATION)
                    ? 1
                    : ifd0.getInt(ExifIFD0Directory.TAG_ORIENTATION);
        } catch (Exception e) {
            // An unreadable header is not an OCR failure; read the image as supplied.
            return new Result(image, 1, false);
        }

        if (orientation <= 1 || orientation > 8) {
            return new Result(image, Math.max(orientation, 1), false);
        }

        try {
            BufferedImage source = ImageIO.read(new ByteArrayInputStream(image));
            if (source == null) {
                return new Result(image, orientation, false);
            }
            BufferedImage turned = transform(source, orientation);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            if (!ImageIO.write(turned, "png", out)) {
                return new Result(image, orientation, false);
            }
            return new Result(out.toByteArray(), orientation, true);
        } catch (Exception | OutOfMemoryError e) {
            log.warn("Could not apply EXIF orientation {}; reading the image as supplied",
                    orientation, e);
            return new Result(image, orientation, false);
        }
    }

    /** The eight EXIF orientations, as the rotation and flip needed to undo each. */
    private static BufferedImage transform(BufferedImage source, int orientation) {
        int w = source.getWidth();
        int h = source.getHeight();
        boolean quarterTurn = orientation >= 5;
        int outW = quarterTurn ? h : w;
        int outH = quarterTurn ? w : h;

        AffineTransform t = new AffineTransform();
        switch (orientation) {
            case 2 -> {
                t.translate(w, 0);
                t.scale(-1, 1);
            }
            case 3 -> {
                t.translate(w, h);
                t.rotate(Math.PI);
            }
            case 4 -> {
                t.translate(0, h);
                t.scale(1, -1);
            }
            case 5 -> {
                t.rotate(-Math.PI / 2);
                t.scale(-1, 1);
            }
            case 6 -> {
                t.translate(h, 0);
                t.rotate(Math.PI / 2);
            }
            case 7 -> {
                t.translate(h, 0);
                t.rotate(Math.PI / 2);
                t.translate(w, 0);
                t.scale(-1, 1);
            }
            case 8 -> {
                t.translate(0, w);
                t.rotate(-Math.PI / 2);
            }
            default -> {
                return source;
            }
        }

        BufferedImage out = new BufferedImage(outW, outH, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        try {
            g.drawImage(source, t, null);
        } finally {
            g.dispose();
        }
        return out;
    }
}
