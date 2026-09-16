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
import java.util.HashMap;
import java.util.Map;

/**
 * Delegates face matching to a dedicated biometric service.
 *
 * <p>This is how face verification is deployed in practice: the embedding model runs in
 * its own container, on its own hardware, on a schedule of its own retraining - not inside
 * the screening API. Point {@code screening.face.service-url} at that service and Module 4
 * activates; leave it unset and Module 4 reports itself as not run.
 *
 * <p>The service is expected to accept a multipart POST at {@code /compare} with parts
 * {@code document} and {@code live}, and to reply with JSON:
 * <pre>{"similarity": 0.0-1.0, "documentFaceFound": bool, "liveFaceFound": bool}</pre>
 */
@Component
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
                details);
    }

    private static double asDouble(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        // A matcher that did not return a usable score must not be read as a perfect
        // match; the absence of a measurement scores as no evidence of similarity.
        return 0.0;
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
