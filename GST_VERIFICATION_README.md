# ARGUS - OCR to Supplier Data & Prototype GST Verification

This folder contains all source code, prototype test data, and integration tests for connecting ARGUS's existing document/OCR extraction pipeline to the supplier data layer and the prototype GST verification service.

---

## 1. Directory Structure & File Manifest

You can copy the contents of this folder directly into your ARGUS project root. The directory paths match the existing Spring Boot project structure:

```text
argus gst verification/
├── README.md
│
├── data/
│   └── prototype_gst_registry_v2 new.csv
│       [Synthetic 25-row prototype GST registry dataset for testing all verification states]
│
└── src/
    ├── main/java/com/supplychainai/riskai/
    │   ├── DocsUploadService.java
    │   │   [MODIFIED: Hooks async OCR completion callback (thenAccept) to SupplierDataReconciliationService]
    │   │
    │   ├── config/
    │   │   └── GstPrototypeDataInitializer.java
    │   │       [NEW: Optional Spring Boot ApplicationRunner to auto-import the prototype CSV at startup]
    │   │
    │   ├── entity/
    │   │   ├── SupplierVerificationStatus.java
    │   │   │   [MODIFIED: Added NEEDS_REVIEW enum value]
    │   │   ├── Supplier.java
    │   │   │   [MODIFIED: Added tradeName, gstin, pan, state, verificationNotes, lastVerifiedAt to TiDB entity]
    │   │   └── GstRegistryRecord.java
    │   │       [NEW: Domain record representing rows in the prototype GST registry table]
    │   │
    │   ├── repository/
    │   │   └── GstRegistryRepository.java
    │   │       [NEW: PostgreSQL JDBC repository for prototype_gst_registry using pgvectorJdbcTemplate]
    │   │
    │   └── service/
    │       ├── ExtractedSupplierData.java
    │       │   [NEW: DTO holding structured OCR extraction fields and confidence ratings]
    │       ├── SupplierDocumentParser.java
    │       │   [NEW: Entity extraction parser with Indian Modulo-36 GSTIN checksum validation & state mapping]
    │       ├── GstVerificationService.java
    │       │   [NEW: Verification engine enforcing primary legal-name matching and status invalidation rules]
    │       ├── SupplierDataReconciliationService.java
    │       │   [NEW: Coordinates existing-data protection, profile reconciliation, and verification persistence]
    │       └── GstRegistryImporter.java
    │           [NEW: Idempotent RFC 4180 CSV parser and batch upsert importer for the prototype registry]
    │
    └── test/java/com/supplychainai/riskai/
        └── GstSupplierVerificationIntegrationTest.java
            [NEW: Comprehensive integration test suite covering 12 verification and reconciliation scenarios]
```

---

## 2. Core Architecture & Workflow

```text
Supplier uploads document (POST /api/v1/supplier/docs/upload)
        ↓
DocsPortalController (extracts supplierId strictly from authenticated JWT context)
        ↓
DocsUploadService (persists file & stores DocumentMetadata with supplierId)
        ↓
DocumentExtractionService (OCR text extraction via ocr-sidecar / Apache Tika)
        ↓ (async thenAccept callback)
SupplierDocumentParser (extracts structured legalName, tradeName, GSTIN, PAN, state + Modulo-36 checksum)
        ↓
SupplierDataReconciliationService (Existing-Data Protection: populate empty fields, confirm matches, block conflicting overwrites of verified data)
        ↓
GstVerificationService (Lookup in PostgreSQL prototype_gst_registry; primary legal name similarity >= 0.60; status checks)
        ↓
SupplierRepository (TiDB/MySQL supplier profile updated with status: VERIFIED / NEEDS_REVIEW / REJECTED)
        ↓
Available immediately on Supplier Profile API (GET /api/v1/supplier/profile)
```

---

## 3. Key Design Rules Enforced

1. **Primary Legal Name Comparison (NO `max(legal, trade)`):**
   - Legal name is the primary basis for GST verification.
   - If legal name similarity is $< 0.60$, the verification outcome is flagged as `NEEDS_REVIEW` with an explicit mismatch note.
   - A trade name match will **never** allow a conflicting legal name to pass.
2. **Database Separation:**
   - The primary `Supplier` entity stays in TiDB / MySQL (`app.datasource.tidb`).
   - Only the prototype GST registry table (`prototype_gst_registry`) is created in PostgreSQL (`app.datasource.pgvector` via `pgvectorJdbcTemplate`).
3. **PostgreSQL Timestamp Precision:**
   - `last_updated` is typed as `TIMESTAMPTZ` in PostgreSQL DDL and mapped to `java.time.Instant`.
4. **Existing-Data Protection:**
   - Empty supplier fields are automatically populated from high-confidence OCR extractions.
   - Matching values confirm existing records.
   - Conflicting values against an already-`VERIFIED` supplier are blocked from overwriting and flagged as `NEEDS_REVIEW` with conflict notes.
5. **JWT / Document Ownership Security:**
   - `supplierId` is bound strictly from the authenticated JWT token at upload time, passed via `DocumentMetadata.supplierId()`, and never accepted blindly from client payloads.
6. **Synthetic Prototype Attribution:**
   - The 25 records are explicitly labeled with `source = 'PROTOTYPE_CSV'` and `dataset_version = 'v1.1'` for synthetic prototype evaluation.

---

## 4. Test Scenarios Covered (All 12 Passing)

The test suite in `GstSupplierVerificationIntegrationTest.java` verifies:
1. `ACTIVE` status + matching legal name $\rightarrow$ `VERIFIED`
2. `CANCELLED` status $\rightarrow$ `REJECTED`
3. `INACTIVE` status $\rightarrow$ `REJECTED`
4. `SUSPENDED` status $\rightarrow$ `NEEDS_REVIEW`
5. Missing or invalid GSTIN from OCR $\rightarrow$ `NEEDS_REVIEW`
6. Conflicting legal name ($< 0.60$) $\rightarrow$ `NEEDS_REVIEW` (trade name match cannot override)
7. Missing trade name in registry $\rightarrow$ verified successfully if legal name matches
8. Existing supplier data + matching OCR data $\rightarrow$ confirmed and updated
9. Existing verified supplier data + conflicting OCR data $\rightarrow$ overwrite blocked, flagged `NEEDS_REVIEW`
10. Idempotent import (running CSV import twice results in 0 duplicate records)
11. Inserting new CSV record $\rightarrow$ inserted cleanly
12. Updating changed CSV record $\rightarrow$ updated in-place via `ON CONFLICT (gstin) DO UPDATE`
