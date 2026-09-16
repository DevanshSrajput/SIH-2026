package com.govid.screening.face;

import org.bytedeco.opencv.opencv_core.*;
import org.bytedeco.opencv.opencv_dnn.Net;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.bytedeco.opencv.global.opencv_dnn.readNetFromCaffe;
import static org.bytedeco.opencv.global.opencv_dnn.readNetFromONNX;

/**
 * Manages loading and caching of DNN models used for face detection and recognition.
 *
 * <p>Models are shipped as classpath resources and extracted to a temp directory on first
 * use. Thread-safe: multiple screening threads may need models simultaneously.
 */
@Component
public class FaceModelManager {

    private static final Logger log = LoggerFactory.getLogger(FaceModelManager.class);

    private final Path modelDir;
    private final Map<String, Net> cache = new ConcurrentHashMap<>();

    private final String faceDetectorCaffemodel;
    private final String faceDetectorPrototxt;
    private final String faceRecognizerOnnx;

    public FaceModelManager(
            @Value("${screening.face.models.dir:#{systemProperties['java.io.tmpdir']}/govtid-face-models}")
            String modelDir,
            @Value("${screening.face.models.detector-caffemodel:opencv_face_detector.caffemodel}")
            String faceDetectorCaffemodel,
            @Value("${screening.face.models.detector-prototxt:opencv_face_detector.prototxt}")
            String faceDetectorPrototxt,
            @Value("${screening.face.models.recognizer-onnx:face_recognizer_fast.onnx}")
            String faceRecognizerOnnx) {
        this.modelDir = Path.of(modelDir);
        this.faceDetectorCaffemodel = faceDetectorCaffemodel;
        this.faceDetectorPrototxt = faceDetectorPrototxt;
        this.faceRecognizerOnnx = faceRecognizerOnnx;
    }

    @PostConstruct
    void init() {
        try {
            Files.createDirectories(modelDir);
            log.info("Face model directory: {}", modelDir.toAbsolutePath());
        } catch (IOException e) {
            throw new IllegalStateException(
                    "Could not create face model directory: " + modelDir.toAbsolutePath(), e);
        }
    }

    /**
     * Returns a loaded Caffe face-detection network.
     */
    public Net faceDetector() {
        return cache.computeIfAbsent("face-detector", key -> {
            try {
                Path prototxt = extractResource(faceDetectorPrototxt);
                Path caffemodel = extractResource(faceDetectorCaffemodel);
                log.info("Loading face detection model (Caffe DNN)");
                return readNetFromCaffe(prototxt.toString(), caffemodel.toString());
            } catch (Exception e) {
                throw new IllegalStateException("Failed to load face detection model", e);
            }
        });
    }

    /**
     * Returns a loaded SFace ONNX face-recognition network.
     */
    public Net faceRecognizer() {
        return cache.computeIfAbsent("face-recognizer", key -> {
            try {
                Path onnx = extractResource(faceRecognizerOnnx);
                log.info("Loading face recognition model (SFace ONNX)");
                return readNetFromONNX(onnx.toString());
            } catch (Exception e) {
                throw new IllegalStateException("Failed to load face recognition model", e);
            }
        });
    }

    /**
     * Extracts a classpath resource to the model directory, returning the local path.
     */
    private Path extractResource(String resourceName) throws IOException {
        Path target = modelDir.resolve(resourceName);
        if (Files.exists(target)) {
            return target;
        }
        Path tmp = target.resolveSibling(resourceName + ".tmp");
        try (InputStream in = getClass().getResourceAsStream("/models/face/" + resourceName)) {
            if (in == null) {
                throw new IOException("Classpath resource not found: /models/face/" + resourceName);
            }
            Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
        }
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        log.info("Extracted face model resource to {}", target);
        return target;
    }
}
