package com.govid.screening.face;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Delegates face matching to a dedicated biometric service.
 *
 * <p>This is how face verification is deployed in practice: the embedding model runs in
 * its own container, on its own hardware, on a schedule of its own retraining - not inside
 * the screening API. Point {@code screening.face.service-url} at that service and Module 4
 * activates; leave it unset and Module 4 reports itself as not run.
 *
 * <p>This is the only matcher. An in-process OpenCV fallback used to sit behind it, but
 * its detector reported no facial landmarks, so it could never align a crop to SFace's
 * canonical layout - and an unaligned crop shifts every embedding in a common direction,
 * dragging unrelated faces together. A fallback that cannot confirm an identity is not a
 * fallback; it was removed rather than left to look like one.
 *
 * <p>The service is expected to accept a multipart POST at {@code /compare} with parts
 * {@code document} and {@code live}, and to reply with JSON carrying at least
 * {@code similarity}, {@code documentFaceFound} and {@code liveFaceFound}. A service that
 * also returns {@code decision} (MATCH / NO_MATCH / UNCERTAIN) has assessed quality and
 * liveness itself, and that verdict is passed straight through.
 */
@Component
@Order(10)
public class HttpFaceVerifier implements FaceVerifier {

    private static final Logger log = LoggerFactory.getLogger(HttpFaceVerifier.class);

    private final String serviceUrl;
    private final RestClient restClient;

    public HttpFaceVerifier(
            @Value("${screening.face.service-url:}") String serviceUrl,
            @Value("${screening.face.timeout-seconds:15}") long timeoutSeconds) {
        this.serviceUrl = serviceUrl == null ? "" : serviceUrl.trim();

        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(timeoutSeconds));
        factory.setReadTimeout(Duration.ofSeconds(timeoutSeconds));
        this.restClient = RestClient.builder().requestFactory(factory).build();

        if (this.serviceUrl.isEmpty()) {
            log.info("Face verification inactive: screening.face.service-url is not set");
        } else {
            log.info("Face verification active against {}", this.serviceUrl);
        }
    }

    @Override
    public String name() {
        return "http-face-service";
    }

    @Override
    public boolean isAvailable() {
        return !serviceUrl.isEmpty();
    }

    @Override
    public FaceMatchResult compare(byte[] documentPortrait, byte[] liveCapture) {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("document", new NamedByteArrayResource(documentPortrait, "document.jpg"));
        body.add("live", new NamedByteArrayResource(liveCapture, "live.jpg"));

        @SuppressWarnings("unchecked")
        Map<String, Object> response = restClient.post()
                .uri(serviceUrl + "/compare")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(body)
                .retrieve()
                .body(Map.class);

        if (response == null) {
            throw new IllegalStateException("Face service returned an empty response");
        }

        Map<String, Object> details = new HashMap<>(response);
        details.remove("similarity");
        details.put("serviceUrl", serviceUrl);

        return new FaceMatchResult(
                clamp(asDouble(response.get("similarity"))),
                asBoolean(response.get("documentFaceFound")),
                asBoolean(response.get("liveFaceFound")),
                name(),
                details,
                asDecision(response.get("decision")),
                asConfidence(response.get("confidence")),
                asStrings(response.get("reasons")),
                asStrings(response.get("blockers")),
                asString(response.get("summary")),
                asString(response.get("recaptureAdvice")));
    }

    private static double asDouble(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        // A matcher that did not return a usable score must not be read as a perfect
        // match; the absence of a measurement scores as no evidence of similarity.
        return 0.0;
    }

    private static double asConfidence(Object value) {
        return value instanceof Number number ? number.doubleValue() : Double.NaN;
    }

    /**
     * An unrecognised decision value is treated as absent rather than guessed at, so the
     * screening service falls back to its own thresholds. A service speaking a dialect we
     * do not understand must not be able to assert a match by accident.
     */
    private static FaceDecision asDecision(Object value) {
        if (!(value instanceof String text) || text.isBlank()) {
            return null;
        }
        try {
            return FaceDecision.valueOf(text.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            log.warn("Face service returned an unrecognised decision '{}'; "
                    + "falling back to threshold comparison", text);
            return null;
        }
    }

    private static String asString(Object value) {
        return value instanceof String text && !text.isBlank() ? text : null;
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

    /**
     * Absent detection flags default to {@code true} so a minimal service that only
     * returns a similarity is not misread as having failed to find any faces.
     */
    private static boolean asBoolean(Object value) {
        return !(value instanceof Boolean flag) || flag;
    }

    private static double clamp(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }

    /** Multipart parts need a filename, which the plain byte-array resource does not carry. */
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
