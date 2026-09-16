package com.govid.screening.support;

import org.springframework.web.multipart.MultipartFile;

import java.util.Set;

/**
 * Validation for uploaded images, applied at the edge of every endpoint that takes one.
 *
 * <p>Checked here rather than left to the decoder deeper in the pipeline. A PDF renamed to
 * {@code .jpg} would otherwise travel through OCR, forensics and face detection before
 * anything noticed, and arrive at the officer as an unexplained empty result several
 * seconds later. Rejecting it at the door produces an error that says what was wrong.
 *
 * <p>The content type is not taken on trust: it comes from the client, and a client that
 * is wrong about it is exactly the case worth catching. The first bytes of the file decide.
 */
public final class Uploads {

    private Uploads() {
    }

    /** Formats the imaging pipeline can actually decode. */
    private static final Set<String> ACCEPTED_CONTENT_TYPES = Set.of(
            "image/jpeg", "image/jpg", "image/png", "image/webp",
            "image/tiff", "image/bmp", "image/heic", "image/heif");

    /**
     * Below this, the payload cannot be a decodable image of a document or a face - it is
     * a truncated upload or an empty file with a header.
     */
    private static final int MINIMUM_PLAUSIBLE_BYTES = 256;

    /**
     * Requires a present, non-empty, decodable image.
     *
     * @param field the part name, so the error names the field the caller got wrong
     * @throws IllegalArgumentException with a message fit to show an officer
     */
    public static void requireImage(MultipartFile file, String field) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("A " + field + " image is required.");
        }
        if (file.getSize() < MINIMUM_PLAUSIBLE_BYTES) {
            throw new IllegalArgumentException(
                    "The " + field + " image is too small to be a real image ("
                            + file.getSize() + " bytes). It may have been truncated in transit.");
        }

        String declared = file.getContentType();
        if (declared != null && !declared.isBlank()
                && !"application/octet-stream".equalsIgnoreCase(declared)
                && !ACCEPTED_CONTENT_TYPES.contains(declared.toLowerCase())) {
            throw new IllegalArgumentException(
                    "The " + field + " upload is a " + declared
                            + ". Supply a JPEG, PNG, WebP, TIFF or BMP image.");
        }

        if (!looksLikeAnImage(file)) {
            throw new IllegalArgumentException(
                    "The " + field + " upload is not a readable image. Its contents do not "
                            + "match any supported image format, whatever its file name says.");
        }
    }

    /** Optional images still have to be valid if they are present at all. */
    public static void validateIfPresent(MultipartFile file, String field) {
        if (file != null && !file.isEmpty()) {
            requireImage(file, field);
        }
    }

    /**
     * Sniffs the magic bytes. Cheap, and it catches the two cases that actually occur: a
     * PDF or an office document renamed to an image extension, and a truncated upload.
     */
    private static boolean looksLikeAnImage(MultipartFile file) {
        byte[] header = new byte[12];
        try (var stream = file.getInputStream()) {
            int read = stream.readNBytes(header, 0, header.length);
            if (read < 4) {
                return false;
            }
        } catch (Exception e) {
            return false;
        }

        // JPEG: FF D8 FF
        if (u(header[0]) == 0xFF && u(header[1]) == 0xD8 && u(header[2]) == 0xFF) {
            return true;
        }
        // PNG: 89 50 4E 47
        if (u(header[0]) == 0x89 && header[1] == 'P' && header[2] == 'N' && header[3] == 'G') {
            return true;
        }
        // GIF87a / GIF89a - decodable even though it is not an accepted upload type.
        if (header[0] == 'G' && header[1] == 'I' && header[2] == 'F') {
            return true;
        }
        // BMP: 42 4D
        if (header[0] == 'B' && header[1] == 'M') {
            return true;
        }
        // TIFF: II* or MM*
        if ((header[0] == 'I' && header[1] == 'I' && u(header[2]) == 0x2A)
                || (header[0] == 'M' && header[1] == 'M' && u(header[3]) == 0x2A)) {
            return true;
        }
        // RIFF....WEBP
        if (header[0] == 'R' && header[1] == 'I' && header[2] == 'F' && header[3] == 'F'
                && header[8] == 'W' && header[9] == 'E' && header[10] == 'B' && header[11] == 'P') {
            return true;
        }
        // ISO-BMFF container (HEIC/HEIF/AVIF): bytes 4-7 are "ftyp".
        return header[4] == 'f' && header[5] == 't' && header[6] == 'y' && header[7] == 'p';
    }

    private static int u(byte value) {
        return value & 0xFF;
    }
}
