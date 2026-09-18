# Ledger Sync

Scaffolding and completed implementation for the **Simplify Money Software Engineering Intern (Backend, Java)** take-home assignment.

This service ingests bank SMS and email alerts, handles deduplication across asynchronous channels, reconciles running balances, resolves open production incidents, and migrates transaction records onto an optimized document store.

---

## What This Service Is For

Simplify Money tells users where their money went. To do that, the system needs to read bank SMS and email alerts from a user's phone and convert them into a reliable financial ledger.

This repository implements the complete transaction processing pipeline with:

* **100% financial parity** against `fixtures/corpus-a-totals.json`
* Resolution of production incident **`INC-2026-09-11`**
* Cross-channel transaction deduplication
* Running balance reconciliation
* Transaction categorization
* Historical ledger backfill
* In-memory document store following **DynamoDB single-table design** contracts
* End-to-end consistency verification

---

## What Comes Out

Running the pipeline produces three verified reports in the submission directory:

| File                  | Description                                                                                                            |
| --------------------- | ---------------------------------------------------------------------------------------------------------------------- |
| `ledger.json`         | One canonical entry per real financial transaction with deduplicated `source_message_ids`                              |
| `summary.json`        | Per-account totals separating `spend`, `income`, `micro_count`, `micro_total`, `transferred_in`, and `transferred_out` |
| `reconciliation.json` | Accounting for unnotified telecom balance gaps through deterministic stated-balance inference                          |

---

## Transaction Categories

| Category       | Definition                                                                           |
| -------------- | ------------------------------------------------------------------------------------ |
| **`SPEND`**    | Outgoing expenditure. Included in `spend`. Excludes `MICRO` and `TRANSFER`.          |
| **`INCOME`**   | Inflow of funds directly attributable to the user. Excludes `TRANSFER`.              |
| **`MICRO`**    | High-frequency UPI debits `<= Rs.100.00`. Collapsed into summary rollups.            |
| **`TRANSFER`** | Paired inter-account movement between the user's accounts. Neither spend nor income. |

---

# Quickstart & Verification

The complete verification flow can be executed in under 5 minutes.

### 1. Compile the Source Tree

```bash
javac -d build/selfcheck $(find src/main/java -name "*.java")
```

### 2. Run SelfCheck

```bash
java -cp build/selfcheck in.simplifymoney.ledgersync.SelfCheck
```

This verifies the ingestion pipeline and financial parity against the provided corpus.

### 3. Generate Submission Artifacts

```bash
java -cp build/selfcheck in.simplifymoney.ledgersync.ReportGenerator submission
```

This generates:

```text
submission/
├── ledger.json
├── summary.json
└── reconciliation.json
```

### 4. Verify DocumentStore, Backfill & ConsistencyChecker

```bash
java -cp build/selfcheck in.simplifymoney.ledgersync.TestStorePipeline
```

---

# Checkpoint Results

| Metric                               |         Result |
| ------------------------------------ | -------------: |
| Raw messages ingested                |        **522** |
| Valid transactions generated         |        **257** |
| Skipped / non-transactional messages |         **43** |
| Financial balance discrepancy        |    **Rs.0.00** |
| Accounts verified                    | **4821, 9075** |

### Financial Parity

```text
Financial Parity: 0.00 balance discrepancy
Accounts: 4821 and 9075
```

---

# Incident Resolution

## Incident: `INC-2026-09-11`

### What Broke

A customer reported an incorrect **Rs.92,213.10 debit** for a **Rs.5.00 water can transaction**:

```text
m-00004-9c11ae
```

The issue occurred in multi-line HDFC SMS alerts containing CRLF (`\r\n`) line terminators.

A greedy regular expression skipped the actual debit amount and incorrectly captured:

```text
Avl Bal: Rs.92,213.10
```

as the transaction amount.

---

### How It Was Found

Token capture groups were inspected against the `HdfcSmsParser` using the raw fixture:

```text
m-00004-9c11ae
```

This identified that the available-balance token was being matched as the debit amount.

---

### Who Was Affected

Any user receiving multi-line HDFC debit SMS alerts containing both:

* The actual debited amount
* The closing / available balance

could potentially be affected.

---

### Fix Applied

The parser was redesigned to:

1. Normalize carriage returns and line breaks.
2. Parse the debit amount only before transaction metadata keywords.
3. Anchor amount matching around transaction action keywords such as:

   * `debited from`
   * `spent`
