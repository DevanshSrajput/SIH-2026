package com.govid.screening.tampering;

import com.govid.screening.domain.RiskFlag;
import com.govid.screening.domain.ScreeningModule;
import com.govid.screening.domain.Severity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.awt.image.BufferedImage;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Copy-move detection - aimed squarely at stamp forgery.
 *
 * <p>The cheapest way to fake an entry stamp, a visa endorsement or a security feature is
 * to copy a genuine one from elsewhere on the same document and paste it where it is
 * wanted. The duplicated region is pixel-for-pixel identical to its source, which nothing
 * in a genuine photograph reproduces.
 *
 * <p>The search works on overlapping blocks reduced to a coarse signature, so a JPEG
 * re-save after the paste does not break the match. Three safeguards keep the
 * false-positive rate usable on identity documents, which are deliberately covered in
 * repeated printing:
 * <ul>
 *   <li><b>Flat blocks are discarded.</b> Blank paper and uniform background match each
 *       other everywhere and mean nothing.</li>
 *   <li><b>Shift-vector consensus is required.</b> A genuine repeated texture produces
 *       matches scattered at random offsets. A copied region produces many matched pairs
 *       that all share the <em>same</em> displacement.</li>
 *   <li><b>The matches must form a compact, connected region.</b> This is the safeguard
 *       that matters most in practice: guilloche and microprinting <em>do</em> produce
 *       matches at a consistent offset, but they land along thin strokes spread across
 *       the page. A pasted stamp or photograph is a solid, filled patch.</li>
 * </ul>
 */
@Component
public class CopyMoveDetector implements TamperingDetector {

    private static final Logger log = LoggerFactory.getLogger(CopyMoveDetector.class);

    /**
     * Longest edge analysed at native resolution.
     *
     * <p>This detector cannot use ordinary smooth downscaling. A pasted region is exactly
     * equal to its source only in the original pixels; interpolating to a non-integer
     * scale resamples the copy at a different subpixel phase from the source, so the two
     * stop being equal and the duplication becomes invisible. Larger images are therefore
     * decimated by a whole-number factor, which preserves the relationship for
     * displacements that are multiples of that factor.
     */
    private static final int MAX_NATIVE_EDGE = 1200;

    private static final int BLOCK_SIZE = 8;

    /**
     * Blocks are taken at every pixel. A displacement is only discoverable when it is a
     * multiple of the stride, so any stride above 1 would silently miss most real pastes.
     */
    private static final int STRIDE = 1;

    /** Blocks flatter than this carry no distinguishing content. */
    private static final double MIN_BLOCK_VARIANCE = 60.0;

    /** Matches closer together than this are just block overlap, not a copy. */
    private static final int MIN_SHIFT_PIXELS = 12;

    /** Matched pairs that must share one displacement before a finding is raised. */
    private static final int MIN_SHIFT_VOTES = 400;

    /**
     * Largest number of blocks that may share one signature and still be considered.
     *
     * <p>A genuine duplication produces a handful of occurrences of each signature. A
     * signature shared by more blocks than this belongs to repeated security printing,
     * which is evidence of nothing.
     */
    private static final int MAX_BUCKET_SIZE = 6;

    /** Cap on matches retained per shift vector, bounding memory on textured images. */
    private static final int MAX_MATCHES_PER_SHIFT = 60_000;

    /**
     * Side, in pixels, of the cells the shape test works on.
     *
     * <p>Matches are counted per cell rather than per pixel. Within a genuinely duplicated
     * area many individual blocks still fail to match - flat patches are skipped, and a
     * signature shared too widely is discarded - so at pixel resolution even a real paste
     * looks like scattered dots. Grouping into cells recovers the shape of the region
     * without letting the thin, spread-out matches of security printing coalesce.
     */
    private static final int REGION_CELL_PIXELS = 8;

    /** Connected cells required before a region counts as a duplicated area. */
    private static final int MIN_REGION_CELLS = 25;

    /**
     * How much of its bounding box a region must fill. Repeated security printing matches
     * along thin strokes and scores low here; a pasted stamp or photograph fills its box.
     */
    private static final double MIN_REGION_DENSITY = 0.30;

