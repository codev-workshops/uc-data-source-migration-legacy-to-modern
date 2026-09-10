# Golden baseline API responses

Captured in Session 1 of the data source migration, **before** any service rewiring,
from the legacy-backed application (`LoanService` reading `CDW_*` tables via the
`Legacy*` repositories). Session 4 replays the five endpoints against the
modern-backed service and diffs against these files.

## How they were captured

```
mvn -B clean package
java -jar target/loan-service-1.0.0.jar
curl -s localhost:8080/api/loans                      | jq . > loans.json
curl -s localhost:8080/api/borrowers                  | jq . > borrowers.json
curl -s localhost:8080/api/loans/{id}                 | jq . > loans/{id}.json        # each loanAccountNumber in loans.json
curl -s localhost:8080/api/loans/{id}/payments        | jq . > payments/{id}.json     # each loanAccountNumber in loans.json
curl -s localhost:8080/api/borrowers/{id}             | jq . > borrowers/{id}.json    # each id in borrowers.json
```

Bodies are the raw JSON responses, pretty-printed with `jq .` (2-space indent,
key order preserved, numbers unchanged).

## Layout

| File | Endpoint |
|------|----------|
| `loans.json` | `GET /api/loans` |
| `loans/<loanAccountNumber>.json` | `GET /api/loans/{id}` |
| `payments/<loanAccountNumber>.json` | `GET /api/loans/{loanId}/payments` |
| `borrowers.json` | `GET /api/borrowers` |
| `borrowers/<id>.json` | `GET /api/borrowers/{id}` |

## Notes for comparison

- Dates are legacy `MM/DD/YYYY` strings (e.g. `originationDate`, `paymentDate`).
- Status/type labels are title-case expansions (`Active`, `Regular`, `Posted`,
  `Single Family Residence`).
- Numeric fields are JSON numbers with legacy scale (e.g. `285000`, `4.75`).
- List ordering follows legacy `findAll()` (insertion order) for loans/borrowers
  and payment date descending for payments.

## Session 4 validation

`GoldenBaselineApiTest` replays every file here against the modern-backed API;
`MigrationIntegrityTest` checks FK integrity, row counts and allowed code sets.
See `GOLDEN_DIFFERENCES.md` for the documented storage-vs-presentation differences
and the field-specific normalizers used in the comparison.
