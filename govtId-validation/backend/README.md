# Screening API

Spring Boot 4.1 on Java 21. See [../docs/API.md](../docs/API.md) for the REST reference and
[../docs/ARCHITECTURE.md](../docs/ARCHITECTURE.md) for the design.

## Running

No MongoDB installed? Use the embedded profile — in-process database, discarded on exit:

```bash
./mvnw spring-boot:run -Pembedded-mongo
```

With MongoDB running (`docker compose up -d mongo` from the repo root):

```bash
./mvnw spring-boot:run
```

Health at <http://localhost:8080/actuator/health>.

Swagger UI at <http://localhost:8080/swagger-ui.html>, generated from the controllers, with
the raw OpenAPI 3 document at <http://localhost:8080/v3/api-docs>. Uploads can be tried
straight from the page — the multipart screening endpoint included.

## Tests

```bash
./mvnw test
```

Integration tests run the whole pipeline against an embedded MongoDB, so no Docker daemon
is required.

## Layout

```
src/main/java/com/govid/screening
├── api          REST controllers, DTOs, error handling
├── config       Clock bean, CORS
├── domain       Mongo documents and value records
├── face         Module 4
├── ocr          Module 1 — engines, MrzParser, field extraction
├── pipeline     ScreeningService orchestration, ImageStore
├── repository   Spring Data Mongo repositories
├── risk         RiskEngine
├── support      IdentityKeys, NameNormaliser
├── tampering    Module 3 — forensic detectors
├── validation   Module 2 — rules, country codes
└── watchlist    Blacklist and cross-case identity screening
```