    /**
     * Smallest span, in analysis pixels, that a duplicated area may have on either axis.
     *
     * <p>This is what separates a forged stamp from security printing. Guilloche strokes
     * repeat at a spacing of a few pixels, so they can form small compact clumps that pass
     * the density test; a duplicated stamp, photograph or endorsement is tens of pixels
     * across in both directions. Nothing worth forging is smaller than this.
     */
    private static final int MIN_REGION_EXTENT_PIXELS = 48;

    /** Best-supported shift vectors examined per image, bounding the cost of the search. */
    private static final int MAX_SHIFTS_EXAMINED = 60;

    @Override
    public String name() {
        return "copy-move";
    }

    @Override
    public List<RiskFlag> analyse(ImageEvidence evidence) {
        if (!evidence.hasPixels()) {
            return List.of();
        }
        try {
            return run(evidence.image());
        } catch (Exception e) {
            log.debug("Copy-move analysis failed", e);
            return List.of();
        }
    }

    private List<RiskFlag> run(BufferedImage source) {
        BufferedImage image = decimate(ImageUtils.toRgb(source));
        // Smoothed before matching. A pasted region rarely lands at the same phase
        // relative to the JPEG 8x8 grid as its source, so re-compression quantises the two
        // copies slightly differently. A light blur suppresses those blocking artefacts
        // and leaves the underlying content, which is what actually matches.
        double[][] gray = smooth(ImageUtils.toGrayscale(image));

        int height = gray.length;
        int width = gray[0].length;
        if (width < BLOCK_SIZE * 8 || height < BLOCK_SIZE * 8) {
            return List.of();
        }

        Map<Long, List<int[]>> buckets = new HashMap<>();
        for (int y = 0; y + BLOCK_SIZE <= height; y += STRIDE) {
            for (int x = 0; x + BLOCK_SIZE <= width; x += STRIDE) {
                if (variance(gray, x, y) < MIN_BLOCK_VARIANCE) {
                    continue;
                }
                buckets.computeIfAbsent(signature(gray, x, y), k -> new ArrayList<>())
                        .add(new int[]{x, y});
            }
        }

        // Vote on displacement vectors, keeping where each matching block sat. A copied
        // region makes one vector dominate.
        Map<Long, List<int[]>> shiftMatches = new HashMap<>();
        for (List<int[]> bucket : buckets.values()) {
            // A signature shared by a crowd of blocks is a repeated texture - guilloche,
            // microprinting, a halftone screen - and says nothing about any particular
            // pair. Such buckets are discarded outright rather than truncated: truncating
            // keeps whichever blocks were scanned first and would silently throw away the
            // duplicate being searched for, because a pasted region is scanned last.
            if (bucket.size() < 2 || bucket.size() > MAX_BUCKET_SIZE) {
                continue;
            }
            for (int i = 0; i < bucket.size(); i++) {
                for (int j = i + 1; j < bucket.size(); j++) {
                    int dx = bucket.get(j)[0] - bucket.get(i)[0];
                    int dy = bucket.get(j)[1] - bucket.get(i)[1];
                    if (Math.abs(dx) + Math.abs(dy) < MIN_SHIFT_PIXELS) {
                        continue;
                    }
                    // Normalise direction so a pair votes once, not twice.
                    if (dx < 0 || (dx == 0 && dy < 0)) {
                        dx = -dx;
                        dy = -dy;
                    }
                    long key = (((long) (dx + 4096)) << 20) | (dy + 4096);
                    List<int[]> matches = shiftMatches.computeIfAbsent(key, k -> new ArrayList<>());
                    if (matches.size() < MAX_MATCHES_PER_SHIFT) {
                        matches.add(bucket.get(i));
                    }
                }
            }
        }

        // Every shift with enough support is examined, not just the most popular one.
        //
        // On a real document the highest-voted shift is usually the guilloche pitch, since
        // security printing repeats across the whole page while a forged stamp covers a
        // small part of it. Judging only the top shift would let heavy security printing
        // mask an actual duplication - the exact documents this has to work on.
        Region region = null;
        long bestKey = 0;
        int bestVotes = 0;

        List<Map.Entry<Long, List<int[]>>> candidates = shiftMatches.entrySet().stream()
                .filter(entry -> entry.getValue().size() >= MIN_SHIFT_VOTES)
                .sorted(Comparator.comparingInt(
                        (Map.Entry<Long, List<int[]>> entry) -> entry.getValue().size()).reversed())
                .limit(MAX_SHIFTS_EXAMINED)
                .toList();

        for (Map.Entry<Long, List<int[]>> candidate : candidates) {
            Region found = largestCompactRegion(candidate.getValue(), width, height);
            if (found != null && (region == null || found.size() > region.size())) {
                region = found;
                bestKey = candidate.getKey();
                bestVotes = candidate.getValue().size();
            }
        }

        if (region == null) {
            return List.of();
        }

        long key = bestKey;
        int dx = (int) ((key >> 20) & 0xFFFFF) - 4096;
        int dy = (int) (key & 0xFFFFF) - 4096;
        double scale = (double) source.getWidth() / width;

        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("matchedBlocks", bestVotes);
        evidence.put("regionCells", region.size());
        evidence.put("regionDensity", Math.round(region.density() * 100) / 100.0);
        evidence.put("shiftX", (int) (dx * scale));
        evidence.put("shiftY", (int) (dy * scale));
        evidence.put("regionX", (int) (region.minX() * REGION_CELL_PIXELS * scale));
        evidence.put("regionY", (int) (region.minY() * REGION_CELL_PIXELS * scale));
        evidence.put("regionWidth", (int) (region.pixelWidth() * scale));
        evidence.put("regionHeight", (int) (region.pixelHeight() * scale));

        Severity severity = region.size() >= MIN_REGION_CELLS * 3 ? Severity.HIGH : Severity.MEDIUM;

        return List.of(RiskFlag.of("COPY_MOVE_DUPLICATION", ScreeningModule.TAMPERING_DETECTION,
                severity,
                "A contiguous region of this document appears twice at an identical offset, "
                        + "meaning it was duplicated and pasted elsewhere on the same document. "
                        + "This is the standard method for fabricating an entry stamp or "
                        + "endorsement.",
                evidence));
    }

