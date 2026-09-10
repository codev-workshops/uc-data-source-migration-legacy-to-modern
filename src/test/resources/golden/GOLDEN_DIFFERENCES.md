# Golden baseline vs. modern-backed API (Session 4)

`GoldenBaselineApiTest` replays all 17 golden files (2 list responses + 5 loans +
5 payment histories + 5 borrowers) against the modern-backed service via MockMvc.

## Result

All 17 responses are **JSON-identical** to the Session 1 baseline (same values, same
array order, same numeric scale). `goldenFilesRequireNoNormalizationToday` pins this:
none of the normalizers below is currently exercised.

## Storage vs. presentation

The database now stores modern canonical values (`data/mappings/column_mappings.md`):
UPPERCASE tokens (`ACTIVE`, `SINGLE_FAMILY`, `POSTED`), typed `DATE` columns and
fixed-scale `DECIMAL`s. `service/ApiLabels` maps them back to the legacy presentation
so the DTO contract is unchanged:

| Field(s) | Stored (modern) | Emitted (legacy contract) |
|----------|-----------------|---------------------------|
| loan `status` | `ACTIVE/CLOSED/DEFAULT/FORBEARANCE` | `Active/Closed/Default/Forbearance` |
| loan `propertyType` | `SINGLE_FAMILY/CONDOMINIUM/MULTI_FAMILY/TOWNHOUSE` | `Single Family Residence/Condominium/Multi-Family Residence/Townhouse` |
| payment `type` | `REGULAR/EXTRA/PARTIAL/PREPAYMENT` | `Regular/Extra/Partial/Prepayment` |
| payment `status` | `POSTED/REVERSED/NSF/PENDING` | `Posted/Reversed/Non-Sufficient Funds/Pending` |
| `originationDate`, `paymentDate` | `DATE` | `MM/dd/yyyy` string |
| amounts / `interestRate` | `DECIMAL(12,2)` / `DECIMAL(5,3)` | trailing zeros stripped (`285000`, `4.75`); null -> `0` |

## Normalizers applied by the test (documented fields only)

Only these fields are normalized before comparison, on **both** sides, so the test keeps
passing if a future session flips the API to the modern presentation:

- `originationDate`, `paymentDate`: parsed as `MM/dd/yyyy` or ISO `yyyy-MM-dd`, compared as dates.
- `status`, `propertyType`, `type`: title-case label mapped to its UPPERCASE token via the
  table above (unknown labels are upper-cased).

Nothing else is normalized or ignored. Numbers are compared as `BigDecimal` values
(`285000` == `285000.00`), array order is significant, key order is not.

## Intentional behaviour changes (not covered by golden files)

- `GET /api/loans/{unknown}/payments` returns `404` (legacy returned `200 []`).
  `GET /api/loans/{unknown}` and `/api/borrowers/{unknown}` return RFC 7807 `404` instead
  of a 500. Covered by `unknownIdsReturn404ProblemDetail`.
- Loan `borrowerName` derives from the borrower FK (`borrowers.first_name + last_name`)
  instead of the legacy per-loan snapshot columns; identical for the seeded data.

## Notes for maintainers

- `@AutoConfigureMockMvc` must not be used on these tests: it creates a second cached
  Spring context, which re-runs `schema-*.sql` against the shared `DB_CLOSE_DELAY=-1`
  H2 instance and fails with `Table "CDW_BORR_MSTR" already exists`. Build MockMvc from
  the shared `WebApplicationContext` instead.
- The Session 3 handoff mentioned 22 golden files; the committed baseline has 17.
