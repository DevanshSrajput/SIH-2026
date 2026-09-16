package com.govid.screening.tampering;

import com.govid.screening.domain.ModuleResult;
import com.govid.screening.domain.RiskFlag;
import com.govid.screening.domain.ScreeningModule;
import com.govid.screening.domain.Severity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TamperingServiceTest {

    /** A detector under our control, so corroboration logic can be tested in isolation. */
    private record StubDetector(String name, List<RiskFlag> findings) implements TamperingDetector {
        @Override
        public List<RiskFlag> analyse(ImageEvidence evidence) {
            return findings;
        }
    }

    private static RiskFlag flag(String code, Severity severity) {
        return RiskFlag.of(code, ScreeningModule.TAMPERING_DETECTION, severity, code);
    }

    private static byte[] image() throws Exception {
        BufferedImage image = new BufferedImage(400, 300, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(Color.LIGHT_GRAY);
        g.fillRect(0, 0, 400, 300);
        g.dispose();
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        ImageIO.write(image, "jpg", buffer);
        return buffer.toByteArray();
    }

    private static List<String> codes(ModuleResult result) {
        return result.flags().stream().map(RiskFlag::code).toList();
    }

    @Test
    @DisplayName("does not claim corroboration when one technique fires several times")
    void oneDetectorIsNotCorroboration() throws Exception {
        // The regression this guards: counting findings rather than techniques would read
        // one detector's three observations as three independent confirmations.
        TamperingService service = new TamperingService(List.of(
                new StubDetector("noisy-detector", List.of(
                        flag("FINDING_A", Severity.MEDIUM),
                        flag("FINDING_B", Severity.MEDIUM),
                        flag("FINDING_C", Severity.HIGH)))));

        ModuleResult result = service.analyse(image(), "image/jpeg");

        assertThat(codes(result)).doesNotContain("TAMPERING_CORROBORATED");
        assertThat(result.details()).containsEntry("corroboratingDetectors", List.of("noisy-detector"));
    }

    @Test
    @DisplayName("claims corroboration when three separate techniques agree")
    void threeDetectorsCorroborate() throws Exception {
        TamperingService service = new TamperingService(List.of(
                new StubDetector("ela", List.of(flag("ELA_SPLICE_SUSPECTED", Severity.HIGH))),
                new StubDetector("noise", List.of(flag("NOISE_FOREIGN_REGION", Severity.MEDIUM))),
                new StubDetector("copy-move", List.of(flag("COPY_MOVE_DUPLICATION", Severity.MEDIUM)))));

        ModuleResult result = service.analyse(image(), "image/jpeg");

        assertThat(codes(result)).contains("TAMPERING_CORROBORATED");
    }

    @Test
    @DisplayName("does not count a low-severity observation towards corroboration")
    void lowSeverityDoesNotCorroborate() throws Exception {
        TamperingService service = new TamperingService(List.of(
                new StubDetector("ela", List.of(flag("ELA_SPLICE_SUSPECTED", Severity.HIGH))),
                new StubDetector("noise", List.of(flag("NOISE_FOREIGN_REGION", Severity.MEDIUM))),
                new StubDetector("metadata", List.of(flag("META_NO_CAMERA_INFO", Severity.LOW)))));

        ModuleResult result = service.analyse(image(), "image/jpeg");

        assertThat(codes(result))
                .contains("META_NO_CAMERA_INFO")
                .doesNotContain("TAMPERING_CORROBORATED");
    }

    @Test
    @DisplayName("a detector that throws does not stop the others")
    void detectorFailureIsContained() throws Exception {
        TamperingDetector broken = new TamperingDetector() {
            @Override
            public String name() {
                return "broken";
            }

            @Override
            public List<RiskFlag> analyse(ImageEvidence evidence) {
                throw new IllegalStateException("detector exploded");
            }
        };

        TamperingService service = new TamperingService(List.of(
                broken,
                new StubDetector("ela", List.of(flag("ELA_SPLICE_SUSPECTED", Severity.HIGH)))));

        ModuleResult result = service.analyse(image(), "image/jpeg");

        assertThat(result.status()).isEqualTo(ModuleResult.Status.COMPLETED);
        assertThat(codes(result)).contains("ELA_SPLICE_SUSPECTED");
    }

    @Test
    @DisplayName("reports an undecodable upload rather than failing the module")
    void undecodableImageIsReported() {
        TamperingService service = new TamperingService(List.of());

        ModuleResult result = service.analyse("not an image".getBytes(), "image/jpeg");

        assertThat(result.status()).isEqualTo(ModuleResult.Status.COMPLETED);
        assertThat(codes(result)).contains("IMAGE_UNDECODABLE");
    }

    @Test
    @DisplayName("skips when no image was supplied at all")
    void skipsWithoutImage() {
        TamperingService service = new TamperingService(List.of());

        assertThat(service.analyse(null, null).status()).isEqualTo(ModuleResult.Status.SKIPPED);
    }
}