    /** A connected group of matching blocks, with its bounding box in grid coordinates. */
    private record Region(int size, int minX, int minY, int maxX, int maxY) {

        /** How much of the bounding box the region actually fills. */
        double density() {
            int area = (maxX - minX + 1) * (maxY - minY + 1);
            return area == 0 ? 0.0 : (double) size / area;
        }

        /** Width of the duplicated area in analysis pixels. */
        int pixelWidth() {
            return (maxX - minX + 1) * REGION_CELL_PIXELS;
        }

        int pixelHeight() {
            return (maxY - minY + 1) * REGION_CELL_PIXELS;
        }
    }

    /**
     * Largest connected, solidly filled group among the blocks matching one shift vector.
     *
     * @return the region, or {@code null} when the matches are too few or too sparse to be
     *         anything but repeated printing
     */
    private static Region largestCompactRegion(List<int[]> matches, int width, int height) {
        int cols = Math.max(1, width / REGION_CELL_PIXELS);
        int rows = Math.max(1, height / REGION_CELL_PIXELS);
        boolean[][] grid = new boolean[rows][cols];
        for (int[] match : matches) {
            int x = Math.min(cols - 1, match[0] / REGION_CELL_PIXELS);
            int y = Math.min(rows - 1, match[1] / REGION_CELL_PIXELS);
            grid[y][x] = true;
        }

        boolean[][] seen = new boolean[rows][cols];
        Region best = null;

        for (int y = 0; y < rows; y++) {
            for (int x = 0; x < cols; x++) {
                if (!grid[y][x] || seen[y][x]) {
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
                    size++;
                    minX = Math.min(minX, cell[0]);
                    minY = Math.min(minY, cell[1]);
                    maxX = Math.max(maxX, cell[0]);
                    maxY = Math.max(maxY, cell[1]);

                    int[][] neighbours = {
                            {cell[0] + 1, cell[1]}, {cell[0] - 1, cell[1]},
                            {cell[0], cell[1] + 1}, {cell[0], cell[1] - 1}};
                    for (int[] n : neighbours) {
                        if (n[0] >= 0 && n[1] >= 0 && n[0] < cols && n[1] < rows
                                && grid[n[1]][n[0]] && !seen[n[1]][n[0]]) {
                            seen[n[1]][n[0]] = true;
                            stack.push(n);
                        }
                    }
                }

                Region candidate = new Region(size, minX, minY, maxX, maxY);
                if (candidate.size() >= MIN_REGION_CELLS
                        && candidate.density() >= MIN_REGION_DENSITY
                        && candidate.pixelWidth() >= MIN_REGION_EXTENT_PIXELS
                        && candidate.pixelHeight() >= MIN_REGION_EXTENT_PIXELS
                        && (best == null || candidate.size() > best.size())) {
                    best = candidate;
                }
            }
        }
        return best;
    }

