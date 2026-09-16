package com.govid.screening.tampering;

import com.govid.screening.domain.RiskFlag;
import com.govid.screening.domain.ScreeningModule;
import com.govid.screening.domain.Severity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Sensor-noise consistency - detects retouched and spliced regions.
 *
 * <p>Every capture device lays down a roughly uniform noise floor across the frame. Two
 * common manipulations disturb it in opposite directions: content pasted from a different
 * source brings that source's noise level with it, and a region that has been smoothed,
 * cloned or healed to hide an edit ends up unnaturally clean.
 *
 * <p>The noise level is estimated per block from the Laplacian residual, using a median
 * absolute deviation so that genuine edges - which are abundant on a printed document -
 * do not inflate the estimate the way a plain variance would.
 *
 * <p>This technique is independent of compression, so it stays useful on PNG and on
 * re-saved images where {@link ErrorLevelAnalysisDetector} has nothing to work with.
 */
@Component
public class NoiseConsistencyDetector implements TamperingDetector {

    private static final Logger log = LoggerFactory.getLogger(NoiseConsistencyDetector.class);

    private static final int BLOCK_SIZE = 32;
    private static final int MAX_ANALYSIS_EDGE = 1200;

    /** Converts a median absolute deviation into a Gaussian sigma estimate. */
    private static final double MAD_TO_SIGMA = 1.4826;

    /** Below this multiple of the image median, a region is suspiciously smooth. */
    private static final double SMOOTH_RATIO = 0.35;

    /** Above this multiple, a region carries foreign or added noise. */
    private static final double NOISY_RATIO = 2.5;

    /** Minimum adjacent anomalous blocks before a finding is raised. */
    private static final int MIN_CLUSTER_BLOCKS = 4;

    /**
     * Above this share of anomalous blocks the image has no localised suspect region,
     * only a naturally uneven noise floor.
     */
    private static final double MAX_ANOMALOUS_FRACTION = 0.15;

    @Override
    public String name() {
        return "noise-consistency";
    }

    @Override
    public List<RiskFlag> analyse(ImageEvidence evidence) {
        if (!evidence.hasPixels()) {
            return List.of();
        }
        try {
            return run(evidence.image());
        } catch (Exception e) {
            log.debug("Noise consistency analysis failed", e);
            return List.of();
        }
    }

    private List<RiskFlag> run(BufferedImage source) {
        BufferedImage image = ImageUtils.toRgb(ImageUtils.downscale(source, MAX_ANALYSIS_EDGE));
        double[][] gray = ImageUtils.toGrayscale(image);

        int height = gray.length;
        int width = gray[0].length;
        int blocksY = height / BLOCK_SIZE;
        int blocksX = width / BLOCK_SIZE;
        if (blocksX < 4 || blocksY < 4) {
            return List.of();
        }

        double[][] sigma = new double[blocksY][blocksX];
        for (int by = 0; by < blocksY; by++) {
            for (int bx = 0; bx < blocksX; bx++) {
                sigma[by][bx] = blockNoiseSigma(gray, bx * BLOCK_SIZE, by * BLOCK_SIZE);
            }
        }

        double median = median(sigma);
        if (median <= 0.01) {
            // A synthetic or heavily flattened image has no noise floor to reason about.
            return List.of();
        }

        boolean[][] smooth = new boolean[blocksY][blocksX];
        boolean[][] noisy = new boolean[blocksY][blocksX];
        for (int by = 0; by < blocksY; by++) {
            for (int bx = 0; bx < blocksX; bx++) {
                smooth[by][bx] = sigma[by][bx] < median * SMOOTH_RATIO;
                noisy[by][bx] = sigma[by][bx] > median * NOISY_RATIO;
            }
        }

        List<RiskFlag> flags = new ArrayList<>();
        double scale = (double) source.getWidth() / width;

        // A tampered region is by definition a minority of the document. When a large
        // share of the image reads as anomalous, the image simply has a noise floor that
        // varies naturally - film grain, heavy print texture, aggressive denoising - and
        // there is no "region" to point an officer at.
        if (fraction(smooth) > MAX_ANOMALOUS_FRACTION || fraction(noisy) > MAX_ANOMALOUS_FRACTION) {
            return List.of();
        }

        int smoothCluster = largestClusterSize(smooth);
        if (smoothCluster >= MIN_CLUSTER_BLOCKS) {
            Map<String, Object> evidence = new LinkedHashMap<>();
            evidence.put("medianSigma", round(median));
            evidence.put("clusterBlocks", smoothCluster);
            evidence.put("blockSizePixels", (int) (BLOCK_SIZE * scale));
            flags.add(RiskFlag.of("NOISE_UNNATURALLY_SMOOTH", ScreeningModule.TAMPERING_DETECTION,
                    Severity.MEDIUM,
                    "A contiguous region carries far less sensor noise than the rest of the "
                            + "image, which is what cloning, healing or airbrushing over an "
                            + "edit leaves behind.",
                    evidence));
        }

        int noisyCluster = largestClusterSize(noisy);
        if (noisyCluster >= MIN_CLUSTER_BLOCKS) {
            Map<String, Object> evidence = new LinkedHashMap<>();
            evidence.put("medianSigma", round(median));
            evidence.put("clusterBlocks", noisyCluster);
            evidence.put("blockSizePixels", (int) (BLOCK_SIZE * scale));
            flags.add(RiskFlag.of("NOISE_FOREIGN_REGION", ScreeningModule.TAMPERING_DETECTION,
                    Severity.MEDIUM,
                    "A contiguous region carries a markedly different noise level from the rest "
                            + "of the image, consistent with content captured by a different "
                            + "device and pasted in.",
                    evidence));
        }

        return flags;
    }

