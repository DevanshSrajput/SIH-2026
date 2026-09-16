package com.govid.screening.api.dto;

import com.govid.screening.domain.Severity;
import com.govid.screening.domain.WatchlistEntry;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

/**
 * A new watchlist entry.
 *
 * <p>Name parts are taken separately rather than as one display string: the identity key
 * is built from surname plus date of birth, and guessing which word is the surname would
 * produce entries that silently never match.
 */
public record WatchlistRequest(
        @Size(max = 40) String documentNumber,
        @Size(max = 80) String surname,
        @Size(max = 80) String givenNames,
        LocalDate dateOfBirth,
        @Size(max = 3) String nationality,
        @NotNull WatchlistEntry.ListType listType,
        Severity severity,
        @Size(max = 500) String reason,
        @Size(max = 120) String source,
        @Size(max = 120) String addedBy) {
}
