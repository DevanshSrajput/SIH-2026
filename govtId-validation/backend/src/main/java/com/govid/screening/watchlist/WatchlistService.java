package com.govid.screening.watchlist;

import com.govid.screening.domain.ExtractedFields;
import com.govid.screening.domain.ModuleResult;
import com.govid.screening.domain.RiskFlag;
import com.govid.screening.domain.ScreeningCase;
import com.govid.screening.domain.ScreeningModule;
import com.govid.screening.domain.Severity;
import com.govid.screening.domain.WatchlistEntry;
import com.govid.screening.repository.ScreeningCaseRepository;
import com.govid.screening.repository.WatchlistRepository;
import com.govid.screening.support.IdentityKeys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Watchlist and cross-case identity screening.
 *
 * <p>Covers the three checkpoint problems that a single document, examined on its own,
 * cannot reveal:
 * <ul>
 *   <li><b>Blacklisted and stolen documents</b> - a hit against the watchlist.</li>
 *   <li><b>One person, several identities</b> - the same face and date of birth arriving
 *       under different document numbers over time.</li>
 *   <li><b>One document, several people</b> - the same document number presented under
 *       different names, which is what a shared or resold document looks like.</li>
 * </ul>
 *
 * <p>The last two are only visible because every screening is written to the case history,
 * which is also what makes the trail available to investigators afterwards.
 */
@Service
public class WatchlistService {

    /** How far back to look when judging whether a document is being reused abnormally. */
    private static final Duration VELOCITY_WINDOW = Duration.ofHours(24);

    private final WatchlistRepository watchlistRepository;
    private final ScreeningCaseRepository caseRepository;
    private final Clock clock;
    private final int velocityThreshold;

    public WatchlistService(WatchlistRepository watchlistRepository,
                            ScreeningCaseRepository caseRepository,
                            Clock clock,
                            @Value("${screening.watchlist.velocity-threshold:3}") int velocityThreshold) {
        this.watchlistRepository = watchlistRepository;
        this.caseRepository = caseRepository;
        this.clock = clock;
        this.velocityThreshold = velocityThreshold;
    }

    public ModuleResult screen(ExtractedFields fields, String currentCaseId) {
        long start = System.nanoTime();

        String documentKey = IdentityKeys.documentNumberKey(fields);
        String identityKey = IdentityKeys.identityKey(fields);

        if (documentKey == null && identityKey == null) {
            return ModuleResult.skipped(ScreeningModule.WATCHLIST,
                    "Neither a usable document number nor a name and date of birth could be "
                            + "established, so no lookup was possible.");
        }

        List<RiskFlag> flags = new ArrayList<>();
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("documentNumberKey", documentKey);
        details.put("identityKey", identityKey);

        checkWatchlist(documentKey, identityKey, flags);
        checkCrossCaseHistory(documentKey, identityKey, currentCaseId, flags, details);

        return new ModuleResult(ScreeningModule.WATCHLIST, ModuleResult.Status.COMPLETED,
                elapsed(start), flags, details,
                flags.isEmpty() ? "No watchlist or history findings" : flags.size() + " finding(s)");
    }

    // ------------------------------------------------------------------
    // Watchlist
    // ------------------------------------------------------------------

    private void checkWatchlist(String documentKey, String identityKey, List<RiskFlag> flags) {
        Set<WatchlistEntry> hits = new LinkedHashSet<>();
        if (documentKey != null) {
            hits.addAll(watchlistRepository.findByDocumentNumberKeyAndActiveIsTrue(documentKey));
        }
        if (identityKey != null) {
            hits.addAll(watchlistRepository.findByIdentityKeyAndActiveIsTrue(identityKey));
        }

        for (WatchlistEntry entry : hits) {
            boolean documentMatch = documentKey != null
                    && documentKey.equals(entry.getDocumentNumberKey());

            Map<String, Object> evidence = new HashMap<>();
            evidence.put("listType", String.valueOf(entry.getListType()));
            evidence.put("source", entry.getSource());
            evidence.put("reason", entry.getReason());
            evidence.put("matchedOn", documentMatch ? "documentNumber" : "identity");
            evidence.put("watchlistEntryId", entry.getId());

            flags.add(RiskFlag.of(
                    documentMatch ? "WATCHLIST_HIT_DOCUMENT" : "WATCHLIST_HIT_IDENTITY",
                    ScreeningModule.WATCHLIST,
                    entry.getSeverity() == null ? Severity.CRITICAL : entry.getSeverity(),
                    describe(entry, documentMatch),
                    evidence));
        }
    }

