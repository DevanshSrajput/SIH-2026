package com.govid.screening.ocr;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.models.messages.Base64ImageSource;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.ImageBlockParam;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.messages.TextBlockParam;
import com.anthropic.models.messages.ThinkingConfigAdaptive;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Vision-model OCR backend.
 *
 * <p>Reads the printed page and the machine readable zone in one pass. Unlike classical
 * OCR it copes with the conditions that actually break document reading at a checkpoint:
 * glare on the laminate, a passport held at an angle, a partially obscured MRZ, and
 * non-Latin printing alongside the transliterated MRZ.
 *
 * <p>The model is asked only to <em>read</em>. It is never asked whether the document is
 * genuine: that judgement belongs to Modules 2 and 3, which are deterministic and
 * auditable. Keeping extraction and adjudication apart means a model error shows up as a
 * failed check digit rather than as a silently wrong verdict.
 *
 * <p>The engine deactivates itself when no credential is configured, so the platform runs
 * end-to-end without it.
 */
@Component
public class ClaudeVisionOcrEngine implements OcrEngine {

    private static final Logger log = LoggerFactory.getLogger(ClaudeVisionOcrEngine.class);

    private static final String MODEL = "claude-opus-5";

    private static final String SYSTEM_PROMPT = """
            You transcribe identity and travel documents for a border control system.

            Transcribe only what is printed. Never infer, correct, complete or normalise a
            value. If a character is unreadable, emit '?' for that character. If a whole
            field is absent or illegible, use null. Do not guess a checksum, a date or a
            document number, and do not repair a value that looks wrong - a wrong-looking
            value is evidence and must survive to the validation stage unchanged.

            Reply with a single JSON object and nothing else:
            {
              "documentType": "PASSPORT|VISA|NATIONAL_ID|DRIVING_LICENCE|PERMIT|TRAVEL_AUTHORIZATION|UNKNOWN",
              "mrzLines": ["<each MRZ line exactly as printed, including < fillers>"],
              "visibleText": "<all other printed text, line by line>",
              "confidence": <0.0-1.0>
            }
            """;

    private final ObjectMapper objectMapper;
    private final boolean configuredEnabled;
    private final String configuredApiKey;
    private final OutputConfig.Effort effort;

    /** Built once on first use; {@code null} means no usable credential. */
    private volatile AnthropicClient client;
    private volatile boolean initialised;

    public ClaudeVisionOcrEngine(
            ObjectMapper objectMapper,
            @Value("${screening.ocr.claude.enabled:true}") boolean configuredEnabled,
            @Value("${screening.ocr.claude.api-key:}") String configuredApiKey,
            @Value("${screening.ocr.claude.effort:medium}") String effort) {
        this.objectMapper = objectMapper;
        this.configuredEnabled = configuredEnabled;
        this.configuredApiKey = configuredApiKey == null ? "" : configuredApiKey.trim();
        this.effort = switch (effort.toLowerCase()) {
            case "low" -> OutputConfig.Effort.LOW;
            case "high" -> OutputConfig.Effort.HIGH;
            default -> OutputConfig.Effort.MEDIUM;
        };
    }

    @Override
    public String name() {
        return "claude-vision";
    }

    @Override
    public int priority() {
        return 20;
    }

    @Override
    public boolean isAvailable(OcrRequest request) {
        if (!configuredEnabled || request.image() == null || request.image().length == 0) {
            return false;
        }
        return clientOrNull() != null;
    }

    /**
     * Lazily builds the SDK client. A missing credential is an expected configuration,
     * not an error, so it is logged once and the engine simply stays unavailable.
     *
     * <p>The credential is checked explicitly rather than by attempting to construct the
     * client. {@code fromEnv()} succeeds with no credential present and returns a client
     * that fails at request time with a 401 - which would make this engine claim
     * availability it does not have, win engine selection, and take Module 1 down on a
     * document a lower-priority engine could have read.
     */
    private AnthropicClient clientOrNull() {
        if (initialised) {
            return client;
        }
        synchronized (this) {
            if (initialised) {
                return client;
            }
            initialised = true;
            try {
                if (!configuredApiKey.isBlank()) {
                    client = AnthropicOkHttpClient.builder().apiKey(configuredApiKey).build();
                    log.info("Claude vision OCR engine active (model {}, key from configuration)",
                            MODEL);
                } else if (hasCredentialInEnvironment()) {
                    client = AnthropicOkHttpClient.fromEnv();
                    log.info("Claude vision OCR engine active (model {}, key from environment)",
                            MODEL);
                } else {
                    log.info("Claude vision OCR engine inactive: no Anthropic credential "
                            + "configured (set ANTHROPIC_API_KEY or screening.ocr.claude.api-key)");
                    client = null;
                }
            } catch (RuntimeException e) {
                log.warn("Claude vision OCR engine inactive: client could not be built", e);
                client = null;
            }
            return client;
        }
    }

