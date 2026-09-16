package com.govid.screening.face;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link FaceModelManager} model extraction and caching logic.
 *
 * <p>These tests verify the resource extraction mechanism without requiring the actual
 * model files to be present. The model files are large binary blobs that are downloaded
 * separately.
 */
class FaceModelManagerTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("model manager creates the model directory on init")
    void createsModelDirectory() {
        Path modelDir = tempDir.resolve("models");
        FaceModelManager manager = new FaceModelManager(
                modelDir.toString(),
                "opencv_face_detector.caffemodel",
                "opencv_face_detector.prototxt",
                "face_recognizer_fast.onnx");

        manager.init();

        assertThat(modelDir).exists();
        assertThat(modelDir).isDirectory();
    }

    @Test
    @DisplayName("model manager handles existing directory gracefully")
    void handlesExistingDirectory() {
        FaceModelManager manager = new FaceModelManager(
                tempDir.toString(),
                "opencv_face_detector.caffemodel",
                "opencv_face_detector.prototxt",
                "face_recognizer_fast.onnx");

        // Should not throw
        manager.init();
        assertThat(tempDir).exists();
    }

    @Test
    @DisplayName("local face verifier reports name correctly")
    void verifierName() {
        FaceModelManager manager = new FaceModelManager(
                tempDir.toString(),
                "opencv_face_detector.caffemodel",
                "opencv_face_detector.prototxt",
                "face_recognizer_fast.onnx");

        FaceDetector detector = new FaceDetector(manager);
        LocalFaceVerifier verifier = new LocalFaceVerifier(manager, detector, true);

        assertThat(verifier.name()).isEqualTo("local-opencv-sface");
    }

    @Test
    @DisplayName("local face verifier is unavailable when disabled")
    void verifierUnavailableWhenDisabled() {
        FaceModelManager manager = new FaceModelManager(
                tempDir.toString(),
                "opencv_face_detector.caffemodel",
                "opencv_face_detector.prototxt",
                "face_recognizer_fast.onnx");

        FaceDetector detector = new FaceDetector(manager);
        LocalFaceVerifier verifier = new LocalFaceVerifier(manager, detector, false);

        assertThat(verifier.isAvailable()).isFalse();
    }
}
