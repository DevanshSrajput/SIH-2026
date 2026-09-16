# Face Models

This directory holds the pre-trained DNN models used by the local face verification
system (Module 4 - Face Verification).

## Required Models

| File | Purpose | Source |
|---|---|---|
| `opencv_face_detector.caffemodel` | Face detection (Caffe SSD) | OpenCV DNN samples |
| `opencv_face_detector.prototxt` | Face detection model architecture | OpenCV DNN samples |
| `face_recognizer_fast.onnx` | Face recognition (SFace) | OpenCV contrib |

## Download Script

Run the download script from the repository root to fetch all required models:

```bash
cd backend
./mvnw exec:java -Dexec.mainClass="com.govid.screening.face.FaceModelDownloader"
```

Or download manually:

### 1. Face Detector (Caffe)

```bash
# Architecture
curl -L -o src/main/resources/models/face/opencv_face_detector.prototxt \
  https://raw.githubusercontent.com/opencv/opencv/master/samples/dnn/face_detector/deploy.prototxt

# Weights
curl -L -o src/main/resources/models/face/opencv_face_detector.caffemodel \
  https://raw.githubusercontent.com/opencv/opencv_3rdparty/dnn_samples_face_detector_20170830/res10_300x300_ssd_iter_140000.caffemodel
```

### 2. Face Recognizer (SFace)

```bash
curl -L -o src/main/resources/models/face/face_recognizer_fast.onnx \
  https://github.com/opencv/opencv_contrib/raw/master/modules/face/samples/face_recognizer_fast.onnx
```

## Model Sizes

- Face detector: ~10 MB (Caffe model)
- Face recognizer: ~35 MB (ONNX model)

Total: ~45 MB extracted to temp directory on first use.

## Notes

- Models are extracted from classpath to a temp directory on first application startup.
- The default temp directory is `${java.io.tmpdir}/govtid-face-models`.
- Models are cached across application restarts.
- For production, configure `screening.face.models.dir` to point at a persistent directory.
