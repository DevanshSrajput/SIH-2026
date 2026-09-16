package com.govid.screening.api;

import com.govid.screening.api.dto.ApiError;
import com.govid.screening.api.dto.WatchlistRequest;
import com.govid.screening.domain.AuditEvent;
import com.govid.screening.domain.Severity;
import com.govid.screening.domain.WatchlistEntry;
import com.govid.screening.repository.AuditEventRepository;
import com.govid.screening.repository.WatchlistRepository;
import com.govid.screening.watchlist.WatchlistService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.web.PagedModel;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Maintains the blacklist of stolen, revoked and flagged documents and identities. */
@RestController
@RequestMapping("/api/watchlist")
@Tag(name = "Watchlist",
        description = "Maintain the blacklist of stolen, revoked and flagged documents and "
                + "identities that the cross-case stage screens against.")
public class WatchlistController {

    private final WatchlistService watchlistService;
    private final WatchlistRepository watchlistRepository;
    private final AuditEventRepository auditRepository;

    public WatchlistController(WatchlistService watchlistService,
                               WatchlistRepository watchlistRepository,
                               AuditEventRepository auditRepository) {
        this.watchlistService = watchlistService;
        this.watchlistRepository = watchlistRepository;
        this.auditRepository = auditRepository;
    }

    @Operation(summary = "List watchlist entries, newest first",
            description = "Includes deactivated entries; check the `active` field. `size` is "
                    + "capped at 200.")
    @GetMapping
    public PagedModel<WatchlistEntry> list(
            @Parameter(description = "Zero-based page index.")
            @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "Rows per page. Values above 200 are clamped.")
            @RequestParam(defaultValue = "50") int size) {
        return new PagedModel<>(watchlistRepository.findAllByOrderByAddedAtDesc(
                PageRequest.of(Math.max(0, page), Math.clamp(size, 1, 200))));
    }

    @Operation(summary = "Add a watchlist entry",
            description = "Requires either a document number, or a surname together with a date "
                    + "of birth. A name on its own is too common to match on safely.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The stored entry."),
            @ApiResponse(responseCode = "400",
                    description = "Neither a document number nor a surname with a date of birth "
                            + "was supplied, or a field failed validation.",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ApiError.class)))})
    @PostMapping
    public WatchlistEntry add(@Valid @RequestBody WatchlistRequest request) {
        requireIdentifiable(request);

        WatchlistEntry entry = new WatchlistEntry();
        entry.setDocumentNumberKey(request.documentNumber());
        entry.setDisplayName(displayName(request));
        entry.setNationality(request.nationality());
        entry.setDateOfBirth(request.dateOfBirth());
        entry.setListType(request.listType());
        entry.setSeverity(request.severity() == null ? Severity.CRITICAL : request.severity());
        entry.setReason(request.reason());
        entry.setSource(request.source());
        entry.setAddedBy(request.addedBy());

        WatchlistEntry saved = watchlistService.add(entry, request.surname(), request.givenNames());

        auditRepository.save(new AuditEvent(null, request.addedBy(), "WATCHLIST_ENTRY_ADDED",
                "Added " + saved.getListType() + " entry",
                Map.of("watchlistEntryId", String.valueOf(saved.getId()),
                        "listType", String.valueOf(saved.getListType()))));

        return saved;
    }

    /**
     * Deactivates an entry rather than deleting it.
     *
     * <p>A watchlist is evidence. Removing a row outright would erase the record that a
     * document was ever flagged, and with it the reason any past case was rejected.
     */
    @Operation(summary = "Deactivate a watchlist entry",
            description = """
                    Marks the entry inactive; it is never deleted.

                    A watchlist is evidence. Removing a row outright would erase the record that \
                    a document was ever flagged, and with it the reason any past case was \
                    rejected.""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The deactivated entry."),
            @ApiResponse(responseCode = "400", description = "No such entry.",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ApiError.class)))})
    @DeleteMapping("/{id}")
    public ResponseEntity<WatchlistEntry> deactivate(
            @Parameter(description = "Internal id of the entry.")
            @PathVariable String id,
            @Parameter(description = "Who deactivated it, recorded in the audit trail.")
            @RequestParam(required = false) String actor) {
        WatchlistEntry entry = watchlistRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Unknown watchlist entry " + id));
        entry.setActive(false);
        WatchlistEntry saved = watchlistRepository.save(entry);

        auditRepository.save(new AuditEvent(null, actor, "WATCHLIST_ENTRY_DEACTIVATED",
                "Deactivated watchlist entry",
                Map.of("watchlistEntryId", id)));

        return ResponseEntity.ok(saved);
    }


    @Operation(summary = "Update a watchlist entry",
            description = "Replaces the entry's details. The lookup keys are rebuilt from "
                    + "the supplied name and document number, so correcting a mistyped "
                    + "passport number makes the entry start matching rather than leaving a "
                    + "silent no-op on the list. The original addedAt and addedBy are "
                    + "preserved - who raised the entry and when is not something an edit "
                    + "may rewrite.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The updated entry."),
            @ApiResponse(responseCode = "400", description = "No such entry, or invalid fields.",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ApiError.class)))})
    @PutMapping("/{id}")
    public WatchlistEntry update(
            @Parameter(description = "Internal id of the entry.")
            @PathVariable String id,
            @Valid @RequestBody WatchlistRequest request) {

        requireIdentifiable(request);

        WatchlistEntry entry = watchlistRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Unknown watchlist entry " + id));

        entry.setDocumentNumberKey(request.documentNumber());
        // Cleared so the service recomputes it from the supplied name parts; leaving the
        // old value would keep the entry matching the person it used to describe.
        entry.setIdentityKey(null);
        entry.setDisplayName(displayName(request));
        entry.setNationality(request.nationality());
        entry.setDateOfBirth(request.dateOfBirth());
        entry.setListType(request.listType());
        entry.setSeverity(request.severity() == null ? Severity.CRITICAL : request.severity());
        entry.setReason(request.reason());
        entry.setSource(request.source());

        WatchlistEntry saved = watchlistService.add(entry, request.surname(), request.givenNames());

        auditRepository.save(new AuditEvent(null, request.addedBy(), "WATCHLIST_ENTRY_UPDATED",
                "Updated " + saved.getListType() + " entry",
                Map.of("watchlistEntryId", String.valueOf(saved.getId()),
                        "listType", String.valueOf(saved.getListType()))));

        return saved;
    }

    @Operation(summary = "Reactivate a deactivated entry",
            description = "Puts a withdrawn entry back on the list, for the case where it "
                    + "was stood down in error.")
    @PostMapping("/{id}/reactivate")
    public WatchlistEntry reactivate(
            @Parameter(description = "Internal id of the entry.")
            @PathVariable String id,
            @Parameter(description = "Who reactivated it, recorded in the audit trail.")
            @RequestParam(required = false) String actor) {
        WatchlistEntry entry = watchlistRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Unknown watchlist entry " + id));
        entry.setActive(true);
        WatchlistEntry saved = watchlistRepository.save(entry);

        auditRepository.save(new AuditEvent(null, actor, "WATCHLIST_ENTRY_REACTIVATED",
                "Reactivated watchlist entry", Map.of("watchlistEntryId", id)));
        return saved;
    }

    @Operation(summary = "Import watchlist entries in bulk",
            description = "Takes the same entry shape as the single-entry endpoint, as an "
                    + "array. Rows are applied independently and a bad row does not abort "
                    + "the import: a feed from an upstream agency routinely carries a "
                    + "handful of records that fail validation, and rejecting the whole file "
                    + "over three of them would leave the entire list un-updated. The "
                    + "response says exactly which rows were rejected and why.")
    @PostMapping("/import")
    public ImportResult importEntries(
            @RequestBody List<WatchlistRequest> requests,
            @Parameter(description = "Who ran the import, recorded in the audit trail.")
            @RequestParam(required = false) String actor) {

        if (requests == null || requests.isEmpty()) {
            throw new IllegalArgumentException("The import contains no entries.");
        }
        if (requests.size() > 5000) {
            throw new IllegalArgumentException(
                    "The import contains " + requests.size() + " entries; the limit is 5000 "
                            + "per request. Split the file.");
        }

        List<String> rejected = new ArrayList<>();
        int imported = 0;

        for (int row = 0; row < requests.size(); row++) {
            WatchlistRequest request = requests.get(row);
            try {
                if (request == null) {
                    throw new IllegalArgumentException("the row is empty");
                }
                if (request.listType() == null) {
                    throw new IllegalArgumentException("listType is required");
                }
                requireIdentifiable(request);

                WatchlistEntry entry = new WatchlistEntry();
                entry.setDocumentNumberKey(request.documentNumber());
                entry.setDisplayName(displayName(request));
                entry.setNationality(request.nationality());
                entry.setDateOfBirth(request.dateOfBirth());
                entry.setListType(request.listType());
                entry.setSeverity(request.severity() == null
                        ? Severity.CRITICAL : request.severity());
                entry.setReason(request.reason());
                entry.setSource(request.source());
                entry.setAddedBy(request.addedBy() == null ? actor : request.addedBy());

                watchlistService.add(entry, request.surname(), request.givenNames());
                imported++;
            } catch (RuntimeException e) {
                rejected.add("Row " + (row + 1) + ": " + e.getMessage());
            }
        }

        auditRepository.save(new AuditEvent(null, actor, "WATCHLIST_IMPORTED",
                "Imported " + imported + " watchlist entries",
                Map.of("imported", imported, "rejected", rejected.size())));

        return new ImportResult(requests.size(), imported, rejected.size(), rejected);
    }

    /** Outcome of a bulk import. */
    @Schema(name = "WatchlistImportResult",
            description = "How many rows of a bulk import were applied, and why any were not.")
    public record ImportResult(
            @Schema(description = "Rows in the submitted file.") int submitted,
            @Schema(description = "Rows stored.") int imported,
            @Schema(description = "Rows rejected.") int rejected,
            @Schema(description = "Why each rejected row was rejected.")
            List<String> rejections) {
    }

    @Operation(summary = "Export the full watchlist",
            description = "Every entry, active and withdrawn, as a JSON array. The shape "
                    + "round-trips through the import endpoint.")
    @GetMapping("/export")
    public List<WatchlistEntry> export() {
        return watchlistRepository.findAll();
    }

    /**
     * A watchlist entry has to be findable.
     *
     * <p>A name on its own is not enough to match on: common surnames would stop travellers
     * who share a name with someone on the list, and that is a worse failure than missing a
     * hit, because it happens repeatedly to the same innocent people.
     */
    private static void requireIdentifiable(WatchlistRequest request) {
        if (request.documentNumber() == null
                && (request.surname() == null || request.dateOfBirth() == null)) {
            throw new IllegalArgumentException(
                    "Provide a document number, or a surname together with a date of birth. "
                            + "A name on its own is too common to match on safely.");
        }
    }

    private static String displayName(WatchlistRequest request) {
        if (request.surname() == null && request.givenNames() == null) {
            return null;
        }
        if (request.givenNames() == null) {
            return request.surname();
        }
        if (request.surname() == null) {
            return request.givenNames();
        }
        return request.givenNames() + " " + request.surname();
    }
}
