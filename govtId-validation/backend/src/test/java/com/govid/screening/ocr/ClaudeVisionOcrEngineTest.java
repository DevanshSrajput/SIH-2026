package com.govid.screening.ocr;

import com.govid.screening.domain.DocumentType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Availability of the vision engine.
 *
 * <p>Guards the defect behind a 401 seen in a running system: the engine reported itself
 * available with no credential configured, won engine selection, and then failed at request
 * time. An engine that cannot work must say so during selection, not afterwards.
 */
class ClaudeVisionOcrEngineTest {

    private static final byte[] IMAGE = new byte[]{1, 2, 3, 4};

    private static ClaudeVisionOcrEngine engine(boolean enabled, String apiKey) {
        return new ClaudeVisionOcrEngine(JsonMapper.builder().build(), enabled, apiKey, "medium");
    }

    private static OcrEngine.OcrRequest request(byte[] image) {
        return new OcrEngine.OcrRequest(image, "image/jpeg", DocumentType.PASSPORT, null);
    }

    @Test
    @DisplayName("reports itself available when a key is configured")
    void availableWithConfiguredKey() {
        assertThat(engine(true, "sk-ant-test-key").isAvailable(request(IMAGE))).isTrue();
    }

    @Test
    @DisplayName("reports itself unavailable when disabled by configuration")
    void unavailableWhenDisabled() {
        assertThat(engine(false, "sk-ant-test-key").isAvailable(request(IMAGE))).isFalse();
    }

    @Test
    @DisplayName("reports itself unavailable when there is no image to read")
    void unavailableWithoutImage() {
        assertThat(engine(true, "sk-ant-test-key").isAvailable(request(new byte[0]))).isFalse();
    }

    @Test
    @DisplayName("does not claim availability it cannot honour when no credential is set")
    void unavailableWithoutCredential() {
        // With no configured key, availability depends on the environment. Whatever the
        // environment says, the engine's answer and its ability to build a client must
        // agree - that agreement is the property the 401 defect violated.
        ClaudeVisionOcrEngine engine = engine(true, "");
        boolean claimsAvailable = engine.isAvailable(request(IMAGE));

        boolean credentialPresent = isSet(System.getenv("ANTHROPIC_API_KEY"))
                || isSet(System.getenv("ANTHROPIC_AUTH_TOKEN"));

        assertThat(claimsAvailable)
                .as("availability must track whether a credential actually exists")
                .isEqualTo(credentialPresent);
    }

    @Test
    @DisplayName("refuses to read when it is not configured")
    void readRefusesWithoutClient() {
        ClaudeVisionOcrEngine engine = engine(false, "");

        assertThat(catchThrowable(() -> engine.read(request(IMAGE))))
                .isInstanceOf(IllegalStateException.class);
    }

    private static Throwable catchThrowable(ThrowingCallable callable) {
        try {
            callable.call();
            return null;
        } catch (Throwable t) {
            return t;
        }
    }

    @FunctionalInterface
    private interface ThrowingCallable {
        void call() throws Exception;
    }

    private static boolean isSet(String value) {
        return value != null && !value.isBlank();
    }
}
