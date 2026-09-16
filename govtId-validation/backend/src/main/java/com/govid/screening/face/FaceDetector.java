package com.govid.screening.face;

import org.bytedeco.javacpp.indexer.DoubleIndexer;
import org.bytedeco.javacpp.indexer.FloatIndexer;
import org.bytedeco.opencv.opencv_core.*;
import org.bytedeco.opencv.opencv_dnn.Net;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

import static org.bytedeco.opencv.global.opencv_core.*;
import static org.bytedeco.opencv.global.opencv_dnn.*;
import static org.bytedeco.opencv.global.opencv_imgproc.*;
import static org.bytedeco.opencv.global.opencv_imgcodecs.*;

/**
 * DNN-based face detector using OpenCV's face detection model (Caffe-based SSD).
 */
@Component
public class FaceDetector {

    private static final Logger log = LoggerFactory.getLogger(FaceDetector.class);

    private static final float CONFIDENCE_THRESHOLD = 0.5f;
    private static final Size INPUT_SIZE = new Size(300, 300);
    private static final Scalar MEAN_VAL = new Scalar(104.0, 177.0, 123.0, 0);

    private final FaceModelManager modelManager;

    public FaceDetector(FaceModelManager modelManager) {
        this.modelManager = modelManager;
    }

    public List<DetectedFace> detect(byte[] imageRaw) {
        Mat image = imdecode(new Mat(imageRaw), IMREAD_COLOR);
        if (image == null || image.empty()) {
            log.warn("Could not decode image for face detection");
            return List.of();
        }

        Mat bgr = new Mat();
        if (image.channels() == 4) {
            cvtColor(image, bgr, COLOR_BGRA2BGR);
        } else {
            bgr = image;
        }

        try {
            return detectInFrame(bgr);
        } finally {
            if (bgr != image) {
                bgr.close();
            }
            image.close();
        }
    }

    public List<DetectedFace> detectInFrame(Mat bgr) {
        Net net = modelManager.faceDetector();

        int width = bgr.cols();
        int height = bgr.rows();

        Mat blob = blobFromImage(bgr, 1.0, INPUT_SIZE, MEAN_VAL, false, false, CV_32F);
        net.setInput(blob);

        Mat detections = net.forward();
        Mat detectionMat = detections.reshape(1, (int) detections.size(3));

        List<DetectedFace> faces = new ArrayList<>();
        FloatIndexer indexer = detectionMat.createIndexer();

        for (int i = 0; i < detectionMat.rows(); i++) {
            float confidence = indexer.get(i, 2);
            if (confidence < CONFIDENCE_THRESHOLD) {
                continue;
            }

            int x1 = (int) (indexer.get(i, 3) * width);
            int y1 = (int) (indexer.get(i, 4) * height);
            int x2 = (int) (indexer.get(i, 5) * width);
            int y2 = (int) (indexer.get(i, 6) * height);

            x1 = Math.max(0, Math.min(x1, width - 1));
            y1 = Math.max(0, Math.min(y1, height - 1));
            x2 = Math.max(x1 + 1, Math.min(x2, width));
            y2 = Math.max(y1 + 1, Math.min(y2, height));

            Rect roi = new Rect(x1, y1, x2 - x1, y2 - y1);
            faces.add(new DetectedFace(roi, confidence));
        }

        blob.close();
        detections.close();
        detectionMat.close();

        faces.sort((a, b) -> Float.compare(b.confidence(), a.confidence()));
        return faces;
    }

    public LivenessResult checkLiveness(Mat image, DetectedFace face, List<DetectedFace> faces) {
        List<String> reasons = new ArrayList<>();
        double score = 1.0;

        Rect roi = face.roi();

        double faceHeightRatio = (double) roi.height() / image.rows();
        if (faceHeightRatio < 0.10) {
            score -= 0.3;
            reasons.add("Face is very small in frame");
        } else if (faceHeightRatio > 0.80) {
            score -= 0.2;
            reasons.add("Face fills most of the frame");
        }

        double aspectRatio = (double) roi.width() / roi.height();
        if (aspectRatio < 0.5 || aspectRatio > 2.0) {
            score -= 0.2;
            reasons.add("Unusual face aspect ratio");
        }

        if (faces.size() > 1) {
            score -= 0.15;
            reasons.add("Multiple faces detected");
        } else if (faces.isEmpty()) {
            return new LivenessResult(0.0, false, List.of("No face detected"));
        }

        Mat faceRegion = new Mat(image, roi);
        Mat meanMat = new Mat();
        Mat stdDev = new Mat();
        meanStdDev(faceRegion, meanMat, stdDev);

        DoubleIndexer stdDevIdx = stdDev.createIndexer();
        double variance = 0.0;
        for (int ch = 0; ch < 3; ch++) {
            variance += stdDevIdx.get(ch);
        }
        variance /= 3.0;

        if (variance < 20.0) {
            score -= 0.25;
            reasons.add("Low texture variance - possible printed photograph");
        }

        stdDev.close();
        meanMat.close();
        faceRegion.close();

        score = Math.max(0.0, Math.min(1.0, score));
        boolean live = score >= 0.5;

        return new LivenessResult(score, live, reasons);
    }

    public record DetectedFace(Rect roi, float confidence) {
    }

    public record LivenessResult(double score, boolean live, List<String> reasons) {
    }
}
