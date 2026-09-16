package com.govid.screening.api;

import com.govid.screening.domain.RiskFlag;
import com.govid.screening.domain.ScreeningCase;
import com.govid.screening.repository.ScreeningCaseRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Checkpoint-level statistics.
 *
 * <p>Supports the shift-level questions a supervisor actually asks: how many people came
 * through, how many were referred, how long screening is taking, and which findings are
 * driving referrals right now - a spike in one flag code is often the first sign of a
 * forgery batch circulating.
 */
@RestController
@RequestMapping("/api/stats")
@Tag(name = "Statistics",
        description = "Shift-level throughput, referral rate and the flag codes currently "
                + "driving referrals.")
public class StatsController {

    private final ScreeningCaseRepository caseRepository;
    private final Clock clock;

    public StatsController(ScreeningCaseRepository caseRepository, Clock clock) {
        this.caseRepository = caseRepository;
        this.clock = clock;
    }

    @Operation(summary = "Checkpoint statistics over a rolling window",
            description = """
                    Counts by verdict and document type, the ten most frequent flag codes, \
                    referral rate, and processing times.

                    Processing time is reported as a median, not a mean: one pathological image \
                    must not distort the number a supervisor uses to judge whether lanes are \
                    keeping up.""")
    @GetMapping
    public Map<String, Object> stats(
            @Parameter(description = "Size of the window, in hours ending now. Minimum 1.")
            @RequestParam(defaultValue = "24") int windowHours) {
        Instant since = Instant.now(clock).minus(Duration.ofHours(Math.max(1, windowHours)));
        List<ScreeningCase> recent = caseRepository.findByCreatedAtAfter(since);

        Map<String, Long> byVerdict = recent.stream()
                .filter(c -> c.getRisk() != null)
                .collect(Collectors.groupingBy(
                        c -> c.getRisk().verdict().name(), Collectors.counting()));

        Map<String, Long> byDocumentType = recent.stream()
                .filter(c -> c.getDocumentType() != null)
                .collect(Collectors.groupingBy(
                        c -> c.getDocumentType().name(), Collectors.counting()));

        Map<String, Long> topFlags = recent.stream()
                .filter(c -> c.getRisk() != null)
                .flatMap(c -> c.getRisk().flags().stream())
                .collect(Collectors.groupingBy(RiskFlag::code, Collectors.counting()))
                .entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .limit(10)
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue,
                        (a, b) -> a, LinkedHashMap::new));

        // Median rather than mean: one pathological image must not distort the headline
        // number a supervisor uses to judge whether lanes are keeping up.
        List<Long> durations = recent.stream()
                .map(ScreeningCase::getProcessingMillis)
                .filter(millis -> millis > 0)
                .sorted()
                .toList();
        Long medianMillis = durations.isEmpty() ? null : durations.get(durations.size() / 2);

        long referred = recent.stream()
                .filter(c -> c.getRisk() != null)
                .filter(c -> c.getRisk().verdict() != com.govid.screening.domain.Verdict.CLEAR)
                .count();

        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("windowHours", windowHours);
        stats.put("totalScreenings", recent.size());
        stats.put("totalAllTime", caseRepository.count());
        stats.put("referredForReview", referred);
        stats.put("referralRate", recent.isEmpty()
                ? 0.0 : Math.round((double) referred / recent.size() * 1000) / 1000.0);
        stats.put("medianProcessingMillis", medianMillis);
        stats.put("slowestProcessingMillis", durations.isEmpty()
                ? null : durations.get(durations.size() - 1));
        stats.put("byVerdict", byVerdict);
        stats.put("byDocumentType", byDocumentType);
        stats.put("topFlags", topFlags);
        stats.put("highestRiskCases", recent.stream()
                .filter(c -> c.getRisk() != null)
                .sorted(Comparator.comparingInt((ScreeningCase c) -> c.getRisk().score()).reversed())
                .limit(5)
                .map(c -> Map.of(
                        "caseReference", c.getCaseReference(),
                        "score", c.getRisk().score(),
                        "verdict", c.getRisk().verdict().name()))
                .toList());

        return stats;
    }
}