    /** Three-by-three box blur, applied before signatures are computed. */
    private static double[][] smooth(double[][] gray) {
        int height = gray.length;
        int width = gray[0].length;
        double[][] out = new double[height][width];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                double total = 0;
                int count = 0;
                for (int dy = -1; dy <= 1; dy++) {
                    int ny = y + dy;
                    if (ny < 0 || ny >= height) {
                        continue;
                    }
                    for (int dx = -1; dx <= 1; dx++) {
                        int nx = x + dx;
                        if (nx < 0 || nx >= width) {
                            continue;
                        }
                        total += gray[ny][nx];
                        count++;
                    }
                }
                out[y][x] = total / count;
            }
        }
        return out;
    }

    /**
     * Reduces an oversized image by a whole-number factor, sampling pixels rather than
     * blending them.
     *
     * <p>Nearest-neighbour sampling is deliberate: averaging neighbouring pixels would
     * make a copied region and its source differ wherever their positions sit at different
     * phases relative to the sampling grid, and that equality is what this detector
     * depends on.
     */
    private static BufferedImage decimate(BufferedImage source) {
        int longest = Math.max(source.getWidth(), source.getHeight());
        if (longest <= MAX_NATIVE_EDGE) {
            return source;
        }
        int factor = (int) Math.ceil((double) longest / MAX_NATIVE_EDGE);
        int width = source.getWidth() / factor;
        int height = source.getHeight() / factor;

        BufferedImage reduced = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                reduced.setRGB(x, y, source.getRGB(x * factor, y * factor));
            }
        }
        return reduced;
    }

    /**
     * Coarse block signature: the block mean plus its four quadrant means, quantised.
     *
     * <p>Quantisation is what makes the match survive a JPEG re-save after the paste;
     * exact pixel hashing would not.
     */
    private static long signature(double[][] gray, int originX, int originY) {
        int half = BLOCK_SIZE / 2;
        double[] quadrants = new double[4];
        double total = 0;

        for (int y = 0; y < BLOCK_SIZE; y++) {
            for (int x = 0; x < BLOCK_SIZE; x++) {
                double value = gray[originY + y][originX + x];
                total += value;
                int quadrant = (y < half ? 0 : 2) + (x < half ? 0 : 1);
                quadrants[quadrant] += value;
            }
        }

        long signature = quantise(total / (BLOCK_SIZE * BLOCK_SIZE));
        for (double quadrant : quadrants) {
            signature = (signature << 6) | quantise(quadrant / (half * half));
        }
        return signature;
    }

    /** Six-bit quantisation: tolerant of re-compression, still discriminating. */
    private static long quantise(double value) {
        return Math.min(63, Math.max(0, (long) (value / 4.0)));
    }

    private static double variance(double[][] gray, int originX, int originY) {
        double sum = 0;
        double sumSquares = 0;
        int count = BLOCK_SIZE * BLOCK_SIZE;
        for (int y = 0; y < BLOCK_SIZE; y++) {
            for (int x = 0; x < BLOCK_SIZE; x++) {
                double value = gray[originY + y][originX + x];
                sum += value;
                sumSquares += value * value;
            }
        }
        double mean = sum / count;
        return (sumSquares / count) - (mean * mean);
    }
}
