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
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

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
    public Page<WatchlistEntry> list(
            @Parameter(description = "Zero-based page index.")
            @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "Rows per page. Values above 200 are clamped.")
            @RequestParam(defaultValue = "50") int size) {
        return watchlistRepository.findAllByOrderByAddedAtDesc(
                PageRequest.of(page, Math.min(size, 200)));
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
        if (request.documentNumber() == null
                && (request.surname() == null || request.dateOfBirth() == null)) {
            throw new IllegalArgumentException(
                    "Provide a document number, or a surname together with a date of birth. "
                            + "A name on its own is too common to match on safely.");
        }

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
