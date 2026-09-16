package com.govid.screening.domain;

import java.util.List;
import java.util.Map;

/**
 * Outcome of one module run. Modules never throw into the pipeline: a module that
 * cannot run reports {@link Status#FAILED} or {@link Status#SKIPPED} so the remaining
 * modules still produce a decision.
 */
public record ModuleResult(
        ScreeningModule module,
        Status status,
        long durationMillis,
        List<RiskFlag> flags,
        Map<String, Object> details,
        String note) {

    public enum Status { COMPLETED, SKIPPED, FAILED }

    public static ModuleResult skipped(ScreeningModule module, String note) {
        return new ModuleResult(module, Status.SKIPPED, 0L, List.of(), Map.of(), note);
    }

    public static ModuleResult failed(ScreeningModule module, long millis, String note) {
        return new ModuleResult(module, Status.FAILED, millis, List.of(), Map.of(), note);
    }
}
