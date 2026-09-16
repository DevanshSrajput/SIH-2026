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
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Classical OCR backend, shelling out to a local Tesseract install.
 *
 * <p>Present so the platform has an offline, no-network reading path: at a border post
 * that cannot reach an external service, this is what runs. It self-deactivates when the
 * binary is not on the PATH.
 */
@Component
public class TesseractOcrEngine implements OcrEngine {

    private static final Logger log = LoggerFactory.getLogger(TesseractOcrEngine.class);

    private final String binary;
    private final String languages;
    private final long timeoutSeconds;

    private volatile Boolean binaryPresent;

    public TesseractOcrEngine(
            @Value("${screening.ocr.tesseract.binary:tesseract}") String binary,
            @Value("${screening.ocr.tesseract.languages:eng}") String languages,
            @Value("${screening.ocr.tesseract.timeout-seconds:30}") long timeoutSeconds) {
        this.binary = binary;
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
            try {
                Process probe = new ProcessBuilder(binary, "--version")
                        .redirectErrorStream(true)
                        .start();
                boolean finished = probe.waitFor(10, TimeUnit.SECONDS);
                binaryPresent = finished && probe.exitValue() == 0;
            } catch (IOException | InterruptedException e) {
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                binaryPresent = false;
            }
            log.info("Tesseract OCR engine {}", binaryPresent ? "active" : "inactive (binary not found)");
            return binaryPresent;
        }
    }

    @Override
    public OcrOutput read(OcrRequest request) throws Exception {
        Path input = Files.createTempFile("screening-ocr-", suffixFor(request.contentType()));
        try {
            Files.write(input, request.image());

            // --psm 6 treats the page as a single uniform block, which suits the flat,
            // densely printed data page of a passport or ID card.
            Process process = new ProcessBuilder(
                    binary, input.toAbsolutePath().toString(), "stdout",
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
            return new OcrOutput(text, 0.70, Map.of("languages", languages, "psm", 6));
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
