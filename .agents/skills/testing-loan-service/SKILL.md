---
name: testing-loan-service
description: Run local end-to-end REST contract checks for the Spring Boot loan service.
---

# Local runtime testing

Use Java 17 and the repository Maven wrapper. There is no frontend or API authentication.

If Maven Central download fails, the wrapper distribution and dependency artifacts need separate mirror configuration. Set `MVNW_REPOURL=https://maven-central.storage-download.googleapis.com/maven2` for wrapper bootstrap; configure a Central mirror in `~/.m2/settings.xml` for dependency resolution. Do not overwrite unrelated Maven settings.

Start with `./mvnw spring-boot:run`. If another instance occupies port 8080, use `-Dspring-boot.run.arguments=--server.port=8081` rather than stopping an unrelated process. Wait for the Tomcat startup message.

The default configuration initializes an isolated in-memory H2 database from the configured SQL scripts on each startup. Test public `/api/loans`, `/api/loans/{id}`, `/api/loans/{id}/payments`, `/api/borrowers`, and `/api/borrowers/{id}` routes over real HTTP.

Compare JSON responses to `src/test/resources/golden/*.json` semantically with exact decimal arithmetic. Preserve array ordering, nulls, field names, and string values; ignore only numerically equivalent number scale. Check external payment IDs and parsed date order explicitly.

Check the characterized missing-resource contract rather than assuming 404: currently unknown loan/borrower details intentionally return 500, while unknown-loan payments return 200 with an empty array.

Chrome can display actual endpoint JSON; its Pretty-print checkbox improves visual evidence but normalizes decimal rendering. Preserve raw HTTP bodies separately when testing monetary precision.

## Devin Secrets Needed

None for the local seeded API.
