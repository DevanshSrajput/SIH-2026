package com.govid.screening.face;

import org.bytedeco.javacpp.BytePointer;
import org.bytedeco.opencv.opencv_core.*;
import org.bytedeco.opencv.opencv_dnn.Net;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.bytedeco.opencv.global.opencv_core.*;
import static org.bytedeco.opencv.global.opencv_dnn.*;
import static org.bytedeco.opencv.global.opencv_imgproc.*;
import static org.bytedeco.opencv.global.opencv_imgcodecs.*;

/**
 * Local face verification using OpenCV's DNN face detector and SFace recognition model.
 */
@Component
public class LocalFaceVerifier implements FaceVerifier {

    private static final Logger log = LoggerFactory.getLogger(LocalFaceVerifier.class);

    private static final Size RECOGNITION_SIZE = new Size(112, 112);
    private static final Scalar SFACE_MEAN = new Scalar(104.0, 177.0, 123.0, 0);

    private final FaceModelManager modelManager;
    private final FaceDetector faceDetector;
    private final boolean enabled;

    public LocalFaceVerifier(
            FaceModelManager modelManager,
            FaceDetector faceDetector,
            @Value("${screening.face.local.enabled:true}") boolean enabled) {
        this.modelManager = modelManager;
        this.faceDetector = faceDetector;
        this.enabled = enabled;

        if (enabled) {
            log.info("Local face verification active (OpenCV SFace + DNN detector)");
        } else {
            log.info("Local face verification disabled via configuration");
        }
    }

    @Override
    public String name() {
        return "local-opencv-sface";
    }

    @Override
    public boolean isAvailable() {
        if (!enabled) {
            return false;
        }
        try {
            modelManager.faceDetector();
            modelManager.faceRecognizer();
            return true;
        } catch (Exception e) {
            log.warn("Local face verifier unavailable: {}", e.getMessage());
            return false;
        }
    }

    @Override
    public FaceMatchResult compare(byte[] documentImage, byte[] liveCapture) throws Exception {
        long start = System.nanoTime();

        Mat docMat = imdecode(new Mat(documentImage), IMREAD_COLOR);
        Mat liveMat = imdecode(new Mat(liveCapture), IMREAD_COLOR);

        if (docMat == null || docMat.empty()) {
            return new FaceMatchResult(0.0, false, false, name(),
                    Map.of("error", "Could not decode document image"));
        }
        if (liveMat == null || liveMat.empty()) {
            return new FaceMatchResult(0.0, false, false, name(),
                    Map.of("error", "Could not decode live capture image"));
        }

        try {
            Mat docBgr = ensureBgr(docMat);
            Mat liveBgr = ensureBgr(liveMat);

            List<FaceDetector.DetectedFace> docFaces = faceDetector.detectInFrame(docBgr);
            List<FaceDetector.DetectedFace> liveFaces = faceDetector.detectInFrame(liveBgr);

            boolean docFaceFound = !docFaces.isEmpty();
            boolean liveFaceFound = !liveFaces.isEmpty();

            if (!docFaceFound || !liveFaceFound) {
                return new FaceMatchResult(0.0, docFaceFound, liveFaceFound, name(),
                        Map.of("docFacesDetected", (double) docFaces.size(),
                                "liveFacesDetected", (double) liveFaces.size()));
            }

            FaceDetector.DetectedFace docFace = docFaces.get(0);
            FaceDetector.DetectedFace liveFace = liveFaces.get(0);

            FaceDetector.LivenessResult liveness = faceDetector.checkLiveness(
                    liveBgr, liveFace, liveFaces);

            float[] docEmbedding = extractEmbedding(docBgr, docFace.roi());
            float[] liveEmbedding = extractEmbedding(liveBgr, liveFace.roi());

            if (docEmbedding == null || liveEmbedding == null) {
                return new FaceMatchResult(0.0, docFaceFound, liveFaceFound, name(),
                        Map.of("error", "Could not extract face embeddings"));
            }

            double similarity = cosineSimilarity(docEmbedding, liveEmbedding);

            Map<String, Object> details = new HashMap<>();
            details.put("docFaceConfidence", (double) docFace.confidence());
            details.put("liveFaceConfidence", (double) liveFace.confidence());
            details.put("livenessScore", liveness.score());
            details.put("livenessLive", liveness.live());
            details.put("livenessReasons", liveness.reasons());
            details.put("embeddingDimension", (double) docEmbedding.length);
            details.put("elapsedMs", (double) ((System.nanoTime() - start) / 1_000_000L));

            return new FaceMatchResult(clamp(similarity), docFaceFound, liveFaceFound, name(), details);

        } finally {
            docMat.close();
            liveMat.close();
        }
    }

    private float[] extractEmbedding(Mat image, Rect faceRoi) {
        int padding = (int) (Math.min(faceRoi.width(), faceRoi.height()) * 0.15);
        int x = Math.max(0, faceRoi.x() - padding);
        int y = Math.max(0, faceRoi.y() - padding);
        int w = Math.min(image.cols() - x, faceRoi.width() + 2 * padding);
        int h = Math.min(image.rows() - y, faceRoi.height() + 2 * padding);
        Rect expandedRoi = new Rect(x, y, w, h);

        Mat faceCrop = new Mat(image, expandedRoi);
        Mat resized = new Mat();
        resize(faceCrop, resized, RECOGNITION_SIZE);

        Mat floatMat = new Mat();
        resized.convertTo(floatMat, CV_32F);

        Mat blob = blobFromImage(floatMat, 1.0, RECOGNITION_SIZE, SFACE_MEAN, false, false, CV_32F);

        Net recognizer = modelManager.faceRecognizer();
        recognizer.setInput(blob);
        Mat output = recognizer.forward();

        // Read float data from Mat using BytePointer
        int embeddingSize = (int) output.total();
        float[] embedding = new float[embeddingSize];
        BytePointer ptr = output.data();
        ByteBuffer buffer = ptr.asByteBuffer();
        buffer.order(ByteOrder.nativeOrder());
        for (int i = 0; i < embeddingSize; i++) {
            embedding[i] = buffer.getFloat(i * 4);
        }

        // L2-normalise
        double norm = 0.0;
        for (float v : embedding) {
            norm += v * v;
        }
        norm = Math.sqrt(norm);
        if (norm > 0) {
            for (int i = 0; i < embedding.length; i++) {
                embedding[i] /= norm;
            }
        }

        faceCrop.close();
        resized.close();
        floatMat.close();
        blob.close();
        output.close();

        return embedding;
    }

    private static double cosineSimilarity(float[] a, float[] b) {
        if (a.length != b.length) {
            throw new IllegalArgumentException(
                    "Embedding dimension mismatch: " + a.length + " vs " + b.length);
        }
        double dot = 0.0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
        }
        return (dot + 1.0) / 2.0;
    }

    private static Mat ensureBgr(Mat image) {
        if (image.channels() == 4) {
            Mat bgr = new Mat();
            cvtColor(image, bgr, COLOR_BGRA2BGR);
            return bgr;
        }
        return image;
    }

    private static double clamp(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }
}
