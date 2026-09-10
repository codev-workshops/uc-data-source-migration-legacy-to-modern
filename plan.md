# Data Source Migration Plan (legacy CDW -> modern normalized schema)

Repository: `codev-workshops/uc-data-source-migration-jdbc-normalization`. Base branch: `feature/praveen-demo-migration` (cut from `main`). No session may target `main`.

## Orchestration model
A parent (orchestrator) session performs no implementation work. It (1) ensures the base branch exists; (2) spawns child sessions one at a time in the order below; (3) waits for each child's PR to be merged into `feature/praveen-demo-migration` before spawning the next; (4) passes handoff notes between children. Each child creates its own leaf branch off the current tip of `feature/praveen-demo-migration` and opens a PR targeting it. Sessions are strictly sequential (1 -> 2 -> 3 -> 4 -> 5).

## Shared context
The app runs on a single in-memory H2 datasource that only loads `src/main/resources/schema-legacy.sql` and `data-legacy.sql` at startup (see `src/main/resources/application.properties`). The modern target schema exists only as a design file at `data/modern-schema/modern_tables.sql`; the legacy->modern column/type mappings are in `data/mappings/column_mappings.md`. Current transformation logic lives in `src/main/java/com/workshop/loanservice/service/LoanService.java` (parseLegacyAmount/Decimal/Integer, expandStatusCode/PropertyType/PaymentType/PaymentStatus). DTOs (`LoanSummaryDto`, `PaymentDto`, `BorrowerDto`) are the response contract and should stay stable. Endpoints to preserve: GET /api/loans, /api/loans/{id}, /api/loans/{loanId}/payments, /api/borrowers, /api/borrowers/{id}.

## Session 1 — plan + modern entities + schema wiring + golden baseline
- Commit this plan as `plan.md` at the repository root.
- Make the modern schema live at runtime by copying `data/modern-schema/modern_tables.sql` into `src/main/resources/` (e.g. `schema-modern.sql`) and adding it to `spring.sql.init.schema-locations` so both legacy and modern tables exist in the same H2 instance.
- Create JPA entities for `borrowers`, `loan_products`, `loan_accounts`, `payments` in `src/main/java/com/workshop/loanservice/entity/` using proper types (`Long` auto-increment IDs, `LocalDate`, `LocalDateTime`/`Instant`, `BigDecimal`, `Integer`, `Boolean`) and JPA relationships (`@ManyToOne` loan_accounts -> borrowers/loan_products, payments -> loan_accounts). Match columns exactly to `modern_tables.sql`.
- Create Spring Data repositories for the four modern entities in `src/main/java/com/workshop/loanservice/repository/`, with finder methods equivalent to the legacy ones (find loan accounts by borrower, find payments by loan account ordered by payment date desc).
- Before any rewiring, capture golden baseline API responses from the still-legacy app for all five endpoints and commit them under `src/test/resources/golden/` for Session 4.
- Success: `plan.md` present; modern entities compile with proper types and modeled FKs; both schemas initialize at startup; golden baseline files committed.

## Session 2 — migration service
- Add `src/main/java/com/workshop/loanservice/migration/DataMigrationService.java` that reads all legacy records via the Legacy* repositories, transforms per `column_mappings.md`, and inserts into the modern repositories.
- Transforms: `MM/DD/YYYY` -> `LocalDate`/timestamps; strip commas and parse amounts to `BigDecimal`; parse integers; expand codes to UPPERCASE tokens (ACT->ACTIVE, CLO->CLOSED, DFT->DEFAULT, FRB->FORBEARANCE; PROD_STAT_CD->is_active BOOLEAN; property and payment codes per doc).
- FK ordering: borrowers and loan_products first, then loan_accounts (BORR_ID->borrowers.id, PROD_CD->loan_products.id), then payments (LN_ACCT_NBR->loan_accounts.id). Drop denormalized borrower fields on loan accounts.
- Edge cases: null/blank, malformed data, duplicates; log/quarantine bad rows rather than aborting the batch.
- Reconciliation: row counts (5 borrowers, 5 products, 5 loan accounts, 10 payments) and summed amounts match after conversion.

## Session 3 — rewire service layer
- `LoanService.java` depends on modern repositories instead of Legacy*; remove/simplify parse*/expand* helpers.
- Keep DTO shapes and all five endpoints identical; reproduce composite fields exactly (borrowerName, propertyAddress).
- Decide canonical representation for status/type labels and dates and apply consistently (service emits title-case "Active"; mapping doc uses "ACTIVE"); document the choice.
- Add `@ControllerAdvice` translating not-found to 404.

## Session 4 — validation
- Tests replaying all five endpoints against the modern-backed service, diffed against the Session 1 golden files.
- Document intentional differences (date formatting, status casing) and normalize the comparison.
- Reconciliation/integrity assertions: no orphaned FKs; every migrated status/type value within the allowed set.

## Session 5 — docs/cleanup + remove plan
- Remove `plan.md`.
- Add `DATA_SOURCE_MIGRATION_NOTES.md` summarizing the migration and intentional differences.
- Point `application.properties` fully at the modern schema; flag/remove legacy entities and repositories as deprecated (or keep for reference per project preference).
