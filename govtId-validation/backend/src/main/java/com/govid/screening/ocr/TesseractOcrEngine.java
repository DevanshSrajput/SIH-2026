package com.govid.screening.ocr;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Classical OCR backend, shelling out to a local Tesseract install.
 *
 * <p>Present so the platform has an offline, no-network reading path: at a border post
 * that cannot reach an external service, this is what runs.
 *
 * <p>The binary is found without the operator having to edit {@code PATH}. The configured
 * value is tried first, then the standard install locations for each platform. A checkpoint
 * machine is provisioned by whoever is on shift, and an OCR engine that silently reports
 * itself inactive because an installer did not update an environment variable is a support
 * call that should not have to happen. It still self-deactivates when Tesseract genuinely
 * is not installed.
 */
@Component
public class TesseractOcrEngine implements OcrEngine {

    private static final Logger log = LoggerFactory.getLogger(TesseractOcrEngine.class);

    /**
     * Where Tesseract installs itself when nobody chose a location. Searched in order,
     * after the configured value.
     */
    private static final List<String> WELL_KNOWN_LOCATIONS = List.of(
            // Windows - the default path for the UB-Mannheim installer.
            "C:\\Program Files\\Tesseract-OCR\\tesseract.exe",
            "C:\\Program Files (x86)\\Tesseract-OCR\\tesseract.exe",
            // Linux package managers.
            "/usr/bin/tesseract",
            "/usr/local/bin/tesseract",
            "/snap/bin/tesseract",
            // macOS Homebrew, Intel and Apple silicon.
            "/opt/homebrew/bin/tesseract",
            "/usr/local/Cellar/tesseract/bin/tesseract");

    private final String configuredBinary;
    private final String languages;
    private final long timeoutSeconds;

    /** The binary that actually answered, resolved once on first use. */
    private volatile String resolvedBinary;
    private volatile Boolean binaryPresent;

    public TesseractOcrEngine(
            @Value("${screening.ocr.tesseract.binary:tesseract}") String binary,
            @Value("${screening.ocr.tesseract.languages:eng}") String languages,
            @Value("${screening.ocr.tesseract.timeout-seconds:30}") long timeoutSeconds) {
        this.configuredBinary = binary;
        this.languages = languages;
        this.timeoutSeconds = timeoutSeconds;
    }

    @Override
    public String name() {
        return "tesseract";
    }

    @Override
    public int priority() {
        return 30;
    }

    @Override
    public boolean isAvailable(OcrRequest request) {
        if (request.image() == null || request.image().length == 0) {
            return false;
        }
        return binaryPresent();
    }

    private boolean binaryPresent() {
        if (binaryPresent != null) {
            return binaryPresent;
        }
        synchronized (this) {
            if (binaryPresent != null) {
                return binaryPresent;
            }
            for (String candidate : candidates()) {
                if (responds(candidate)) {
                    resolvedBinary = candidate;
                    binaryPresent = true;
                    log.info("Tesseract OCR engine active: {}", candidate);
                    return true;
                }
            }
            binaryPresent = false;
            log.info("Tesseract OCR engine inactive: no Tesseract binary was found. "
                    + "Install it, or set screening.ocr.tesseract.binary to its full path.");
            return false;
        }
    }

    /** The configured value first, then the well-known install locations. */
    private List<String> candidates() {
        List<String> candidates = new ArrayList<>();
        if (configuredBinary != null && !configuredBinary.isBlank()) {
            candidates.add(configuredBinary.trim());
        }
        for (String location : WELL_KNOWN_LOCATIONS) {
            if (!candidates.contains(location) && Files.isRegularFile(Path.of(location))) {
                candidates.add(location);
            }
        }
        return candidates;
    }

    /** Whether this path is a Tesseract that runs. */
    private boolean responds(String candidate) {
        try {
            Process probe = new ProcessBuilder(candidate, "--version")
                    .redirectErrorStream(true)
                    .start();
            if (!probe.waitFor(10, TimeUnit.SECONDS)) {
                probe.destroyForcibly();
                return false;
            }
            return probe.exitValue() == 0;
        } catch (IOException e) {
            log.debug("Tesseract not at {}: {}", candidate, e.getMessage());
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** The resolved path, for diagnostics. Null until the first availability check. */
    public String resolvedBinary() {
        return resolvedBinary;
    }

    @Override
    public OcrOutput read(OcrRequest request) throws Exception {
        Path input = Files.createTempFile("screening-ocr-", suffixFor(request.contentType()));
        try {
            Files.write(input, request.image());

            // --psm 6 treats the page as a single uniform block, which suits the flat,
            // densely printed data page of a passport or ID card.
            Process process = new ProcessBuilder(
                    resolvedBinary == null ? configuredBinary : resolvedBinary,
                    input.toAbsolutePath().toString(), "stdout",
                    "-l", languages, "--psm", "6")
                    .redirectErrorStream(false)
                    .start();

            String text;
            try (InputStream out = process.getInputStream()) {
                text = new String(out.readAllBytes(), StandardCharsets.UTF_8);
            }

            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IOException("Tesseract timed out after " + timeoutSeconds + "s");
            }
            if (process.exitValue() != 0) {
                throw new IOException("Tesseract exited with code " + process.exitValue());
            }

            // Tesseract does not report a document-level confidence on the stdout path.
            // The value below is a conservative fixed estimate; Module 2 is what actually
            // decides whether the text is trustworthy.
            return new OcrOutput(text, 0.70, Map.of(
                    "languages", languages, "psm", 6,
                    "binary", String.valueOf(resolvedBinary)));
        } finally {
            Files.deleteIfExists(input);
        }
    }

    private static String suffixFor(String contentType) {
        String type = contentType == null ? "" : contentType.toLowerCase();
        if (type.contains("png")) {
            return ".png";
        }
        if (type.contains("tif")) {
            return ".tif";
        }
        return ".jpg";
    }
}