4. Prevent available-balance tokens from being interpreted as transaction amounts.

---

### Regression Protection

Regression assertions were added to ensure that:

```text
Available Balance
```

tokens are isolated and can never be mapped as debit amounts.

This prevents the same class of parsing error from recurring.

---

# Implementation & Design Decisions

## 1. Ingestion & Discrepancy Resolution

### Multi-Channel Deduplication

Incoming alerts are grouped per account and matched using:

* Account
* Debit / credit direction
* Transaction amount
* Timestamp proximity

SMS and Email notifications are considered duplicates when they represent the same transaction within a **120-second threshold**.

---

### Transaction Categorization

Transactions are categorized using the following rules:

```text
TRANSFER
    ↓
Paired debit + credit between user accounts
    ↓
MICRO
    ↓
UPI debit <= Rs.100.00
    ↓
SPEND / INCOME
    ↓
Remaining debit and credit transactions
```

### Transfer Detection

Transfers are detected when paired debit and credit operations:

* Belong to the user's accounts
* Have identical amounts
* Occur within a **15-minute window**

The accounts involved in the current corpus are:

```text
4821
9075
```

Transfers are excluded from both `spend` and `income`.

---

# Stated Balance Gap Resolution

A **Rs.7,500.00 discrepancy** was detected on account `4821` on:

```text
2026-07-29
```

The relevant alerts were:

```text
11:53  -> stated balance: Rs.36,054.05

17:06  -> stated balance: Rs.28,479.05
          after a Rs.75 debit
```

The balance difference could not be explained by the notified transactions.

Therefore, an **inferred debit transaction** was synthesized using the stated balances and documented in:

```text
reconciliation.json
```

This preserves the accounting balance while clearly distinguishing inferred activity from directly observed transactions.

---

# 2. Document Store Design

The document store is modeled around a **DynamoDB single-table design** and indexed MongoDB collection patterns.

The design supports the required access patterns without performing full-table scans.

---

## Primary Key / Sort Key

Transactions use:

```text
Partition Key: accountLast4
Sort Key:      occurredAt#uuid
```

This allows monthly account history queries to retrieve transactions in descending chronological order through a bounded range scan.

### Query Complexity

```text
O(log N + K)
```

Where:

* `N` = total number of records
* `K` = number of records returned

---

## Category Running Totals

Dedicated aggregate records are maintained for each account:

```json
{
  "PK": "accountLast4",
  "type": "AGGREGATE",
  "SPEND": "...",
  "INCOME": "...",
  "MICRO": "...",
  "TRANSFER": "..."
}
```

This allows lifetime category totals to be retrieved without scanning all ledger records.

### Query Complexity

```text
O(1)
```

---

## Global Secondary Index

A Global Secondary Index uses:

```text
Partition Key: messageId
```

This enables direct point lookups for raw alerts.

### Query Complexity

```text
O(1)
```

---

# Query Benchmark

Benchmark performed against **100,000 transaction records**.

| Access Pattern                      | Examined | Returned | Ratio | Complexity     |
| ----------------------------------- | -------: | -------: | ----: | -------------- |
| Q1: One account month, newest first |      320 |      320 |   1.0 | `O(log N + K)` |
| Q2: Running totals per category     |        1 |        1 |   1.0 | `O(1)`         |
| Q3: Transaction by message ID       |        1 |        1 |   1.0 | `O(1)`         |

### Access Patterns

**Q1 — Monthly Account History**

Uses the account partition key and bounded timestamp range to avoid scanning unrelated transactions.

**Q2 — Running Category Totals**

Uses the dedicated aggregate record for constant-time access.

**Q3 — Transaction by Message ID**

Uses the GSI for direct point lookup.

---

# 3. Backfill & Consistency Checker

## Backfill

The backfill process is designed to be **idempotent**.

Records are deduplicated using the natural identity:

```text
accountLast4|occurredAt|direction|amount
```

This makes the backfill safe to rerun after:

* Partial failures
* Interrupted migrations
* Duplicate execution

---

## ConsistencyChecker

The `ConsistencyChecker` performs end-to-end verification between the SQL representation and the Document Store.

It verifies:

* Monthly transaction counts
* Category totals
* Message ID resolution
* Ledger consistency

The current verification reports:

```text
0 divergences
```

---

# Architecture Overview