    private static String describe(WatchlistEntry entry, boolean documentMatch) {
        String subject = documentMatch ? "This document" : "This identity";
        String listType = switch (entry.getListType()) {
            case STOLEN_DOCUMENT -> "is recorded as lost or stolen";
            case REVOKED_DOCUMENT -> "is recorded as revoked or cancelled";
            case ENTRY_BAN -> "is subject to an entry ban";
            case WANTED -> "is wanted by a law-enforcement agency";
            case VISA_OVERSTAY -> "has a recorded visa overstay";
            case LOCAL_INTEREST -> "is flagged by checkpoint intelligence";
        };
        String reason = entry.getReason() == null ? "" : " (" + entry.getReason() + ")";
        return subject + " " + listType + reason + ".";
    }

    // ------------------------------------------------------------------
    // Cross-case history
    // ------------------------------------------------------------------

    private void checkCrossCaseHistory(String documentKey, String identityKey, String currentCaseId,
                                       List<RiskFlag> flags, Map<String, Object> details) {
        Instant since = Instant.now(clock).minus(VELOCITY_WINDOW);

        if (documentKey != null) {
            List<ScreeningCase> priorForDocument = caseRepository.findByDocumentNumberKey(documentKey)
                    .stream()
                    .filter(c -> !Objects.equals(c.getId(), currentCaseId))
                    .toList();
            details.put("priorPresentationsOfDocument", priorForDocument.size());

            Set<String> otherIdentities = priorForDocument.stream()
                    .map(ScreeningCase::getIdentityKey)
                    .filter(Objects::nonNull)
                    .filter(key -> !key.equals(identityKey))
                    .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));

            if (!otherIdentities.isEmpty()) {
                flags.add(RiskFlag.of("DOCUMENT_USED_BY_OTHER_IDENTITY", ScreeningModule.WATCHLIST,
                        Severity.HIGH,
                        "This document number has previously been presented under "
                                + otherIdentities.size() + " different identity/identities.",
                        Map.of("documentNumberKey", documentKey,
                                "otherIdentities", List.copyOf(otherIdentities))));
            }

            long recent = priorForDocument.stream()
                    .filter(c -> c.getCreatedAt() != null && c.getCreatedAt().isAfter(since))
                    .count();
            if (recent >= velocityThreshold) {
                flags.add(RiskFlag.of("DOCUMENT_PRESENTATION_VELOCITY", ScreeningModule.WATCHLIST,
                        Severity.MEDIUM,
                        "This document has been presented " + recent + " times in the last 24 "
                                + "hours, which is unusual for a single traveller.",
                        Map.of("presentations", recent, "windowHours", VELOCITY_WINDOW.toHours())));
            }
        }

        if (identityKey != null) {
            List<ScreeningCase> priorForIdentity = caseRepository.findByIdentityKey(identityKey)
                    .stream()
                    .filter(c -> !Objects.equals(c.getId(), currentCaseId))
                    .toList();
            details.put("priorPresentationsByIdentity", priorForIdentity.size());

            Set<String> otherDocuments = priorForIdentity.stream()
                    .map(ScreeningCase::getDocumentNumberKey)
                    .filter(Objects::nonNull)
                    .filter(key -> !key.equals(documentKey))
                    .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));

            if (!otherDocuments.isEmpty()) {
                flags.add(RiskFlag.of("IDENTITY_USING_MULTIPLE_DOCUMENTS", ScreeningModule.WATCHLIST,
                        Severity.HIGH,
                        "This person has previously presented " + otherDocuments.size()
                                + " other document number(s) under the same name and date of birth.",
                        Map.of("identityKey", identityKey,
                                "otherDocumentNumbers", List.copyOf(otherDocuments))));
            }
        }
    }

    // ------------------------------------------------------------------
    // Watchlist maintenance
    // ------------------------------------------------------------------

    /**
     * Stores a watchlist entry, normalising its lookup keys the same way screening does.
     *
     * <p>Name parts are taken separately rather than parsed out of a display name: word
     * order differs between source systems, and guessing wrong here would silently
     * produce an entry that never matches.
     */
    public WatchlistEntry add(WatchlistEntry entry, String surname, String givenNames) {
        entry.setDocumentNumberKey(IdentityKeys.documentNumberKey(entry.getDocumentNumberKey()));
        if (entry.getIdentityKey() == null) {
            entry.setIdentityKey(IdentityKeys.identityKey(surname, givenNames, entry.getDateOfBirth()));
        }
        return watchlistRepository.save(entry);
    }

    private static long elapsed(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }
}
