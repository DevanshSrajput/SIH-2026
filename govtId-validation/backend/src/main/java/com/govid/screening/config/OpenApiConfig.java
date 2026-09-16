package com.govid.screening.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.servers.Server;
import io.swagger.v3.oas.models.tags.Tag;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * OpenAPI description of the officer API, rendered by Swagger UI at {@code /swagger-ui.html}.
 *
 * <p>The document is generated from the controllers rather than hand-written, so it cannot
 * drift from the deployed API. That matters more here than on an ordinary service: an
 * integrator wiring a checkpoint lane to the wrong field name produces a screening decision
 * made on incomplete evidence, not a compile error.
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI screeningOpenApi(@Value("${spring.application.name:govtid-screening}") String name,
                                    @Value("${server.port:8080}") String port) {
        return new OpenAPI()
                .info(new Info()
                        .title("Fake Identity & Document Screening API")
                        .version("0.1.0")
                        .description("""
                                Border-checkpoint screening of identity and travel documents.

                                A screening runs four modules over one presented document — OCR \
                                extraction, issuing-standard validation, image tampering forensics \
                                and face verification — plus a cross-case watchlist stage, and \
                                returns a risk score with the individual findings that produced it.

                                Two things to know before integrating:

                                * **Every finding is auditable.** A `RiskFlag` carries an `evidence` \
                                map recording what was measured, so a decision can be re-checked \
                                months later.
                                * **A module that cannot run says so.** Modules report `SKIPPED` or \
                                `FAILED` with a reason rather than estimating a plausible score, and \
                                the verdict is never `CLEAR` while a module has `FAILED`. Treat a \
                                missing measurement as missing, not as a pass.
                                """)
                        .contact(new Contact().name("Screening platform team"))
                        .license(new License().name("Internal use")))
                .servers(List.of(
                        new Server().url("http://localhost:" + port).description("Local development"),
                        new Server().url("/").description("Same origin as this document")))
                .tags(List.of(
                        new Tag().name("Screenings")
                                .description("Submit documents, retrieve cases, evidence images "
                                        + "and the audit trail, and record an officer decision."),
                        new Tag().name("Watchlist")
                                .description("Maintain the blacklist of stolen, revoked and "
                                        + "flagged documents and identities."),
                        new Tag().name("Statistics")
                                .description("Shift-level throughput, referral rate and the flag "
                                        + "codes currently driving referrals.")))
                .components(new Components());
    }
}
