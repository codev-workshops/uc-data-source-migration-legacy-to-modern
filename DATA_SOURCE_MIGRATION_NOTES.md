# Data Source Migration Notes

Migration of the loan service from the legacy denormalized CDW-style H2 tables
(`CDW_BORR_MSTR`, `CDW_LN_PROD`, `CDW_LN_ACCT`, `CDW_PMT_HIST`, all-`VARCHAR`) to the
normalized, typed modern schema (`borrowers`, `loan_products`, `loan_accounts`, `payments`).

The public REST API contract is unchanged for all five endpoints:
`GET /api/loans`, `GET /api/loans/{id}`, `GET /api/loans/{loanId}/payments`,
`GET /api/borrowers`, `GET /api/borrowers/{id}`.

## Decisions

1. **API dates stay `MM/DD/YYYY` strings.** The database stores `DATE`/`TIMESTAMP`;
   `LoanService` formats `LocalDate` back to `MM/dd/yyyy` for the DTO fields
   `originationDate` and `paymentDate`, which remain `String`.
2. **API labels are unchanged.** The database stores canonical values from
   `data/mappings/column_mappings.md`; `LoanService` maps them back to the existing labels
   (see the maps below).
3. **Static SQL, no runtime ETL.** The modern data ships as `src/main/resources/schema-modern.sql`
   plus `src/main/resources/data-modern.sql`, initialized by `spring.sql.init.*`. There is no
   `CommandLineRunner` or runtime transformation step.
4. **The legacy datasource is fully replaced.** No dual-read and no feature flag: the cutover was a
   single commit that rewired `LoanService` and switched `spring.sql.init.schema-locations` /
   `data-locations`. The `Legacy*` entities/repositories and `schema-legacy.sql`/`data-legacy.sql`
   were deleted afterwards.
5. **Legacy payment ids are preserved.** `payments.external_id` holds the legacy `PMT_SEQ_NBR`
   (e.g. `PMT-2025120001`) and is what `PaymentDto.paymentId` exposes. The BIGINT surrogate key
   is never exposed by the API.

## Additional constraints honored

- **Not-found behavior preserved.** `getLoanById`/`getBorrowerById` still throw an uncaught
  `RuntimeException`, so Spring still returns HTTP 500 for unknown ids. No `@ControllerAdvice`,
  `@ExceptionHandler`, or `@ResponseStatus` was added; adding 404 handling would have been an
  unrelated behavioral change. The characterization tests assert only the HTTP status — never
  `timestamp`, `trace`, stack traces, or other volatile error-body fields.
- **Ordering is contractual only for payment history.** `GET /api/loans/{loanId}/payments` is
  payment-date descending, backed by
  `PaymentRepository.findByLoanAccountAccountNumberOrderByPaymentDateDesc`. The loan list, the
  borrower list, and the nested `loans` array of `GET /api/borrowers/{id}` were unordered
  (`findAll()`/`findByBorrowerId`) before and remain unordered; the tests compare those arrays
  order-insensitively by a stable key, and no new ordering guarantee was introduced.
- **`payments.external_id VARCHAR(20) NOT NULL UNIQUE`.** All 10 legacy `PMT_SEQ_NBR` values are
  present and unique, so the column is declared non-null and unique.
- **Cutover discipline.** Each modern mapping (date formatting, label maps, name/address assembly,
  external payment id, payment ordering) was implemented before its legacy counterpart was removed,
  and legacy code was deleted only once nothing referenced it. No characterization test was
  weakened or relaxed at any point.

## Canonical database values → API labels

Loan status (`loan_accounts.status`):

| Canonical | API label |
| --- | --- |
| `ACTIVE` | Active |
| `CLOSED` | Closed |
| `DEFAULT` | Default |
| `FORBEARANCE` | Forbearance |

Property type (`loan_accounts.property_type`), the chosen canonical expanded strings:

