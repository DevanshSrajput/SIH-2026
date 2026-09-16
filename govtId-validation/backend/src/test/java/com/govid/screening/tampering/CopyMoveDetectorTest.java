package com.govid.screening.tampering;

import com.govid.screening.domain.RiskFlag;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The two cases that decide whether this detector is usable at a checkpoint: it must stay
 * silent on the repetitive security printing every genuine passport carries, and it must
 * still catch an actual duplicated region.
 */
class CopyMoveDetectorTest {

    private final CopyMoveDetector detector = new CopyMoveDetector();

    /**
     * A page resembling a genuine document: gradient paper, a dense guilloche pattern of
     * repeated concentric line-art, printed text, and a uniform capture-noise floor.
     */
    private static BufferedImage securityPrintedPage() {
        int width = 900;
        int height = 620;
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        g.setPaint(new GradientPaint(0, 0, new Color(243, 241, 232),
                width, height, new Color(224, 222, 212)));
        g.fillRect(0, 0, width, height);

        // Guilloche: many repeated strokes at regular offsets, exactly the structure that
        // naively looks like copy-move.
        g.setColor(new Color(170, 182, 200));
        for (int i = 0; i < 120; i++) {
            g.drawOval(30 + i * 5, 30 + (int) (26 * Math.sin(i / 5.0)), 460, 280);
        }
        for (int i = 0; i < 60; i++) {
            g.drawLine(0, i * 11, width, i * 11 + 40);
        }

        // Distinct printed fields. A real data page never repeats the same line of text,
        // and a fixture that did would be testing duplication we deliberately created.
        String[] lines = {
                "SURNAME        ERIKSSON",
                "GIVEN NAMES    ANNA MARIA",
                "PASSPORT NO    L898902C3",
                "NATIONALITY    SWEDEN",
                "DATE OF BIRTH  12 AUG 1974",
                "PLACE OF BIRTH GOTEBORG",
                "AUTHORITY      POLISMYNDIGHETEN",
                "DATE OF EXPIRY 15 APR 2030",
        };
        g.setColor(new Color(28, 28, 34));
        g.setFont(new java.awt.Font("Monospaced", java.awt.Font.PLAIN, 22));
        for (int i = 0; i < lines.length; i++) {
            g.drawString(lines[i], 60, 400 + i * 26);
        }
        g.dispose();

        return addUniformNoise(image, 4242);
    }

    /** Adds an even noise floor, as a camera sensor would across the whole frame. */
    private static BufferedImage addUniformNoise(BufferedImage image, long seed) {
        Random random = new Random(seed);
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                int pixel = image.getRGB(x, y);
                int shift = random.nextInt(7) - 3;
                int r = clamp(((pixel >> 16) & 0xFF) + shift);
                int g = clamp(((pixel >> 8) & 0xFF) + shift);
                int b = clamp((pixel & 0xFF) + shift);
                image.setRGB(x, y, (r << 16) | (g << 8) | b);
            }
        }
        return image;
    }

    private static int clamp(int value) {
        return Math.max(0, Math.min(255, value));
    }

    /** Copies a solid square from one place on the page to another, as a forger would. */
    private static BufferedImage withDuplicatedRegion(BufferedImage source) {
        BufferedImage copy = new BufferedImage(
                source.getWidth(), source.getHeight(), BufferedImage.TYPE_INT_RGB);
        copy.getGraphics().drawImage(source, 0, 0, null);

        int size = 110;
        int fromX = 80;
        int fromY = 60;
        int toX = 520;
        int toY = 380;
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                copy.setRGB(toX + x, toY + y, source.getRGB(fromX + x, fromY + y));
            }
        }
        return copy;
    }

    private static TamperingDetector.ImageEvidence asJpeg(BufferedImage image) throws Exception {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        ImageIO.write(image, "jpg", buffer);
        byte[] bytes = buffer.toByteArray();
        // Round-trip through JPEG so the detector sees the same compression artefacts a
        // real upload would carry.
        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(bytes));
        return new TamperingDetector.ImageEvidence(bytes, "image/jpeg", decoded);
    }

    @Test
    @DisplayName("stays silent on the repeated security printing of a genuine document")
    void ignoresSecurityPrinting() throws Exception {
        List<RiskFlag> flags = detector.analyse(asJpeg(securityPrintedPage()));

        assertThat(flags)
                .as("guilloche and microprinting must not be reported as a pasted region")
                .isEmpty();
    }

    @Test
    @DisplayName("catches a solid region duplicated elsewhere on the same document")
    void catchesDuplicatedRegion() throws Exception {
        BufferedImage tampered = withDuplicatedRegion(securityPrintedPage());

        List<RiskFlag> flags = detector.analyse(asJpeg(tampered));

        assertThat(flags).isNotEmpty();
        RiskFlag flag = flags.get(0);
        assertThat(flag.code()).isEqualTo("COPY_MOVE_DUPLICATION");
        assertThat(flag.evidence()).containsKeys("regionCells", "regionDensity", "shiftX");

        // The finding must point the officer at the right place, not merely fire. The
        // region was copied from (80, 60) and is 110 pixels square.
        assertThat((int) flag.evidence().get("shiftX")).isEqualTo(440);
        assertThat((int) flag.evidence().get("shiftY")).isEqualTo(320);
        assertThat((int) flag.evidence().get("regionX")).isBetween(50, 110);
        assertThat((int) flag.evidence().get("regionY")).isBetween(30, 90);
        assertThat((int) flag.evidence().get("regionWidth")).isBetween(56, 160);
        assertThat((int) flag.evidence().get("regionHeight")).isBetween(56, 160);
    }

    @Test
    @DisplayName("does nothing when there are no pixels to analyse")
    void handlesMissingPixels() {
        assertThat(detector.analyse(
                new TamperingDetector.ImageEvidence(new byte[0], "image/jpeg", null))).isEmpty();
    }
}
