package com.govid.screening.tampering;

import com.govid.screening.domain.RiskFlag;
import com.govid.screening.domain.ScreeningModule;
import com.govid.screening.domain.Severity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Error Level Analysis - detects photo replacement and text manipulation.
 *
 * <p>Every JPEG save quantises the image and introduces a characteristic amount of error.
 * When a region is pasted in from another source and the composite is saved again, that
 * region has been through a different number of compression generations than its
 * surroundings. Re-compressing the whole image at a known quality and measuring the
 * per-pixel difference makes that discrepancy visible: an untouched image errors
 * uniformly, while a spliced one shows a localised bright patch exactly where the
 * replaced photograph or edited text sits.
 *
 * <p>Two properties separate a real finding from noise, and both are required here:
 * the anomalous blocks must be <em>much</em> brighter than the image median, and they
 * must be <em>spatially clustered</em>. Scattered bright blocks are ordinary high-detail
 * content such as printed text or a guilloche pattern.
 *
 * <p>ELA is only meaningful on lossy formats. On a PNG or another lossless input the
 * detector reports nothing rather than producing a meaningless score.
 */
@Component
public class ErrorLevelAnalysisDetector implements TamperingDetector {

    private static final Logger log = LoggerFactory.getLogger(ErrorLevelAnalysisDetector.class);

    /** Quality used for the probe re-compression. */
    private static final float PROBE_QUALITY = 0.90f;

    private static final int BLOCK_SIZE = 16;

    /** A block must exceed this multiple of the image median to be an outlier. */
    private static final double OUTLIER_RATIO = 3.0;

    /** Absolute error floor, so a near-uniform image cannot produce outliers from noise. */
    private static final double OUTLIER_FLOOR = 8.0;

    /** Smallest cluster of adjacent outlier blocks that counts as a suspect region. */
    private static final int MIN_CLUSTER_BLOCKS = 4;

    /**
     * Above this fraction of outlier blocks the "anomaly" covers most of the image,
     * which means the image is simply high-detail or lightly compressed - not spliced.
     */
    private static final double MAX_OUTLIER_FRACTION = 0.20;

    /** Longest edge used for analysis; larger images are downscaled for speed. */
    private static final int MAX_ANALYSIS_EDGE = 1600;

    @Override
    public String name() {
        return "error-level-analysis";
    }

    @Override
    public List<RiskFlag> analyse(ImageEvidence evidence) {
        if (!evidence.hasPixels() || !evidence.isJpeg()) {
            return List.of();
        }
        try {
            return run(evidence.image());
        } catch (Exception e) {
            log.debug("Error level analysis failed", e);
            return List.of();
        }
    }

    private List<RiskFlag> run(BufferedImage source) throws Exception {
        BufferedImage image = ImageUtils.toRgb(ImageUtils.downscale(source, MAX_ANALYSIS_EDGE));
        BufferedImage recompressed = recompress(image);
        if (recompressed == null) {
            return List.of();
        }

        int width = image.getWidth();
        int height = image.getHeight();
        int blocksX = Math.max(1, width / BLOCK_SIZE);
        int blocksY = Math.max(1, height / BLOCK_SIZE);
        if (blocksX < 4 || blocksY < 4) {
            // Too small for the spatial argument to mean anything.
            return List.of();
        }

        double[][] blockError = new double[blocksY][blocksX];
        for (int by = 0; by < blocksY; by++) {
            for (int bx = 0; bx < blocksX; bx++) {
                blockError[by][bx] = meanBlockError(image, recompressed,
                        bx * BLOCK_SIZE, by * BLOCK_SIZE);
            }
        }

        double median = median(blockError);
        double threshold = Math.max(median * OUTLIER_RATIO, OUTLIER_FLOOR);

        boolean[][] outlier = new boolean[blocksY][blocksX];
        int outlierCount = 0;
        for (int by = 0; by < blocksY; by++) {
            for (int bx = 0; bx < blocksX; bx++) {
                if (blockError[by][bx] > threshold) {
                    outlier[by][bx] = true;
                    outlierCount++;
                }
            }
        }

        int totalBlocks = blocksX * blocksY;
        double outlierFraction = (double) outlierCount / totalBlocks;

        Cluster largest = largestCluster(outlier);

        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("medianBlockError", round(median));
        evidence.put("threshold", round(threshold));
        evidence.put("outlierBlocks", outlierCount);
        evidence.put("totalBlocks", totalBlocks);
        evidence.put("outlierFraction", round(outlierFraction));
        evidence.put("largestClusterBlocks", largest.size());

        if (outlierFraction > MAX_OUTLIER_FRACTION) {
            // Widespread high error: a lightly compressed or highly detailed original.
            // Reported as context so the officer console can show why nothing fired.
            return List.of();
        }

        if (largest.size() < MIN_CLUSTER_BLOCKS) {
            return List.of();
        }

        // Express the suspect region in source-image pixel coordinates.
        double scale = (double) source.getWidth() / width;
        evidence.put("regionX", (int) (largest.minX * BLOCK_SIZE * scale));
        evidence.put("regionY", (int) (largest.minY * BLOCK_SIZE * scale));
        evidence.put("regionWidth", (int) ((largest.maxX - largest.minX + 1) * BLOCK_SIZE * scale));
        evidence.put("regionHeight", (int) ((largest.maxY - largest.minY + 1) * BLOCK_SIZE * scale));
        evidence.put("heatmap", ImageUtils.toPngDataUri(
                renderHeatmap(blockError, median, blocksX, blocksY)));

        double areaFraction = (double) largest.size() / totalBlocks;
        Severity severity = areaFraction > 0.02 ? Severity.HIGH : Severity.MEDIUM;

        return List.of(RiskFlag.of("ELA_SPLICE_SUSPECTED", ScreeningModule.TAMPERING_DETECTION,
                severity,
                "A localised region of the image compresses differently from its surroundings, "
                        + "which is the signature of content pasted in from another source - "
                        + "typically a replaced photograph or edited text.",
                evidence));
    }