| Legacy code | Canonical | API label |
| --- | --- | --- |
| `SFR` | `SINGLE_FAMILY_RESIDENCE` | Single Family Residence |
| `CND` | `CONDOMINIUM` | Condominium |
| `MFR` | `MULTI_FAMILY_RESIDENCE` | Multi-Family Residence |
| `TWN` | `TOWNHOUSE` | Townhouse |

Payment type (`payments.type`):

| Canonical | API label |
| --- | --- |
| `REGULAR` | Regular |
| `EXTRA` | Extra |
| `PARTIAL` | Partial |
| `PREPAYMENT` | Prepayment |

Payment status (`payments.status`):

| Canonical | API label |
| --- | --- |
| `POSTED` | Posted |
| `REVERSED` | Reversed |
| `NSF` | Non-Sufficient Funds |
| `PENDING` | Pending |

Unmapped canonical values fall through unchanged and `null` renders as `Unknown`, matching the
legacy `expand*` helpers. Only `ACTIVE`, `SINGLE_FAMILY_RESIDENCE`, `CONDOMINIUM`, `TOWNHOUSE`,
`REGULAR`, and `POSTED` occur in the seed data; the remaining rows of the maps exist because the
legacy helpers covered them.

## Derived (previously denormalized) fields

- `LoanSummaryDto.borrowerName` = `borrowers.first_name + " " + borrowers.last_name` (the legacy
  loan row carried a duplicated copy of the borrower name).
- `BorrowerDto.fullName` = `first_name [middle_initial.] last_name`; the middle initial and its
  trailing period are omitted when `middle_initial` is null (e.g. `Robert Williams`).
- `LoanSummaryDto.propertyAddress` = `property_address + ", " + property_city + ", " +
  property_state + " " + property_zip`.
- `LoanSummaryDto.productDescription` = `loan_products.name`, resolved through the
  `loan_accounts.product_id` foreign key instead of the legacy product-code string.

## Intentional documented differences

- **JSON numeric scale.** Amounts now come from `DECIMAL` columns, so the JSON renders the column
  scale: `285000.00` where the legacy string produced `285000`, and `0.00` for zero late fees. The
  values are numerically identical; the characterization tests compare numbers with
  `BigDecimal.compareTo`, so scale is not part of the contract.
- **Payment history is now chronological across years.** The legacy `PMT_DT` column was an
  `MM/DD/YYYY` `VARCHAR`, so `OrderByPaymentDateDesc` sorted it lexicographically — for a history
  spanning multiple years that put December of every year before November of every year, regardless
  of year. The modern `DATE` column sorts chronologically, which is the intended contract
  ("payment-date descending"). The seed data is entirely within 2025, so no current response
  changes; the legacy lexicographic order is deliberately not reproduced.
- **Null payment components still render as `0`.** `principal_amount`, `interest_amount`,
  `escrow_amount`, and `late_fee` are nullable in the modern schema, while the legacy
  `parseLegacyAmount` turned null/blank into `BigDecimal.ZERO`; `LoanService` coalesces them to zero
  so the API never starts emitting `null` where it used to emit a number.
- Nothing else in the responses changed: property names, JSON types, labels, date strings, payment
  ids, and payment ordering are all identical to the legacy baseline captured in
  `src/test/resources/golden/`.

## Verification

- `./mvnw clean test` — 27 tests green: 18 endpoint characterization tests, 3 not-found tests,
  5 reconciliation tests, and the context-load test. The characterization tests were written
  against the legacy implementation and run unchanged against the modern datasource.
- `DataReconciliationTest` checks row counts (5 borrowers / 5 products / 5 loan accounts /
  10 payments), that every foreign key resolves, that loan amounts and rates equal the legacy
  values after comma-stripping and type conversion, and that all 10 `external_id` values are
  present, unique, and equal to the legacy `PMT_SEQ_NBR` values.
- Manual smoke test of all five endpoints against the modern schema, plus
  `GET /api/loans/LN-DOES-NOT-EXIST` and `GET /api/borrowers/B-99999` both returning HTTP 500.
