# Data Source Migration: Legacy to Modern

A small Spring Boot loan management application that has been migrated from a **legacy data warehouse** (CDW-style, all-VARCHAR tables) to a **modern normalized schema**. The API contract is unchanged; the service layer now reads only from the modern tables.

See [`DATA_SOURCE_MIGRATION_NOTES.md`](DATA_SOURCE_MIGRATION_NOTES.md) for the full migration write-up.

## Overview

This app manages loan data: borrowers, loan products, loan accounts, and payment history. Data lives in normalized tables with typed columns (`DATE`, `DECIMAL`, `BOOLEAN`, integer FKs) and clear naming. The legacy CDW tables are still loaded into the same in-memory H2 instance, but only as the *input* of a one-shot startup migration (`migration/DataMigrationService`) that populates the modern tables.

## Architecture

```
┌─────────────────────────────────────────────┐
│   Loan Service (Spring Boot)                │
│                                             │
│  Controllers ─► LoanService ─► ApiLabels    │
│                     │                       │
│         Modern repositories (JPA)           │
│                     │                       │
│         Modern schema (H2)      ◄── RUNTIME │
│   borrowers / loan_products /               │
│   loan_accounts / payments                  │
│                     ▲                       │
│         DataMigrationService (startup)      │
│                     ▲                       │
│         Legacy CDW tables (H2)  ◄── INPUT   │
│   CDW_BORR_MSTR / CDW_LN_PROD /             │
│   CDW_LN_ACCT / CDW_PMT_HIST                │
└─────────────────────────────────────────────┘
```

## Data Source (Modern)

The app connects to normalized tables (`src/main/resources/schema-modern.sql`, design copy in `data/modern-schema/`):
- `borrowers` — Clean borrower records
- `loan_products` — Product catalog
- `loan_accounts` — Loan accounts with foreign keys to borrowers and products
- `payments` — Payment records with a foreign key to loan accounts

Stored codes are UPPERCASE tokens (`ACTIVE`, `SINGLE_FAMILY`, `POSTED`); `service/ApiLabels` maps them back to the legacy API presentation (title-case labels, `MM/dd/yyyy` dates).

## Legacy Input (Migration Only)

`schema-legacy.sql` + `data-legacy.sql` seed the CDW tables (`CDW_BORR_MSTR`, `CDW_LN_PROD`, `CDW_LN_ACCT`, `CDW_PMT_HIST`). `Legacy*` entities and repositories are `@Deprecated` and read only by `DataMigrationService`. See `data/legacy-schema/` for the DDL and `data/mappings/` for column-level mappings.

## Quick Start

```bash
mvn -B spring-boot:run
```

The app runs on `http://localhost:8080` with endpoints:
- `GET /api/loans` — List all loans
- `GET /api/loans/{id}` — Get loan details (404 if unknown)
- `GET /api/loans/{loanId}/payments` — Payment history for a loan (404 if unknown)
- `GET /api/borrowers` — List borrowers
- `GET /api/borrowers/{id}` — Get borrower with loans (404 if unknown)

IDs are formatted strings (e.g. borrower `B-10001`, loan `LN-2019-00142`).

## Build & Test

```bash
mvn -B clean package
mvn -B test
```

Tests replay the API against golden baseline responses captured from the legacy app (`src/test/resources/golden/`) and verify migration integrity (row counts, FK resolution, allowed code sets).

## Tech Stack

- Java 17
- Spring Boot 3.2
- Spring Data JPA
- H2 (in-memory)
- Maven

## License

MIT