    /**
     * Noise estimate for one block.
     *
     * <p>The Laplacian suppresses smooth gradients and leaves high-frequency content;
     * taking the median absolute value of that residual rather than its variance keeps
     * the estimate from being dominated by the hard edges of printed text.
     */
    private static double blockNoiseSigma(double[][] gray, int originX, int originY) {
        int height = Math.min(BLOCK_SIZE, gray.length - originY);
        int width = Math.min(BLOCK_SIZE, gray[0].length - originX);
        if (height < 3 || width < 3) {
            return 0.0;
        }

        double[] residuals = new double[(height - 2) * (width - 2)];
        int index = 0;
        for (int y = originY + 1; y < originY + height - 1; y++) {
            for (int x = originX + 1; x < originX + width - 1; x++) {
                double laplacian = 4 * gray[y][x]
                        - gray[y - 1][x] - gray[y + 1][x] - gray[y][x - 1] - gray[y][x + 1];
                residuals[index++] = Math.abs(laplacian);
            }
        }

        Arrays.sort(residuals);
        double mad = residuals[residuals.length / 2];
        // The Laplacian of independent noise has roughly sqrt(20) times its sigma.
        return (mad * MAD_TO_SIGMA) / Math.sqrt(20.0);
    }

    private static double median(double[][] values) {
        double[] flat = Arrays.stream(values).flatMapToDouble(Arrays::stream).sorted().toArray();
        if (flat.length == 0) {
            return 0.0;
        }
        int mid = flat.length / 2;
        return flat.length % 2 == 0 ? (flat[mid - 1] + flat[mid]) / 2.0 : flat[mid];
    }

    /** Size of the largest 4-connected group of marked blocks. */
    private static int largestClusterSize(boolean[][] marked) {
        int rows = marked.length;
        int cols = marked[0].length;
        boolean[][] seen = new boolean[rows][cols];
        int best = 0;

        for (int y = 0; y < rows; y++) {
            for (int x = 0; x < cols; x++) {
                if (!marked[y][x] || seen[y][x]) {
                    continue;
                }
                int size = 0;
                java.util.Deque<int[]> stack = new java.util.ArrayDeque<>();
                stack.push(new int[]{x, y});
                seen[y][x] = true;

                while (!stack.isEmpty()) {
                    int[] cell = stack.pop();
                    size++;
                    int[][] neighbours = {
                            {cell[0] + 1, cell[1]}, {cell[0] - 1, cell[1]},
                            {cell[0], cell[1] + 1}, {cell[0], cell[1] - 1}};
                    for (int[] n : neighbours) {
                        if (n[0] >= 0 && n[1] >= 0 && n[0] < cols && n[1] < rows
                                && marked[n[1]][n[0]] && !seen[n[1]][n[0]]) {
                            seen[n[1]][n[0]] = true;
                            stack.push(n);
                        }
                    }
                }
                best = Math.max(best, size);
            }
        }
        return best;
    }

    /** Share of blocks marked anomalous. */
    private static double fraction(boolean[][] marked) {
        int total = 0;
        int hits = 0;
        for (boolean[] row : marked) {
            for (boolean value : row) {
                total++;
                if (value) {
                    hits++;
                }
            }
        }
        return total == 0 ? 0.0 : (double) hits / total;
    }

    private static double round(double value) {
        return Math.round(value * 1000.0) / 1000.0;
    }
}
