package com.govid.screening.config;

import tools.jackson.databind.ObjectMapper;
import com.govid.screening.api.dto.ApiError;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A per-client request cap, to stop one caller exhausting the screening pipeline.
 *
 * <p>Screening is expensive - OCR, four forensic detectors and a neural face comparison
 * per request - so a loop hitting {@code POST /api/screenings} does not merely waste
 * capacity, it stalls the lanes that are processing real travellers. This is the cheap
 * defence: a fixed-window counter per client, held in memory.
 *
 * <p>In-memory is the honest limitation. It is per-instance, so behind a load balancer the
 * effective limit multiplies by the number of instances, and it resets on restart. For a
 * single checkpoint node - which is how this is deployed - that is sufficient. A
 * multi-instance deployment wants a shared counter in Redis, and
 * {@code screening.rate-limit.enabled: false} turns this off in favour of one.
 *
 * <p>Reads are not limited. An officer refreshing a case list is not a threat, and
 * throttling the console mid-shift is a worse failure than the one being prevented.
 */
@Component
@Order(1)
public class RateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);

    private final boolean enabled;
    private final int limit;
    private final long windowMillis;
    private final ObjectMapper objectMapper;

    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    public RateLimitFilter(
            @Value("${screening.rate-limit.enabled:true}") boolean enabled,
            @Value("${screening.rate-limit.requests-per-minute:60}") int requestsPerMinute,
            @Value("${screening.rate-limit.window-seconds:60}") long windowSeconds,
            ObjectMapper objectMapper) {
        this.enabled = enabled;
        this.limit = Math.max(1, requestsPerMinute);
        this.windowMillis = Math.max(1, windowSeconds) * 1000L;
        this.objectMapper = objectMapper;

        if (enabled) {
            log.info("Rate limiting active: {} write requests per {}s per client",
                    this.limit, windowSeconds);
        }
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (!enabled) {
            return true;
        }
        String method = request.getMethod();
        // Only the expensive, state-changing calls are capped.
        if ("GET".equals(method) || "HEAD".equals(method) || "OPTIONS".equals(method)) {
            return true;
        }
        String path = request.getRequestURI();
        return path == null || !path.startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String client = clientKey(request);
        long now = System.currentTimeMillis();

        Window window = windows.compute(client, (key, existing) -> {
            if (existing == null || now - existing.startedAt >= windowMillis) {
                return new Window(now);
            }
            return existing;
        });

        int used = window.count.incrementAndGet();
        long resetIn = Math.max(0, (window.startedAt + windowMillis - now) / 1000);

        response.setHeader("X-RateLimit-Limit", String.valueOf(limit));
        response.setHeader("X-RateLimit-Remaining", String.valueOf(Math.max(0, limit - used)));
        response.setHeader("X-RateLimit-Reset", String.valueOf(resetIn));

        if (used > limit) {
            log.warn("Rate limit exceeded by {} ({} requests in the current window)",
                    client, used);
            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setHeader("Retry-After", String.valueOf(Math.max(1, resetIn)));
            objectMapper.writeValue(response.getOutputStream(), new ApiError(
                    Instant.now(),
                    HttpStatus.TOO_MANY_REQUESTS.value(),
                    HttpStatus.TOO_MANY_REQUESTS.getReasonPhrase(),
                    "Too many requests. The limit is " + limit + " per window; try again in "
                            + Math.max(1, resetIn) + " seconds."));
            return;
        }

        // Keep the map from growing without bound on a long-running node.
        if (windows.size() > 10_000) {
            windows.entrySet().removeIf(entry -> now - entry.getValue().startedAt >= windowMillis);
        }

        chain.doFilter(request, response);
    }

    /**
     * Identifies the caller.
     *
     * <p>{@code X-Forwarded-For} is honoured because this normally runs behind the
     * checkpoint's reverse proxy, where every request otherwise appears to come from the
     * proxy itself and one client's flood would lock out the whole site. On a directly
     * exposed deployment that header is client-controlled and spoofable - which is the
     * trade this takes knowingly, since the counter guards capacity rather than
     * authorising anything.
     */
    private static String clientKey(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            int comma = forwarded.indexOf(',');
            return (comma > 0 ? forwarded.substring(0, comma) : forwarded).trim();
        }
        String remote = request.getRemoteAddr();
        return remote == null ? "unknown" : remote;
    }

    /** One fixed window of counted requests. */
    private static final class Window {

        private final long startedAt;
        private final AtomicInteger count = new AtomicInteger();

        private Window(long startedAt) {
            this.startedAt = startedAt;
        }
    }
}