    private static boolean hasCredentialInEnvironment() {
        return isSet(System.getenv("ANTHROPIC_API_KEY"))
                || isSet(System.getenv("ANTHROPIC_AUTH_TOKEN"));
    }

    private static boolean isSet(String value) {
        return value != null && !value.isBlank();
    }

    @Override
    public OcrOutput read(OcrRequest request) throws Exception {
        AnthropicClient anthropic = clientOrNull();
        if (anthropic == null) {
            throw new IllegalStateException("Claude vision OCR engine is not configured");
        }

        String base64 = Base64.getEncoder().encodeToString(request.image());
        ImageBlockParam image = ImageBlockParam.builder()
                .source(Base64ImageSource.builder()
                        .mediaType(mediaType(request.contentType()))
                        .data(base64)
                        .build())
                .build();

        String instruction = request.declaredType() == null
                ? "Transcribe this document."
                : "Transcribe this document. The officer selected the type "
                  + request.declaredType() + ", but report what you actually see.";

        MessageCreateParams params = MessageCreateParams.builder()
                .model(MODEL)
                .maxTokens(8000L)
                .system(SYSTEM_PROMPT)
                .thinking(ThinkingConfigAdaptive.builder().build())
                .outputConfig(OutputConfig.builder().effort(effort).build())
                .addUserMessageOfBlockParams(List.of(
                        ContentBlockParam.ofImage(image),
                        ContentBlockParam.ofText(TextBlockParam.builder().text(instruction).build())))
                .build();

        Message response = anthropic.messages().create(params);

        StringBuilder replyText = new StringBuilder();
        response.content().stream()
                .flatMap(block -> block.text().stream())
                .forEach(block -> replyText.append(block.text()));

        return parseReply(replyText.toString());
    }

    /**
     * Turns the model reply into plain text for the deterministic stages.
     *
     * <p>MRZ lines are appended last so {@link MrzParser} finds them where it expects
     * them, mirroring their physical position at the foot of the document.
     */
    private OcrOutput parseReply(String reply) throws Exception {
        String json = stripCodeFence(reply);
        JsonNode root = objectMapper.readTree(json);

        List<String> mrzLines = new ArrayList<>();
        JsonNode mrzNode = root.path("mrzLines");
        if (mrzNode.isArray()) {
            mrzNode.forEach(line -> {
                String value = line.asText("").trim();
                if (!value.isEmpty()) {
                    mrzLines.add(value);
                }
            });
        }

        String visibleText = root.path("visibleText").asText("");
        String rawText = visibleText.isBlank()
                ? String.join("\n", mrzLines)
                : visibleText + "\n" + String.join("\n", mrzLines);

        Map<String, Object> details = new HashMap<>();
        details.put("model", MODEL);
        details.put("mrzLineCount", mrzLines.size());
        details.put("reportedDocumentType", root.path("documentType").asText(null));

        double confidence = root.path("confidence").asDouble(0.85);
        return new OcrOutput(rawText, clamp(confidence), details);
    }

    /** Tolerates a fenced reply even though the prompt asks for bare JSON. */
    private static String stripCodeFence(String reply) {
        String trimmed = reply.trim();
        if (!trimmed.startsWith("```")) {
            return trimmed;
        }
        int firstNewline = trimmed.indexOf('\n');
        int lastFence = trimmed.lastIndexOf("```");
        if (firstNewline < 0 || lastFence <= firstNewline) {
            return trimmed;
        }
        return trimmed.substring(firstNewline + 1, lastFence).trim();
    }

    private static double clamp(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }

    private static Base64ImageSource.MediaType mediaType(String contentType) {
        String type = contentType == null ? "" : contentType.toLowerCase();
        if (type.contains("png")) {
            return Base64ImageSource.MediaType.IMAGE_PNG;
        }
        if (type.contains("webp")) {
            return Base64ImageSource.MediaType.IMAGE_WEBP;
        }
        if (type.contains("gif")) {
            return Base64ImageSource.MediaType.IMAGE_GIF;
        }
        return Base64ImageSource.MediaType.IMAGE_JPEG;
    }
}