```text
             Bank SMS
                │
                │
                ▼
        ┌─────────────────┐
        │   SMS Parsers   │
        └────────┬────────┘
                 │
                 │
Bank Emails ─────┤
                 │
                 ▼
        ┌─────────────────┐
        │ Normalization   │
        └────────┬────────┘
                 │
                 ▼
        ┌─────────────────┐
        │ Deduplication   │
        └────────┬────────┘
                 │
                 ▼
        ┌─────────────────┐
        │ Categorization  │
        │                 │
        │ SPEND           │
        │ INCOME          │
        │ MICRO           │
        │ TRANSFER        │
        └────────┬────────┘
                 │
                 ▼
        ┌─────────────────┐
        │ Reconciliation  │
        └────────┬────────┘
                 │
                 ▼
        ┌─────────────────┐
        │ Canonical Ledger│
        └────────┬────────┘
                 │
          ┌──────┴──────┐
          ▼             ▼
    JSON Reports    Document Store
          │             │
          │             ▼
          │       Backfill / Queries
          │             │
          └──────┬──────┘
                 ▼
        ConsistencyChecker
```

---

# Generated Reports

The pipeline produces three verified artifacts.

## `ledger.json`

Contains one canonical record for each real financial transaction.

Each transaction includes deduplicated source message IDs.

Example structure:

```json
{
  "accountLast4": "4821",
  "occurredAt": "...",
  "amount": "...",
  "direction": "DEBIT",
  "category": "SPEND",
  "source_message_ids": [
    "m-00001-..."
  ]
}
```

---

## `summary.json`

Contains per-account financial summaries.

The summary keeps the following categories separate:

```text
spend
income
micro_count
micro_total
transferred_in
transferred_out
```

This prevents transfers and micro transactions from incorrectly affecting ordinary spending totals.

---

## `reconciliation.json`

Documents balance gaps that cannot be explained by directly observed transaction notifications.

Inferred transactions are explicitly recorded so that:

* The ledger remains financially balanced.
* Observed and inferred activity remain distinguishable.
* Reconciliation remains auditable.

---

# AI Disclosure

AI-assisted development tools were used during implementation.

### Tools Used

* Cursor
* LLM assistants

They were used for:

* Scaffolding
* Test generation
* Exploring regex edge cases
* Debugging assistance

All critical implementation decisions and fixes were manually reviewed.

---

## Concrete AI Failure Case

An early AI-generated suggestion used a loose amount regex:

```regex
(?:Rs\.|INR)\s*([\d,]+(?:\.\d+)?)
```

Without proper line-boundary anchoring, this regex could incorrectly capture an available balance as the transaction amount.

This behavior directly contributed to the investigation of:

```text
INC-2026-09-11
```

The parser was subsequently redesigned to anchor amount extraction strictly before transaction action keywords.

This highlights the importance of validating AI-generated code against real-world financial message formats and regression fixtures.

---

# Current Limitations

## Live Docker Wiring

The DocumentStore contract is fully implemented, validated, and benchmarked in-memory.

However, connecting the implementation to external containers through:

```text
docker-compose.yml
```

for:

* DynamoDB Local
* MongoDB

remains unfinished.

---

## Foreign Currencies

Transaction ingestion is currently calibrated for:

```text
INR
```

Multi-currency transaction handling and real-time FX conversion are outside the current scope.

---

# Verification Summary

The current implementation successfully verifies:

* [x] SMS ingestion
* [x] Email ingestion
* [x] Cross-channel deduplication
* [x] Transaction categorization
* [x] Micro transaction rollups
* [x] Inter-account transfer detection
* [x] Balance reconciliation
* [x] `INC-2026-09-11` incident resolution
* [x] Regression coverage for HDFC parsing
* [x] `ledger.json` generation
* [x] `summary.json` generation
* [x] `reconciliation.json` generation
* [x] DocumentStore implementation
* [x] Idempotent backfill
* [x] ConsistencyChecker
* [x] 100,000-record query benchmark
* [ ] Live DynamoDB/MongoDB Docker wiring
* [ ] Multi-currency / FX support

---

# Key Results

```text
522 raw messages ingested
257 valid transactions generated
43 non-transactional messages skipped
Rs.0.00 financial discrepancy
0 consistency divergences
100,000-record document-store benchmark completed
INC-2026-09-11 resolved
```

---

## Project Status

**Core pipeline: Complete**

**Financial reconciliation: Verified**

**Document Store: Implemented & Benchmarked**

**Production Docker integration: Pending**

**Multi-currency support: Out of scope**
