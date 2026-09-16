package com.govid.screening.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/**
 * The body returned for every handled error.
 *
 * <p>A record rather than an ad-hoc map so the published OpenAPI document describes the
 * failure shape as precisely as the success shape - a caller at a checkpoint has to be able
 * to tell a rejected upload from a screening that never ran.
 */
@Schema(name = "ApiError", description = "Error response returned by every endpoint.")
public record ApiError(
        @Schema(description = "When the error was produced.", example = "2026-08-28T09:15:22.481Z")
        Instant timestamp,

        @Schema(description = "HTTP status code.", example = "400")
        int status,

        @Schema(description = "HTTP status reason phrase.", example = "Bad Request")
        String error,

        @Schema(description = "What went wrong, in terms an officer console can display.",
                example = "A document image is required.")
        String message) {
}
