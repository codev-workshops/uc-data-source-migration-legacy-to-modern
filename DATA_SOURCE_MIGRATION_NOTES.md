# Data Source Migration Notes (legacy CDW -> modern normalized schema)

Summary of the five-step migration delivered on `feature/praveen-demo-migration`
(PRs #95 entities/schema/golden baseline, #96 migration service, #97 service rewire,
#98 golden validation, this PR docs/cleanup).

## Architecture

### Before

- Single in-memory H2 (`legacydw`) loading only `schema-legacy.sql` + `data-legacy.sql`.
- Four all-VARCHAR CDW tables: `CDW_BORR_MSTR`, `CDW_LN_PROD`, `CDW_LN_ACCT` (wide, with
  embedded borrower snapshot), `CDW_PMT_HIST`. No foreign keys; joins done in Java.
- `LoanService` read `Legacy*` repositories and parsed strings on every request
  (`parseLegacyAmount`, `expandStatusCode`, ...).

### After

- Single in-memory H2 (`loandb`) loading `schema-modern.sql` (runtime) and
  `schema-legacy.sql` + `data-legacy.sql` (migration input only). `ddl-auto=validate`.
- Modern JPA entities `Borrower`, `LoanProduct`, `LoanAccount`, `Payment` (`entity/`) with
  `Long` identity PKs, `LocalDate`/`LocalDateTime`, `BigDecimal`, `Integer`, `Boolean` and
  `@ManyToOne` relationships; repositories in `repository/`.
- `LoanService` depends only on the modern repositories. `service/ApiLabels` renders stored
  values in the legacy presentation so the DTO contract is unchanged.
- `web/ResourceNotFoundException` + `web/GlobalExceptionHandler` map unknown IDs to RFC 7807
  `404` responses.
- `Legacy*` entities/repositories are `@Deprecated`; their only consumer is
  `migration/DataMigrationService`.

### Cleanup decision (this PR)

Option (a) was chosen: keep the legacy schema/seed SQL loading as the migration input and
mark `Legacy*` types `@Deprecated`, rather than replacing startup migration with a
`data-modern.sql` seed. The README frames the project as a migration workshop where the
legacy data is the system of record; keeping the runner exercised at startup keeps the
transform and reconciliation code honest. Switching to a pure modern seed is a small follow-up
(add `data-modern.sql`, drop `schema-legacy.sql`/`data-legacy.sql` from
`spring.sql.init.*`, delete `Legacy*` and `migration/`).

## Migration service

`migration/DataMigrationService` is an `ApplicationRunner`:

- Idempotent: skips if any modern table already holds rows.
- Inserts in FK order: borrowers -> loan_products -> loan_accounts -> payments.
- Each row is saved in its own `REQUIRES_NEW` transaction, so a database-rejected row cannot
  roll back accepted rows.
- Duplicate business keys (`BORR_ID`, `PROD_CD`, `LN_ACCT_NBR`, `PMT_SEQ_NBR`) are skipped and
  counted; rows failing validation/transformation are quarantined in `MigrationReport` with the
  reason instead of aborting the batch.
- Logs the `MigrationReport` (per-table source/migrated/dupes/quarantined/target counts and
  amount sums) at INFO when reconciled, WARN otherwise.

## Transforms (`migration/LegacyValueParser`, per `data/mappings/column_mappings.md`)

| Legacy | Modern |
|--------|--------|
| `MM/DD/YYYY` string | `LocalDate` (strict); `*_DT` audit columns -> `LocalDateTime` at start of day |
| `"1,500,000.00"` | `BigDecimal` (commas stripped) |
| numeric string | `Integer` |
| `PROD_STAT_CD` `ACT`/other | `is_active` `true`/`false` |
| `ACT/CLO/DFT/FRB` | `ACTIVE/CLOSED/DEFAULT/FORBEARANCE` |
| borrower `ACT/INA` | `ACTIVE/INACTIVE` |
| `SFR/CND/MFR/TWN` | `SINGLE_FAMILY/CONDOMINIUM/MULTI_FAMILY/TOWNHOUSE` |
| `REG/EXT/PRT/PRE` | `REGULAR/EXTRA/PARTIAL/PREPAYMENT` |
| `PST/REV/NSF/PND` | `POSTED/REVERSED/NSF/PENDING` |

Null/blank input yields `null`; malformed input throws and quarantines the row. Unknown codes
pass through upper-cased. Denormalized borrower columns on `CDW_LN_ACCT` are dropped.

## FK resolution

Business keys are kept as `external_id` / `code` / `account_number` on the modern rows. During
migration the service builds in-memory maps (`BORR_ID -> Borrower`, `PROD_CD -> LoanProduct`,
`LN_ACCT_NBR -> LoanAccount`) as parents are inserted; a child referencing a missing parent is
quarantined with `Unresolved FK <column>`.

## Reconciliation

For each table the report compares source row count vs target row count and the sum of one
amount column (`annual_income`, `max_amount`, `original_amount`, `total_amount`). The seeded
data reconciles at 5 borrowers / 5 products / 5 loan accounts / 10 payments with equal sums and
zero quarantined rows. `MigrationIntegrityTest` asserts this plus no orphaned FKs and that
every stored status/type is in the allowed set.

## Presentation (storage vs API)

| Field(s) | Stored | Emitted |
|----------|--------|---------|
| loan `status` | `ACTIVE/CLOSED/DEFAULT/FORBEARANCE` | `Active/Closed/Default/Forbearance` |
| loan `propertyType` | `SINGLE_FAMILY/...` | `Single Family Residence/...` |
| payment `type` / `status` | `REGULAR/...`, `POSTED/...` | `Regular/...`, `Posted/Non-Sufficient Funds/...` |
| dates | `DATE` | `MM/dd/yyyy` |
| amounts | `DECIMAL` | trailing zeros stripped; `null` -> `0` |

## Test strategy (33 tests)

- `GoldenBaselineApiTest`: replays 17 golden files (`src/test/resources/golden/`) captured from
  the legacy app before rewiring; all are JSON-identical today. Normalizers for dates and label
  casing exist but are not exercised (see `src/test/resources/golden/GOLDEN_DIFFERENCES.md`).
- `MigrationIntegrityTest`, `DataMigrationServiceTest`, `LegacyValueParserTest`: reconciliation,
  quarantine behaviour and transform edge cases.
- Gotcha: tests must share one Spring context. A second context re-runs `schema-*.sql` against
  the `DB_CLOSE_DELAY=-1` H2 and fails with "table already exists"; build MockMvc from the shared
  `WebApplicationContext` rather than `@AutoConfigureMockMvc`.

## Intentional differences from the legacy app

- `GET /api/loans/{unknown}/payments` returns `404` (legacy: `200 []`).
- `GET /api/loans/{unknown}` and `GET /api/borrowers/{unknown}` return RFC 7807 `404`
  (legacy: `500`).
- Loan `borrowerName` is derived from the borrower FK (`first_name + last_name`) instead of
  the per-loan snapshot columns; identical for the seeded data.
- Status/type codes are stored as UPPERCASE tokens; the API still emits title-case labels.
