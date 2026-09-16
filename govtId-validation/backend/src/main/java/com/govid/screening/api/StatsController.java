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
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
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
        stats.put("series", series(recent, since, windowHours));
        stats.put("bySeverity", recent.stream()
                .filter(c -> c.getRisk() != null)
                .flatMap(c -> c.getRisk().flags().stream())
                .collect(Collectors.groupingBy(
                        f -> f.severity().name(), Collectors.counting())));
        stats.put("byModule", recent.stream()
                .filter(c -> c.getRisk() != null)
                .flatMap(c -> c.getRisk().flags().stream())
                .collect(Collectors.groupingBy(
                        f -> f.module().name(), Collectors.counting())));
        stats.put("officerAgreement", officerAgreement(recent));
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

    /**
     * Screening volume over time, bucketed so the window always renders as a readable
     * number of columns rather than one bar per hour over a week.
     *
     * <p>Empty buckets are emitted explicitly. A gap in the data and a period with no
     * traffic look identical once the zeroes are dropped, and at a checkpoint those mean
     * very different things - one is a quiet shift, the other is a lane that stopped
     * reporting.
     */
    private List<Map<String, Object>> series(List<ScreeningCase> cases, Instant since,
                                             int windowHours) {
        int buckets = windowHours <= 2 ? 12 : windowHours <= 24 ? 12 : 14;
        long bucketMinutes = Math.max(1, (long) windowHours * 60 / buckets);
        Instant now = Instant.now(clock);

        List<Map<String, Object>> series = new ArrayList<>(buckets);
        for (int i = 0; i < buckets; i++) {
            Instant start = since.plus(Duration.ofMinutes(bucketMinutes * i));
            Instant end = start.plus(Duration.ofMinutes(bucketMinutes));
            if (start.isAfter(now)) {
                break;
            }

            List<ScreeningCase> inBucket = cases.stream()
                    .filter(c -> c.getCreatedAt() != null)
                    .filter(c -> !c.getCreatedAt().isBefore(start) && c.getCreatedAt().isBefore(end))
                    .toList();

            Map<String, Object> point = new LinkedHashMap<>();
            point.put("from", start.truncatedTo(ChronoUnit.MINUTES));
            point.put("to", end.truncatedTo(ChronoUnit.MINUTES));
            point.put("total", inBucket.size());
            point.put("clear", countVerdict(inBucket, com.govid.screening.domain.Verdict.CLEAR));
            point.put("review", countVerdict(inBucket, com.govid.screening.domain.Verdict.REVIEW));
            point.put("reject", countVerdict(inBucket, com.govid.screening.domain.Verdict.REJECT));
            series.add(point);
        }
        return series;
    }

    private static long countVerdict(List<ScreeningCase> cases,
                                     com.govid.screening.domain.Verdict verdict) {
        return cases.stream()
                .filter(c -> c.getRisk() != null && c.getRisk().verdict() == verdict)
                .count();
    }

    /**
     * How often officers agreed with the system's recommendation.
     *
     * <p>Reported because a persistent disagreement is a signal about the system, not the
     * officer. A recommendation that is routinely overridden is one whose thresholds need
     * re-examining, and without this number nobody finds that out.
     */
    private static Map<String, Object> officerAgreement(List<ScreeningCase> cases) {
        List<ScreeningCase> decided = cases.stream()
                .filter(c -> c.getOfficerDecision() != null && c.getRisk() != null)
                .toList();

        long agreed = decided.stream()
                .filter(c -> c.getOfficerDecision() == c.getRisk().verdict())
                .count();

        Map<String, Object> agreement = new LinkedHashMap<>();
        agreement.put("decided", decided.size());
        agreement.put("agreed", agreed);
        agreement.put("overridden", decided.size() - agreed);
        agreement.put("rate", decided.isEmpty()
                ? null : Math.round((double) agreed / decided.size() * 1000) / 1000.0);
        return agreement;
    }
}
