"""Liveness / anti-spoofing detection for face verification.

Implements multiple heuristic checks to distinguish a genuine live person from:
- Printed photographs
- Digital screen displays (phones, tablets, monitors)
- Video replays

Each check produces a score in [0, 1] where 1 = likely live. The final liveness
score is a weighted combination of all checks.
"""

import logging
from dataclasses import dataclass, field

import cv2
import numpy as np

from config import settings
from face_detector import DetectedFace

logger = logging.getLogger(__name__)


@dataclass
class LivenessCheck:
    """Result of a single liveness check."""

    name: str
    score: float  # 0.0 = likely spoof, 1.0 = likely live
    passed: bool
    reason: str


@dataclass
class LivenessResult:
    """Aggregated liveness detection result."""

    score: float  # 0.0 - 1.0
    is_live: bool
    checks: list[LivenessCheck] = field(default_factory=list)
    overall_reason: str = ""

    def to_dict(self) -> dict:
        return {
            "score": self.score,
            "is_live": self.is_live,
            "checks": [
                {"name": c.name, "score": c.score, "passed": c.passed, "reason": c.reason}
                for c in self.checks
            ],
            "overall_reason": self.overall_reason,
        }


class LivenessDetector:
    """Multi-signal liveness detection without requiring a dedicated neural network."""

    # Weights for combining individual checks
    CHECK_WEIGHTS = {
        "texture_variance": 0.25,
        "frequency_analysis": 0.25,
        "color_space": 0.15,
        "edge_sharpness": 0.15,
        "face_size_ratio": 0.10,
        "noise_pattern": 0.10,
    }

    def check_liveness(self, image: np.ndarray, face: DetectedFace) -> LivenessResult:
        """
        Run all liveness checks on a detected face.

        Args:
            image: Full BGR image.
            face: Detected face bounding box.

        Returns:
            LivenessResult with aggregated score and individual check results.
        """
        checks: list[LivenessCheck] = []

        x1, y1, x2, y2 = face.expanded_roi(image.shape, padding=0.1)
        face_region = image[y1:y2, x1:x2]

        if face_region.size == 0:
            return LivenessResult(
                score=0.0,
                is_live=False,
                checks=[],
                overall_reason="Empty face region",
            )

        checks.append(self._check_texture_variance(face_region))
        checks.append(self._check_frequency_analysis(face_region))
        checks.append(self._check_color_space(face_region))
        checks.append(self._check_edge_sharpness(face_region))
        checks.append(self._check_face_size_ratio(face, image.shape))
        checks.append(self._check_noise_pattern(face_region))

        # Weighted average
        total_weight = 0.0
        weighted_sum = 0.0
        for check in checks:
            weight = self.CHECK_WEIGHTS.get(check.name, 0.1)
            weighted_sum += check.score * weight
            total_weight += weight

        score = weighted_sum / total_weight if total_weight > 0 else 0.0
        score = max(0.0, min(1.0, score))
        is_live = score >= settings.LIVENESS_THRESHOLD

        failed_checks = [c for c in checks if not c.passed]
        if failed_checks:
            overall_reason = "; ".join(c.reason for c in failed_checks[:3])
        else:
            overall_reason = "All liveness checks passed"

        return LivenessResult(
            score=score,
            is_live=is_live,
            checks=checks,
            overall_reason=overall_reason,
        )

    def _check_texture_variance(self, face_region: np.ndarray) -> LivenessCheck:
        """
        Check texture variance in the face region.

        Printed photos and screen captures tend to have lower texture variance
        than real faces due to halftone patterns, pixel grids, or smoothing.
        """
        gray = cv2.cvtColor(face_region, cv2.COLOR_BGR2GRAY)
        variance = float(np.std(gray))

        # Real faces typically have variance > 25-35
        passed = variance >= settings.LIVENESS_TEXTURE_MIN_VARIANCE
        score = min(1.0, variance / 50.0)

        return LivenessCheck(
            name="texture_variance",
            score=score,
            passed=passed,
            reason=f"Texture variance: {variance:.1f}" + (" (low - possible print/screen)" if not passed else ""),
        )

    def _check_frequency_analysis(self, face_region: np.ndarray) -> LivenessCheck:
        """
        Analyze frequency domain characteristics.

        Printed photographs exhibit periodic patterns (halftone dots) that
        appear as distinct peaks in the frequency domain. Screen captures
        show moiré patterns from pixel grid interference.
        """
        gray = cv2.cvtColor(face_region, cv2.COLOR_BGR2GRAY)
        f = np.fft.fft2(gray.astype(np.float64))
        fshift = np.fft.fftshift(f)
        magnitude = np.abs(fshift)

        h, w = gray.shape
        cy, cx = h // 2, w // 2

        # Create a mask for high-frequency components (exclude DC center)
        y, x = np.ogrid[:h, :w]
        radius_outer = min(h, w) // 4
        radius_inner = min(h, w) // 10
        high_freq_mask = ((x - cx) ** 2 + (y - cy) ** 2 > radius_inner**2) & (
            (x - cx) ** 2 + (y - cy) ** 2 < radius_outer**2
        )

        total_energy = float(np.sum(magnitude**2))
        if total_energy == 0:
            return LivenessCheck(
                name="frequency_analysis",
                score=0.5,
                passed=True,
                reason="Zero energy in frequency domain",
            )

        high_freq_energy = float(np.sum(magnitude[high_freq_mask] ** 2))
        ratio = high_freq_energy / total_energy

        # Real faces have a balanced frequency spectrum.
        # Screen captures have excessive high-frequency energy (moiré).
        # Printed photos may have reduced high-frequency energy.
        suspicious = ratio > 0.4 or ratio < 0.05
        score = 1.0 - min(1.0, abs(ratio - 0.2) / 0.3)

        return LivenessCheck(
            name="frequency_analysis",
            score=max(0.0, min(1.0, score)),
            passed=not suspicious,
            reason=f"High-freq energy ratio: {ratio:.3f}" + (" (suspicious pattern)" if suspicious else ""),
        )

    def _check_color_space(self, face_region: np.ndarray) -> LivenessCheck:
        """
        Analyze color space distribution.

        Real faces have natural skin tone distributions in HSV/LAB space.
        Printed photos often have shifted or compressed color distributions.
        Screen captures may show unnatural color banding.
        """
        hsv = cv2.cvtColor(face_region, cv2.COLOR_BGR2HSV)

        # Check saturation distribution
        saturation = hsv[:, :, 1].astype(np.float64)
        mean_sat = float(np.mean(saturation))
        std_sat = float(np.std(saturation))

        # Real faces: moderate saturation with natural variance
        # Printed photos: often desaturated or artificially saturated
        # Screens: may show banding (low variance in specific channels)
        sat_score = 1.0
        reasons = []

        if mean_sat < 30:
            sat_score -= 0.3
            reasons.append("Low saturation (possible print)")
        elif mean_sat > 180:
            sat_score -= 0.2
            reasons.append("Unusually high saturation")

        if std_sat < 10:
            sat_score -= 0.3
            reasons.append("Low saturation variance (possible screen)")
        elif std_sat > 80:
            sat_score -= 0.1
            reasons.append("High saturation variance")

        # Check hue distribution (skin tones cluster in a specific hue range)
        hue = hsv[:, :, 0].astype(np.float64)
        hue_std = float(np.std(hue))
        if hue_std > 60:
            sat_score -= 0.2
            reasons.append("Wide hue distribution (unnatural)")

        sat_score = max(0.0, min(1.0, sat_score))
        return LivenessCheck(
            name="color_space",
            score=sat_score,
            passed=sat_score >= 0.5,
            reason="; ".join(reasons) if reasons else "Natural color distribution",
        )

    def _check_edge_sharpness(self, face_region: np.ndarray) -> LivenessCheck:
        """
        Analyze edge sharpness and consistency.

        Printed photos have characteristic edge patterns from halftone screening.
        Screen captures may show aliasing from pixel grid misalignment.
        Real faces have natural, consistent edge sharpness.
        """
        gray = cv2.cvtColor(face_region, cv2.COLOR_BGR2GRAY)

        # Laplacian variance as a sharpness measure
        laplacian = cv2.Laplacian(gray, cv2.CV_64F)
        sharpness = float(np.var(laplacian))

        # Sobel gradients
        sobelx = cv2.Sobel(gray, cv2.CV_64F, 1, 0, ksize=3)
        sobely = cv2.Sobel(gray, cv2.CV_64F, 0, 1, ksize=3)
        gradient_magnitude = np.sqrt(sobelx**2 + sobely**2)
        gradient_std = float(np.std(gradient_magnitude))

        # Real faces: moderate sharpness, natural gradient distribution
        # Too sharp = screen capture (pixel edges)
        # Too smooth = heavily processed or printed
        score = 1.0
        reasons = []

        if sharpness > 2000:
            score -= 0.3
            reasons.append("Excessive sharpness (possible screen capture)")
        elif sharpness < 50:
            score -= 0.3
            reasons.append("Very low sharpness (possible print)")

        if gradient_std < 5:
            score -= 0.2
            reasons.append("Low gradient variance")

        score = max(0.0, min(1.0, score))
        return LivenessCheck(
            name="edge_sharpness",
            score=score,
            passed=score >= 0.5,
            reason="; ".join(reasons) if reasons else f"Sharpness: {sharpness:.1f}",
        )

    def _check_face_size_ratio(self, face: DetectedFace, image_shape: tuple) -> LivenessCheck:
        """
        Check if the face occupies a reasonable portion of the image.

        A face that is too small or fills the entire frame may indicate
        someone holding a phone at a distance or a zoomed-in photo.
        """
        image_area = image_shape[0] * image_shape[1]
        face_area = face.width * face.height
        ratio = face_area / image_area if image_area > 0 else 0

        passed = True
        score = 1.0
        reason = f"Face ratio: {ratio:.3f}"

        if ratio < 0.02:
            score = 0.3
            passed = False
            reason += " (very small - possible distant photo)"
        elif ratio > 0.7:
            score = 0.4
            passed = False
            reason += " (fills frame - possible close-up photo)"
        elif ratio < 0.05:
            score = 0.6
            reason += " (small - ensure face is close to camera)"

        return LivenessCheck(
            name="face_size_ratio",
            score=score,
            passed=passed,
            reason=reason,
        )

    def _check_noise_pattern(self, face_region: np.ndarray) -> LivenessCheck:
        """
        Analyze sensor noise patterns.

        Real camera images have consistent sensor noise patterns.
        Printed photos introduce halftone noise; screen captures
        introduce moiré and pixel-grid noise.
        """
        gray = cv2.cvtColor(face_region, cv2.COLOR_BGR2GRAY).astype(np.float64)

        # Extract noise using a high-pass filter
        kernel = np.ones((3, 3), np.float64) / 9
        smoothed = cv2.filter2D(gray, -1, kernel)
        noise = gray - smoothed

        noise_std = float(np.std(noise))
        noise_mean = float(np.mean(np.abs(noise)))

        # Check noise uniformity using block-wise analysis
        h, w = noise.shape
        block_size = max(8, min(h, w) // 8)
        block_stds = []
        for i in range(0, h - block_size, block_size):
            for j in range(0, w - block_size, block_size):
                block = noise[i : i + block_size, j : j + block_size]
                block_stds.append(float(np.std(block)))

        if block_stds:
            uniformity = float(np.std(block_stds)) / (float(np.mean(block_stds)) + 1e-6)
        else:
            uniformity = 0.0

        # Real images have moderate, uniform noise
        score = 1.0
        reasons = []

        if noise_std < 2:
            score -= 0.3
            reasons.append("Very low noise (possible processed image)")
        elif noise_std > 30:
            score -= 0.2
            reasons.append("High noise (possible screen capture)")

        if uniformity > 1.5:
            score -= 0.3
            reasons.append("Non-uniform noise pattern")

        score = max(0.0, min(1.0, score))
        return LivenessCheck(
            name="noise_pattern",
            score=score,
            passed=score >= 0.5,
            reason="; ".join(reasons) if reasons else f"Noise std: {noise_std:.1f}, uniformity: {uniformity:.2f}",
        )


liveness_detector = LivenessDetector()
