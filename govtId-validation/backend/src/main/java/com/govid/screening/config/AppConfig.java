package com.govid.screening.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
public class AppConfig {

    /**
     * Injected everywhere a date comparison happens, rather than calling
     * {@code LocalDate.now()} directly. Expiry and validity rules are the core of Module 2
     * and have to be testable at a fixed point in time.
     */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