    /** Re-encodes at a fixed quality so the error introduced is measurable and repeatable. */
    private BufferedImage recompress(BufferedImage image) throws Exception {
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (ImageOutputStream out = ImageIO.createImageOutputStream(buffer)) {
            writer.setOutput(out);
            ImageWriteParam params = writer.getDefaultWriteParam();
            params.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            params.setCompressionQuality(PROBE_QUALITY);
            writer.write(null, new IIOImage(image, null, null), params);
        } finally {
            writer.dispose();
        }
        return ImageIO.read(new ByteArrayInputStream(buffer.toByteArray()));
    }

    /** Mean per-channel absolute difference across one block. */
    private static double meanBlockError(BufferedImage a, BufferedImage b, int originX, int originY) {
        int width = Math.min(BLOCK_SIZE, a.getWidth() - originX);
        int height = Math.min(BLOCK_SIZE, a.getHeight() - originY);
        if (width <= 0 || height <= 0) {
            return 0.0;
        }
        long total = 0;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int p1 = a.getRGB(originX + x, originY + y);
                int p2 = b.getRGB(originX + x, originY + y);
                total += Math.abs(((p1 >> 16) & 0xFF) - ((p2 >> 16) & 0xFF));
                total += Math.abs(((p1 >> 8) & 0xFF) - ((p2 >> 8) & 0xFF));
                total += Math.abs((p1 & 0xFF) - (p2 & 0xFF));
            }
        }
        return (double) total / (width * height * 3);
    }

    private static double median(double[][] values) {
        double[] flat = Arrays.stream(values).flatMapToDouble(Arrays::stream).sorted().toArray();
        if (flat.length == 0) {
            return 0.0;
        }
        int mid = flat.length / 2;
        return flat.length % 2 == 0 ? (flat[mid - 1] + flat[mid]) / 2.0 : flat[mid];
    }

    /** Bounding box and size of one connected group of outlier blocks. */
    private record Cluster(int size, int minX, int minY, int maxX, int maxY) {
        static Cluster empty() {
            return new Cluster(0, 0, 0, 0, 0);
        }
    }

    /**
     * Largest 4-connected group of outlier blocks.
     *
     * <p>Connectivity is what distinguishes a pasted region from scattered high-detail
     * content: a splice is a contiguous patch, printed text is not.
     */
    private static Cluster largestCluster(boolean[][] outlier) {
        int rows = outlier.length;
        int cols = outlier[0].length;
        boolean[][] seen = new boolean[rows][cols];
        Cluster best = Cluster.empty();

        for (int y = 0; y < rows; y++) {
            for (int x = 0; x < cols; x++) {
                if (!outlier[y][x] || seen[y][x]) {
                    continue;
                }
                int size = 0;
                int minX = x;
                int minY = y;
                int maxX = x;
                int maxY = y;

                Deque<int[]> stack = new ArrayDeque<>();
                stack.push(new int[]{x, y});
                seen[y][x] = true;

                while (!stack.isEmpty()) {
                    int[] cell = stack.pop();
                    int cx = cell[0];
                    int cy = cell[1];
                    size++;
                    minX = Math.min(minX, cx);
                    minY = Math.min(minY, cy);
                    maxX = Math.max(maxX, cx);
                    maxY = Math.max(maxY, cy);

                    int[][] neighbours = {{cx + 1, cy}, {cx - 1, cy}, {cx, cy + 1}, {cx, cy - 1}};
                    for (int[] n : neighbours) {
                        int nx = n[0];
                        int ny = n[1];
                        if (nx >= 0 && ny >= 0 && nx < cols && ny < rows
                                && outlier[ny][nx] && !seen[ny][nx]) {
                            seen[ny][nx] = true;
                            stack.push(new int[]{nx, ny});
                        }
                    }
                }

                if (size > best.size()) {
                    best = new Cluster(size, minX, minY, maxX, maxY);
                }
            }
        }
        return best;
    }

    /**
     * Renders the block error map as an image the officer can look at, scaled so the
     * median sits mid-grey and anomalies read as bright.
     */
    private static BufferedImage renderHeatmap(double[][] blockError, double median,
                                               int blocksX, int blocksY) {
        BufferedImage heatmap = new BufferedImage(blocksX, blocksY, BufferedImage.TYPE_INT_RGB);
        double scale = median > 0.5 ? median : 0.5;
        for (int y = 0; y < blocksY; y++) {
            for (int x = 0; x < blocksX; x++) {
                int intensity = (int) Math.min(255, (blockError[y][x] / scale) * 96);
                // Anomalies render red-hot against a dark field.
                int red = intensity;
                int green = (int) (intensity * 0.35);
                int blue = (int) (intensity * 0.15);
                heatmap.setRGB(x, y, (red << 16) | (green << 8) | blue);
            }
        }
        return heatmap;
    }

    private static double round(double value) {
        return Math.round(value * 1000.0) / 1000.0;
    }
}
