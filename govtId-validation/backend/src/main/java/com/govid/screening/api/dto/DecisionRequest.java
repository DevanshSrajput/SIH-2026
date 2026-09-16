package com.govid.screening.api.dto;

import com.govid.screening.domain.Verdict;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** An officer's own determination, recorded alongside the system recommendation. */
public record DecisionRequest(
        @NotNull Verdict decision,
        @Size(max = 120) String officerId,
        @Size(max = 2000) String notes) {
}
