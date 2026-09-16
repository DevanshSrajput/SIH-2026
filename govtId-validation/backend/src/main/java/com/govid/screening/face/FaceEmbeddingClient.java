package com.govid.screening.face;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Extracts a face embedding by calling the face service's {@code /embed} endpoint.
 *
 * <p>The split of responsibilities is deliberate: the model lives in the Python service,
 * the records live in MongoDB here. Neither side needs the other's storage, and the
 * recognition model can be swapped or retrained without migrating a database - the
 * embeddings are re-derived from the reference images if the model changes.
 */
@Component
public class FaceEmbeddingClient {

    private static final Logger log = LoggerFactory.getLogger(FaceEmbeddingClient.class);

    private final String serviceUrl;
    private final RestClient restClient;

    public FaceEmbeddingClient(
            @Value("${screening.face.service-url:}") String serviceUrl,
            @Value("${screening.face.timeout-seconds:15}") long timeoutSeconds) {
        this.serviceUrl = serviceUrl == null ? "" : serviceUrl.trim();

        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(timeoutSeconds));
        factory.setReadTimeout(Duration.ofSeconds(timeoutSeconds));
        this.restClient = RestClient.builder().requestFactory(factory).build();
    }

    public boolean isAvailable() {
        return !serviceUrl.isEmpty();
    }

    /**
     * One extracted face embedding, with the quality verdict for the image it came from.
     *
     * @param embedding      L2-normalised vector
     * @param qualityScore   image quality in [0, 1]
     * @param qualityUsable  whether the image cleared the quality gate
     * @param qualityIssues  what was wrong with it, if anything
     * @param qualityAdvice  what to do about it
     * @param facesDetected  how many faces were in the image
     * @param alignmentUsed  whether the crop was landmark-aligned
     * @param engine         which model produced the embedding
     */
    public record Embedding(
            List<Double> embedding,
            double qualityScore,
            boolean qualityUsable,
            List<String> qualityIssues,
            String qualityAdvice,
            int facesDetected,
            boolean alignmentUsed,
            String engine) {

        public int dimension() {
            return embedding.size();
        }
    }

    /**
     * @param source {@code "document"} or {@code "live"}; document portraits are held to a
     *               lower resolution bar because they are printed small and re-scanned.
     */
    public Embedding embed(byte[] image, String source) {
        if (!isAvailable()) {
            throw new IllegalStateException(
                    "Face enrolment needs a face service. Set screening.face.service-url.");
        }

        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("image", new NamedByteArrayResource(image, "face.jpg"));
        body.add("source", source == null ? "live" : source);

        @SuppressWarnings("unchecked")
        Map<String, Object> response = restClient.post()
                .uri(serviceUrl + "/embed")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(body)
                .retrieve()
                .body(Map.class);

        if (response == null) {
            throw new IllegalStateException("Face service returned an empty response");
        }

        List<Double> vector = asDoubles(response.get("embedding"));
        if (vector.isEmpty()) {
            throw new IllegalStateException("Face service returned no embedding");
        }

        Map<String, Object> quality = asMap(response.get("quality"));

        return new Embedding(
                vector,
                asDouble(quality.get("score")),
                Boolean.TRUE.equals(quality.get("usable")),
                asStrings(quality.get("issues")),
                quality.get("advice") == null ? null : String.valueOf(quality.get("advice")),
                (int) asDouble(response.get("facesDetected")),
                !Boolean.FALSE.equals(response.get("alignmentUsed")),
                response.get("engine") == null ? "unknown" : String.valueOf(response.get("engine")));
    }

    private static double asDouble(Object value) {
        return value instanceof Number number ? number.doubleValue() : 0.0;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    private static List<Double> asDoubles(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<Double> doubles = new ArrayList<>(list.size());
        for (Object item : list) {
            if (item instanceof Number number) {
                doubles.add(number.doubleValue());
            }
        }
        return doubles;
    }

    private static List<String> asStrings(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<String> strings = new ArrayList<>(list.size());
        for (Object item : list) {
            if (item != null) {
                strings.add(String.valueOf(item));
            }
        }
        return strings;
    }

    private static final class NamedByteArrayResource extends ByteArrayResource {

        private final String filename;

        private NamedByteArrayResource(byte[] bytes, String filename) {
            super(bytes);
            this.filename = filename;
        }

        @Override
        public String getFilename() {
            return filename;
        }
    }
}
