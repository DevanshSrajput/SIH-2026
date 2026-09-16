package com.govid.screening.tampering;

import com.drew.imaging.ImageMetadataReader;
import com.drew.metadata.Directory;
import com.drew.metadata.Metadata;
import com.drew.metadata.Tag;
import com.drew.metadata.exif.ExifIFD0Directory;
import com.drew.metadata.exif.ExifSubIFDDirectory;
import com.govid.screening.domain.RiskFlag;
import com.govid.screening.domain.ScreeningModule;
import com.govid.screening.domain.Severity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Image metadata analysis - the fourth use case of Module 3.
 *
 * <p>A document photographed at a checkpoint carries the capture device's fingerprint:
 * camera make and model, a single original timestamp, and no editing history. An image
 * that has been through an editor usually carries evidence of it, because writing that
 * evidence is the editor's default behaviour and stripping it is a deliberate extra step.
 *
 * <p>Metadata findings are treated as supporting evidence rather than proof. Legitimate
 * pipelines strip or rewrite metadata all the time - a scanner, a messaging app, or a
 * resize step will do it - which is why absent metadata scores far lower than an explicit
 * editing-software signature.
 */
@Component
public class MetadataForensicsDetector implements TamperingDetector {

    private static final Logger log = LoggerFactory.getLogger(MetadataForensicsDetector.class);

    /** Substrings that identify a raster editor in the EXIF Software tag. */
    private static final List<String> EDITOR_SIGNATURES = List.of(
            "photoshop", "gimp", "paint.net", "affinity", "pixelmator", "lightroom",
            "snapseed", "picsart", "canva", "krita", "corel", "inkscape", "imagemagick",
            "facetune", "remini", "photoroom");

    /** Tolerance between original and modification timestamps before it counts. */
    private static final long TIMESTAMP_TOLERANCE_SECONDS = 60;

    @Override
    public String name() {
        return "metadata-forensics";
    }

    @Override
    public List<RiskFlag> analyse(ImageEvidence evidence) {
        List<RiskFlag> flags = new ArrayList<>();
        Metadata metadata;
        try {
            metadata = ImageMetadataReader.readMetadata(new ByteArrayInputStream(evidence.bytes()));
        } catch (Exception e) {
            log.debug("Metadata could not be read", e);
            flags.add(RiskFlag.of("META_UNREADABLE", ScreeningModule.TAMPERING_DETECTION,
                    Severity.LOW,
                    "Image metadata could not be parsed.",
                    Map.of("reason", String.valueOf(e.getMessage()))));
            return flags;
        }

        ExifIFD0Directory ifd0 = metadata.getFirstDirectoryOfType(ExifIFD0Directory.class);
        ExifSubIFDDirectory subIfd = metadata.getFirstDirectoryOfType(ExifSubIFDDirectory.class);

        String software = ifd0 == null ? null : ifd0.getString(ExifIFD0Directory.TAG_SOFTWARE);
        String make = ifd0 == null ? null : ifd0.getString(ExifIFD0Directory.TAG_MAKE);
        String model = ifd0 == null ? null : ifd0.getString(ExifIFD0Directory.TAG_MODEL);

        checkEditingSoftware(software, flags);
        checkXmpHistory(metadata, flags);
        checkCameraProvenance(make, model, software, flags);
        checkTimestamps(ifd0, subIfd, flags);

        return flags;
    }

    /** An explicit editor signature is the strongest metadata-level signal available. */
    private void checkEditingSoftware(String software, List<RiskFlag> flags) {
        if (software == null || software.isBlank()) {
            return;
        }
        String lower = software.toLowerCase(Locale.ROOT);
        for (String signature : EDITOR_SIGNATURES) {
            if (lower.contains(signature)) {
                flags.add(RiskFlag.of("META_EDITING_SOFTWARE", ScreeningModule.TAMPERING_DETECTION,
                        Severity.HIGH,
                        "The image was last written by image-editing software (" + software
                                + "). A document captured at a checkpoint should carry the "
                                + "capture device's signature, not an editor's.",
                        Map.of("software", software, "matched", signature)));
                return;
            }
        }
    }

    /**
     * XMP carries an explicit edit history. Its presence, and especially a
     * {@code DerivedFrom} reference, means this file is a modified version of another.
     */
    private void checkXmpHistory(Metadata metadata, List<RiskFlag> flags) {
        for (Directory directory : metadata.getDirectories()) {
            if (!directory.getName().toLowerCase(Locale.ROOT).contains("xmp")) {
                continue;
            }
            for (Tag tag : directory.getTags()) {
                String tagName = tag.getTagName().toLowerCase(Locale.ROOT);
                if (tagName.contains("history") || tagName.contains("derivedfrom")) {
                    flags.add(RiskFlag.of("META_EDIT_HISTORY", ScreeningModule.TAMPERING_DETECTION,
                            Severity.HIGH,
                            "The image carries an embedded edit history, meaning it was derived "
                                    + "from another file rather than captured directly.",
                            Map.of("tag", tag.getTagName(),
                                    "value", truncate(tag.getDescription()))));
                    return;
                }
            }
        }
    }

    /**
     * A photograph with no camera identification did not come straight off a camera.
     * Scanners and document-capture kiosks also omit it, so this scores as supporting
     * evidence only.
     */
    private void checkCameraProvenance(String make, String model, String software,
                                       List<RiskFlag> flags) {
        boolean hasCamera = (make != null && !make.isBlank()) || (model != null && !model.isBlank());
        if (hasCamera) {
            return;
        }
        Map<String, Object> evidence = new HashMap<>();
        evidence.put("make", make);
        evidence.put("model", model);
        evidence.put("software", software);
        flags.add(RiskFlag.of("META_NO_CAMERA_INFO", ScreeningModule.TAMPERING_DETECTION,
                Severity.LOW,
                "No camera make or model is recorded. Consistent with a re-saved, scanned or "
                        + "screenshotted image rather than a direct capture.",
                evidence));
    }

    /**
     * A file modified well after it was taken has been through something. The
     * {@code DateTimeOriginal} tag is set by the camera; {@code DateTime} in IFD0 is
     * updated on save.
     */
    private void checkTimestamps(ExifIFD0Directory ifd0, ExifSubIFDDirectory subIfd,
                                 List<RiskFlag> flags) {
        if (ifd0 == null || subIfd == null) {
            return;
        }
        Date original = subIfd.getDate(ExifSubIFDDirectory.TAG_DATETIME_ORIGINAL);
        Date modified = ifd0.getDate(ExifIFD0Directory.TAG_DATETIME);
        if (original == null || modified == null) {
            return;
        }
        long deltaSeconds = TimeUnit.MILLISECONDS.toSeconds(
                Math.abs(modified.getTime() - original.getTime()));
        if (deltaSeconds > TIMESTAMP_TOLERANCE_SECONDS) {
            flags.add(RiskFlag.of("META_TIMESTAMP_INCONSISTENT", ScreeningModule.TAMPERING_DETECTION,
                    Severity.MEDIUM,
                    "The file was modified " + deltaSeconds + " seconds after it was captured.",
                    Map.of("dateTimeOriginal", original.toString(),
                            "dateTimeModified", modified.toString(),
                            "deltaSeconds", deltaSeconds)));
        }
    }

    private static String truncate(String value) {
        if (value == null) {
            return "";
        }
        return value.length() <= 200 ? value : value.substring(0, 200) + "...";
    }
}
