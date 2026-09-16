package com.govid.screening.ocr;

import com.govid.screening.domain.DocumentType;
import com.govid.screening.domain.ModuleResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Engine selection and fallback.
 *
 * <p>These guard a defect that reached a running system: an engine reported itself
 * available, won selection, then failed at request time with a 401 - and Module 1 failed
 * outright even though a working engine was sitting behind it.
 */
class OcrServiceTest {

    private static final byte[] IMAGE = new byte[]{1, 2, 3, 4};

    /** An engine whose availability and behaviour the test controls. */
    private record StubEngine(String name, int priority, boolean available, String text,
                              RuntimeException failure) implements OcrEngine {

        static StubEngine working(String name, int priority, String text) {
            return new StubEngine(name, priority, true, text, null);
        }

        static StubEngine failing(String name, int priority, RuntimeException failure) {
            return new StubEngine(name, priority, true, null, failure);
        }

        static StubEngine unavailable(String name, int priority) {
            return new StubEngine(name, priority, false, null, null);
        }

        @Override
        public boolean isAvailable(OcrRequest request) {
            return available;
        }

        @Override
        public OcrOutput read(OcrRequest request) {
            if (failure != null) {
                throw failure;
            }
            return new OcrOutput(text, 0.95, Map.of());
        }
    }

    private static OcrEngine.OcrRequest request() {
        return new OcrEngine.OcrRequest(IMAGE, "image/jpeg", DocumentType.PASSPORT, null);
    }

    @Test
    @DisplayName("uses the highest-priority available engine")
    void picksByPriority() {
        OcrService service = new OcrService(List.of(
                StubEngine.working("slow", 30, "from slow"),
                StubEngine.working("fast", 10, "from fast")));

        OcrService.OcrOutcome outcome = service.extract(request());

        assertThat(outcome.fields().getEngine()).isEqualTo("fast");
        assertThat(outcome.result().status()).isEqualTo(ModuleResult.Status.COMPLETED);
    }

    @Test
    @DisplayName("skips an engine that reports itself unavailable")
    void skipsUnavailable() {
        OcrService service = new OcrService(List.of(
                StubEngine.unavailable("vision", 20),
                StubEngine.working("tesseract", 30, "read anyway")));

        assertThat(service.extract(request()).fields().getEngine()).isEqualTo("tesseract");
    }

    @Test
    @DisplayName("falls through to the next engine when the preferred one fails")
    void fallsThroughOnFailure() {
        // The exact shape of the bug this guards: the vision engine believed it was
        // available, then failed with a 401 at request time.
        OcrService service = new OcrService(List.of(
                StubEngine.failing("claude-vision", 20,
                        new IllegalStateException("401: x-api-key header is required")),
                StubEngine.working("tesseract", 30, "MRZ TEXT FROM FALLBACK")));

        OcrService.OcrOutcome outcome = service.extract(request());

        assertThat(outcome.result().status()).isEqualTo(ModuleResult.Status.COMPLETED);
        assertThat(outcome.fields().getEngine()).isEqualTo("tesseract");
        assertThat(outcome.fields().getRawText()).isEqualTo("MRZ TEXT FROM FALLBACK");
        assertThat(outcome.result().note()).contains("after 1 engine(s) failed");
    }

    @Test
    @DisplayName("records the failed engine as a diagnostic, never as a risk flag")
    void engineFailureDoesNotRaiseRisk() {
        OcrService service = new OcrService(List.of(
                StubEngine.failing("claude-vision", 20, new IllegalStateException("401 denied")),
                StubEngine.working("tesseract", 30, "text")));

        OcrService.OcrOutcome outcome = service.extract(request());

        // A broken credential is our infrastructure failing; it must not raise the score
        // against the traveller standing at the desk.
        assertThat(outcome.result().flags())
                .noneSatisfy(flag -> assertThat(flag.code()).contains("ENGINE"));
        assertThat(outcome.result().details()).containsKey("failedEngines");
        assertThat((List<?>) outcome.result().details().get("failedEngines"))
                .anySatisfy(entry -> assertThat(String.valueOf(entry)).contains("claude-vision"));
    }

    @Test
    @DisplayName("fails the module only when every available engine fails")
    void failsWhenAllEnginesFail() {
        OcrService service = new OcrService(List.of(
                StubEngine.failing("claude-vision", 20, new IllegalStateException("401 denied")),
                StubEngine.failing("tesseract", 30, new IllegalStateException("binary crashed"))));

        ModuleResult result = service.extract(request()).result();

        assertThat(result.status()).isEqualTo(ModuleResult.Status.FAILED);
        assertThat(result.note())
                .contains("claude-vision")
                .contains("tesseract");
    }

    @Test
    @DisplayName("explains what to configure when no engine is available at all")
    void explainsWhenNothingAvailable() {
        OcrService service = new OcrService(List.of(StubEngine.unavailable("vision", 20)));

        ModuleResult result = service.extract(request()).result();

        assertThat(result.status()).isEqualTo(ModuleResult.Status.FAILED);
        assertThat(result.note()).contains("No OCR engine available");
    }
}
