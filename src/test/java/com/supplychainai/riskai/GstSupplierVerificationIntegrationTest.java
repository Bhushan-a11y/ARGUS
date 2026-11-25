package com.supplychainai.riskai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Example;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.repository.query.FluentQuery.FetchableFluentQuery;

import com.supplychainai.riskai.entity.AuthProvider;
import com.supplychainai.riskai.entity.GstRegistryRecord;
import com.supplychainai.riskai.entity.Supplier;
import com.supplychainai.riskai.entity.SupplierRole;
import com.supplychainai.riskai.entity.SupplierVerificationStatus;
import com.supplychainai.riskai.repository.GstRegistryRepository;
import com.supplychainai.riskai.repository.SupplierRepository;
import com.supplychainai.riskai.service.GstRegistryImporter;
import com.supplychainai.riskai.service.GstVerificationService;
import com.supplychainai.riskai.service.GstVerificationService.VerificationOutcome;
import com.supplychainai.riskai.service.SupplierDataReconciliationService;
import com.supplychainai.riskai.service.SupplierDocumentParser;

public class GstSupplierVerificationIntegrationTest {

    private InMemoryGstRegistryRepository gstRepository;
    private InMemorySupplierRepository supplierRepository;
    private GstRegistryImporter gstImporter;
    private SupplierDocumentParser documentParser;
    private GstVerificationService verificationService;
    private SupplierDataReconciliationService reconciliationService;

    @BeforeEach
    void setUp() throws IOException {
        gstRepository = new InMemoryGstRegistryRepository();
        supplierRepository = new InMemorySupplierRepository();

        gstImporter = new GstRegistryImporter(gstRepository);
        documentParser = new SupplierDocumentParser();
        verificationService = new GstVerificationService(gstRepository);
        reconciliationService = new SupplierDataReconciliationService(
                supplierRepository,
                documentParser,
                verificationService
        );

        // Load the 25-row prototype GST registry CSV
        Path csvPath = Paths.get("data", "prototype_gst_registry_v2 new.csv");
        if (!Files.exists(csvPath)) {
            csvPath = Paths.get("C:/Users/chida/.gemini/antigravity/scratch/ARGUS-main/data/prototype_gst_registry_v2 new.csv");
        }
        if (Files.exists(csvPath)) {
            gstImporter.importCsv(csvPath);
        }
    }

    @Test
    @DisplayName("Test 1: ACTIVE + matching supplier -> VERIFIED")
    void testActiveAndMatchingSupplierReturnsVerified() {
        // GSTIN: 27AABCA1234C1ZF (Apex Precision Components Private Limited)
        Supplier supplier = createSupplier("Apex Precision Components Private Limited", "apex@example.com");

        String docText = """
                TAX INVOICE
                Legal Name: Apex Precision Components Private Limited
                Trade Name: Apex Precision Engineering
                GSTIN: 27AABCA1234C1ZF
                Address: Plot 42, MIDC Industrial Area, Pune, Maharashtra
                """;

        Supplier updated = reconciliationService.reconcileSupplierDocument(supplier.getId(), docText);

        assertNotNull(updated);
        assertEquals(SupplierVerificationStatus.VERIFIED, updated.getVerificationStatus());
        assertEquals("27AABCA1234C1ZF", updated.getGstin());
        assertEquals("Apex Precision Components Private Limited", updated.getName());
        assertEquals("Maharashtra", updated.getState());
        assertTrue(updated.getVerificationNotes().contains("Legal name verified against GST registry"));
    }

    @Test
    @DisplayName("Test 2: CANCELLED status -> REJECTED")
    void testCancelledGstinReturnsRejected() {
        // GSTIN: 24AABCB2345D1ZC (Bharat Agro-Chemical Intermediates Limited - CANCELLED)
        Supplier supplier = createSupplier("Bharat Agro-Chemical Intermediates Limited", "bharat@example.com");

        String docText = """
                GOVERNMENT OF INDIA - CERTIFICATE OF REGISTRATION
                Company: Bharat Agro-Chemical Intermediates Limited
                GSTIN: 24AABCB2345D1ZC
                State: Gujarat
                """;

        Supplier updated = reconciliationService.reconcileSupplierDocument(supplier.getId(), docText);

        assertNotNull(updated);
        assertEquals(SupplierVerificationStatus.REJECTED, updated.getVerificationStatus());
        assertTrue(updated.getVerificationNotes().contains("CANCELLED"));
    }

    @Test
    @DisplayName("Test 3: INACTIVE status -> REJECTED")
    void testInactiveGstinReturnsRejected() {
        // GSTIN: 07AABEP2345R1ZX (Vedic Freight Logistics Private Limited - INACTIVE)
        Supplier supplier = createSupplier("Vedic Freight Logistics Private Limited", "vedic@example.com");

        String docText = """
                COMMERCIAL INVOICE
                Legal Name: Vedic Freight Logistics Private Limited
                GSTIN: 07AABEP2345R1ZX
                State: Delhi
                """;

        Supplier updated = reconciliationService.reconcileSupplierDocument(supplier.getId(), docText);

        assertNotNull(updated);
        assertEquals(SupplierVerificationStatus.REJECTED, updated.getVerificationStatus());
        assertTrue(updated.getVerificationNotes().contains("INACTIVE"));
    }

    @Test
    @DisplayName("Test 4: SUSPENDED status -> NEEDS_REVIEW")
    void testSuspendedGstinReturnsNeedsReview() {
        // GSTIN: 27AABER4567T1ZD (Maratha Sheet Metal Works LLP - SUSPENDED)
        Supplier supplier = createSupplier("Maratha Sheet Metal Works LLP", "maratha@example.com");

        String docText = """
                PURCHASE ORDER
                Company: Maratha Sheet Metal Works LLP
                GSTIN: 27AABER4567T1ZD
                State: Maharashtra
                """;

        Supplier updated = reconciliationService.reconcileSupplierDocument(supplier.getId(), docText);

        assertNotNull(updated);
        assertEquals(SupplierVerificationStatus.NEEDS_REVIEW, updated.getVerificationStatus());
        assertTrue(updated.getVerificationNotes().contains("SUSPENDED"));
    }

    @Test
    @DisplayName("Test 5: GSTIN missing from OCR -> NEEDS_REVIEW")
    void testMissingGstinReturnsNeedsReview() {
        Supplier supplier = createSupplier("Unregistered Fabricators", "unreg@example.com");

        String docText = """
                PROFORMA ESTIMATE
                Legal Name: Unregistered Fabricators
                Address: Peenya Industrial Area, Bangalore
                State: Karnataka
                """;

        Supplier updated = reconciliationService.reconcileSupplierDocument(supplier.getId(), docText);

        assertNotNull(updated);
        assertEquals(SupplierVerificationStatus.NEEDS_REVIEW, updated.getVerificationStatus());
        assertNull(updated.getGstin());
        assertTrue(updated.getVerificationNotes().contains("No valid GSTIN detected"));
    }

    @Test
    @DisplayName("Test 6: Legal-name mismatch -> NEEDS_REVIEW")
    void testLegalNameMismatchReturnsNeedsReview() {
        // Registered to 27AABCA1234C1ZF (Apex Precision Components Private Limited)
        // But document claims to be 'Completely Unrelated Chemicals Private Limited'
        Supplier supplier = createSupplier("Completely Unrelated Chemicals Private Limited", "unrelated@example.com");

        String docText = """
                INVOICE
                Legal Name: Completely Unrelated Chemicals Private Limited
                GSTIN: 27AABCA1234C1ZF
                State: Maharashtra
                """;

        Supplier updated = reconciliationService.reconcileSupplierDocument(supplier.getId(), docText);

        assertNotNull(updated);
        assertEquals(SupplierVerificationStatus.NEEDS_REVIEW, updated.getVerificationStatus());
        assertTrue(updated.getVerificationNotes().contains("Legal name mismatch"));
    }

    @Test
    @DisplayName("Test 7: Missing trade name in registry -> Does not fail verification")
    void testMissingTradeNameInRegistryDoesNotFail() {
        // GSTIN: 07AABEW9012Y1ZA (Rajdhani Industrial Surface Treatments Private Limited - trade_name is empty)
        Supplier supplier = createSupplier("Rajdhani Industrial Surface Treatments Private Limited", "rajdhani@example.com");

        String docText = """
                REGISTRATION VOUCHER
                Legal Name: Rajdhani Industrial Surface Treatments Private Limited
                GSTIN: 07AABEW9012Y1ZA
                State: Delhi
                """;

        Supplier updated = reconciliationService.reconcileSupplierDocument(supplier.getId(), docText);

        assertNotNull(updated);
        assertEquals(SupplierVerificationStatus.VERIFIED, updated.getVerificationStatus());
        assertTrue(updated.getVerificationNotes().contains("Legal name verified"));
    }

    @Test
    @DisplayName("Test 8: Existing supplier data + OCR matching data -> normal update/confirmation")
    void testMatchingDataConfirmsExistingSupplier() {
        Supplier supplier = createSupplier("Apex Precision Components Private Limited", "apex@example.com");
        supplier.setAddress("Old Address, Pune");

        String docText = """
                ANNUAL TAX REPORT
                Legal Name: Apex Precision Components Private Limited
                GSTIN: 27AABCA1234C1ZF
                Trade Name: Apex Precision Engineering
                Address: Plot 42, MIDC Industrial Area, Pune, Maharashtra
                """;

        Supplier updated = reconciliationService.reconcileSupplierDocument(supplier.getId(), docText);

        assertNotNull(updated);
        assertEquals(SupplierVerificationStatus.VERIFIED, updated.getVerificationStatus());
        assertEquals("Apex Precision Components Private Limited", updated.getName());
        assertEquals("Apex Precision Engineering", updated.getTradeName());
        assertEquals("27AABCA1234C1ZF", updated.getGstin());
        assertFalse(updated.getVerificationNotes().contains("[CONFLICT]"));
    }

    @Test
    @DisplayName("Test 9: Existing verified supplier data + conflicting OCR data -> conflict/review, trusted data preserved")
    void testConflictingDataAgainstVerifiedSupplierPreservesTrustedData() {
        // 1. Supplier is ALREADY VERIFIED as 'Apex Precision Components Private Limited'
        Supplier supplier = createSupplier("Apex Precision Components Private Limited", "apex@example.com");
        supplier.setGstin("27AABCA1234C1ZF");
        supplier.setVerificationStatus(SupplierVerificationStatus.VERIFIED);
        supplierRepository.save(supplier);

        // 2. A rogue / mismatched document is uploaded claiming a completely different name
        String conflictingDocText = """
                FRAUDULENT INVOICE
                Legal Name: Rogue Phishing Enterprise Limited
                GSTIN: 27AABCA1234C1ZF
                """;

        Supplier updated = reconciliationService.reconcileSupplierDocument(supplier.getId(), conflictingDocText);

        assertNotNull(updated);
        // CRUCIAL: Trusted legal name must NOT be overwritten!
        assertEquals("Apex Precision Components Private Limited", updated.getName());
        // Status must be safely moved to NEEDS_REVIEW due to conflict
        assertEquals(SupplierVerificationStatus.NEEDS_REVIEW, updated.getVerificationStatus());
        assertTrue(updated.getVerificationNotes().contains("[CONFLICT]"));
        assertTrue(updated.getVerificationNotes().contains("Overwrite blocked"));
    }

    @Test
    @DisplayName("Test 10: Running CSV import twice -> no duplicate GSTIN records (idempotent upsert)")
    void testCsvImportIsIdempotent() throws IOException {
        long initialCount = gstRepository.count();
        assertEquals(25, initialCount, "Initial import should have 25 records");

        // Run import a second time
        Path csvPath = Paths.get("data", "prototype_gst_registry_v2 new.csv");
        if (!Files.exists(csvPath)) {
            csvPath = Paths.get("C:/Users/chida/.gemini/antigravity/scratch/ARGUS-main/data/prototype_gst_registry_v2 new.csv");
        }
        GstRegistryImporter.ImportSummary summary2 = gstImporter.importCsv(csvPath);

        assertEquals(25, summary2.totalProcessed());
        assertEquals(25, gstRepository.count(), "Second import must not create duplicate records");
    }

    @Test
    @DisplayName("Test 11: New CSV record -> inserted cleanly")
    void testNewCsvRecordInserted() throws IOException {
        String newCsvRow = """
                gstin,legal_name,trade_name,status,state,registration_date,taxpayer_type,source,dataset_version,last_updated
                27AABEZ9999A1Z8,Nouveau Test Precision Manufacturing Limited,Nouveau Precision,ACTIVE,Maharashtra,2024-03-01,Regular,PROTOTYPE_CSV,v1.1,2026-11-24T18:00:00Z
                """;

        GstRegistryImporter.ImportSummary summary = gstImporter.importCsv(new ByteArrayInputStream(newCsvRow.getBytes(StandardCharsets.UTF_8)));
        assertEquals(1, summary.successfulUpserts());

        Optional<GstRegistryRecord> recordOpt = gstRepository.findByGstin("27AABEZ9999A1Z8");
        assertTrue(recordOpt.isPresent());
        assertEquals("Nouveau Test Precision Manufacturing Limited", recordOpt.get().legalName());
        assertEquals("ACTIVE", recordOpt.get().status());
    }

    @Test
    @DisplayName("Test 12: Changed CSV record -> updated in-place")
    void testChangedCsvRecordUpdated() throws IOException {
        // Update 27AABCA1234C1ZF status from ACTIVE to SUSPENDED
        String updatedCsvRow = """
                gstin,legal_name,trade_name,status,state,registration_date,taxpayer_type,source,dataset_version,last_updated
                27AABCA1234C1ZF,Apex Precision Components Private Limited,Apex Precision Engineering,SUSPENDED,Maharashtra,2017-08-15,Regular,PROTOTYPE_CSV,v1.1,2026-11-25T12:00:00Z
                """;

        GstRegistryImporter.ImportSummary summary = gstImporter.importCsv(new ByteArrayInputStream(updatedCsvRow.getBytes(StandardCharsets.UTF_8)));
        assertEquals(1, summary.successfulUpserts());

        Optional<GstRegistryRecord> recordOpt = gstRepository.findByGstin("27AABCA1234C1ZF");
        assertTrue(recordOpt.isPresent());
        assertEquals("SUSPENDED", recordOpt.get().status(), "Status should be updated in-place");
    }

    private Supplier createSupplier(String name, String email) {
        Supplier s = new Supplier();
        s.setName(name);
        s.setEmail(email);
        s.setRole(SupplierRole.SUPPLIER);
        s.setAuthProvider(AuthProvider.GOOGLE);
        s.setVerificationStatus(SupplierVerificationStatus.PENDING);
        return supplierRepository.save(s);
    }

    public static void main(String[] args) {
        System.out.println("===============================================================");
        System.out.println("ARGUS OCR -> Supplier Data -> GST Verification Test Suite");
        System.out.println("===============================================================");
        int passed = 0;
        int failed = 0;

        GstSupplierVerificationIntegrationTest test = new GstSupplierVerificationIntegrationTest();

        record TestCase(String name, Runnable testFn) {}
        List<TestCase> tests = List.of(
                new TestCase("Test 1: ACTIVE + matching supplier -> VERIFIED", () -> {
                    try { test.setUp(); test.testActiveAndMatchingSupplierReturnsVerified(); } catch (Exception e) { throw new RuntimeException(e); }
                }),
                new TestCase("Test 2: CANCELLED status -> REJECTED", () -> {
                    try { test.setUp(); test.testCancelledGstinReturnsRejected(); } catch (Exception e) { throw new RuntimeException(e); }
                }),
                new TestCase("Test 3: INACTIVE status -> REJECTED", () -> {
                    try { test.setUp(); test.testInactiveGstinReturnsRejected(); } catch (Exception e) { throw new RuntimeException(e); }
                }),
                new TestCase("Test 4: SUSPENDED status -> NEEDS_REVIEW", () -> {
                    try { test.setUp(); test.testSuspendedGstinReturnsNeedsReview(); } catch (Exception e) { throw new RuntimeException(e); }
                }),
                new TestCase("Test 5: GSTIN missing from OCR -> NEEDS_REVIEW", () -> {
                    try { test.setUp(); test.testMissingGstinReturnsNeedsReview(); } catch (Exception e) { throw new RuntimeException(e); }
                }),
                new TestCase("Test 6: Legal-name mismatch -> NEEDS_REVIEW", () -> {
                    try { test.setUp(); test.testLegalNameMismatchReturnsNeedsReview(); } catch (Exception e) { throw new RuntimeException(e); }
                }),
                new TestCase("Test 7: Missing trade name in registry -> Does not fail", () -> {
                    try { test.setUp(); test.testMissingTradeNameInRegistryDoesNotFail(); } catch (Exception e) { throw new RuntimeException(e); }
                }),
                new TestCase("Test 8: Existing supplier data + OCR matching data -> normal update/confirmation", () -> {
                    try { test.setUp(); test.testMatchingDataConfirmsExistingSupplier(); } catch (Exception e) { throw new RuntimeException(e); }
                }),
                new TestCase("Test 9: Existing verified supplier data + conflicting OCR data -> conflict/review, trusted data preserved", () -> {
                    try { test.setUp(); test.testConflictingDataAgainstVerifiedSupplierPreservesTrustedData(); } catch (Exception e) { throw new RuntimeException(e); }
                }),
                new TestCase("Test 10: Running CSV import twice -> no duplicate GSTIN records (idempotent upsert)", () -> {
                    try { test.setUp(); test.testCsvImportIsIdempotent(); } catch (Exception e) { throw new RuntimeException(e); }
                }),
                new TestCase("Test 11: New CSV record -> inserted cleanly", () -> {
                    try { test.setUp(); test.testNewCsvRecordInserted(); } catch (Exception e) { throw new RuntimeException(e); }
                }),
                new TestCase("Test 12: Changed CSV record -> updated in-place", () -> {
                    try { test.setUp(); test.testChangedCsvRecordUpdated(); } catch (Exception e) { throw new RuntimeException(e); }
                })
        );

        for (int i = 0; i < tests.size(); i++) {
            TestCase tc = tests.get(i);
            try {
                tc.testFn().run();
                System.out.printf("[PASS] %s%n", tc.name());
                passed++;
            } catch (Throwable t) {
                System.out.printf("[FAIL] %s - %s%n", tc.name(), t.getMessage());
                t.printStackTrace();
                failed++;
            }
        }

        System.out.println("===============================================================");
        System.out.printf("Test Run Complete: %d Passed, %d Failed.%n", passed, failed);
        System.out.println("===============================================================");
        if (failed > 0) {
            System.exit(1);
        }
    }

    // =========================================================================
    // In-Memory Test Doubles for Isolated, Zero-Network Unit & Integration Tests
    // =========================================================================

    public static class InMemoryGstRegistryRepository extends GstRegistryRepository {
        private final Map<String, GstRegistryRecord> storage = new ConcurrentHashMap<>();
        private final AtomicLong idGen = new AtomicLong(1);

        public InMemoryGstRegistryRepository() {
            super(null);
        }

        @Override
        public void initSchema() {}

        @Override
        public void upsert(GstRegistryRecord record) {
            GstRegistryRecord toStore = new GstRegistryRecord(
                    record.id() != null ? record.id() : idGen.getAndIncrement(),
                    record.gstin(),
                    record.legalName(),
                    record.tradeName(),
                    record.status(),
                    record.state(),
                    record.registrationDate(),
                    record.taxpayerType(),
                    record.source(),
                    record.datasetVersion(),
                    record.lastUpdated()
            );
            storage.put(record.gstin().trim().toUpperCase(), toStore);
        }

        @Override
        public Optional<GstRegistryRecord> findByGstin(String gstin) {
            if (gstin == null) return Optional.empty();
            return Optional.ofNullable(storage.get(gstin.trim().toUpperCase()));
        }

        @Override
        public long count() {
            return storage.size();
        }
    }

    public static class InMemorySupplierRepository implements SupplierRepository {
        private final Map<Long, Supplier> store = new ConcurrentHashMap<>();
        private final AtomicLong idSequence = new AtomicLong(1);

        @Override
        public <S extends Supplier> S save(S entity) {
            if (entity.getId() == null) {
                entity.setId(idSequence.getAndIncrement());
            }
            store.put(entity.getId(), entity);
            return entity;
        }

        @Override
        public Optional<Supplier> findById(Long id) {
            return Optional.ofNullable(store.get(id));
        }

        @Override
        public Optional<Supplier> findByEmail(String email) {
            return store.values().stream().filter(s -> s.getEmail().equals(email)).findFirst();
        }

        @Override
        public Optional<Supplier> findByGoogleId(String googleId) {
            return store.values().stream().filter(s -> googleId.equals(s.getGoogleId())).findFirst();
        }

        @Override
        public Optional<Supplier> findByEmailIgnoreCase(String email) {
            return store.values().stream().filter(s -> s.getEmail().equalsIgnoreCase(email)).findFirst();
        }

        @Override
        public Optional<Supplier> findByAuthProviderAndExternalId(AuthProvider authProvider, String externalId) {
            return store.values().stream()
                    .filter(s -> authProvider.equals(s.getAuthProvider()) && externalId.equals(s.getExternalId()))
                    .findFirst();
        }

        @Override public List<Supplier> findAll() { return new ArrayList<>(store.values()); }
        @Override public boolean existsById(Long id) { return store.containsKey(id); }
        @Override public long count() { return store.size(); }
        @Override public void deleteById(Long id) { store.remove(id); }
        @Override public void delete(Supplier entity) { store.remove(entity.getId()); }
        @Override public void deleteAll() { store.clear(); }
        @Override public List<Supplier> findAllById(Iterable<Long> ids) { throw new UnsupportedOperationException(); }
        @Override public void deleteAllById(Iterable<? extends Long> ids) { throw new UnsupportedOperationException(); }
        @Override public void deleteAll(Iterable<? extends Supplier> entities) { throw new UnsupportedOperationException(); }
        @Override public <S extends Supplier> List<S> saveAll(Iterable<S> entities) { throw new UnsupportedOperationException(); }
        @Override public void flush() {}
        @Override public <S extends Supplier> S saveAndFlush(S entity) { return save(entity); }
        @Override public <S extends Supplier> List<S> saveAllAndFlush(Iterable<S> entities) { throw new UnsupportedOperationException(); }
        @Override public void deleteAllInBatch(Iterable<Supplier> entities) {}
        @Override public void deleteAllByIdInBatch(Iterable<Long> ids) {}
        @Override public void deleteAllInBatch() {}
        @Override public Supplier getOne(Long id) { return store.get(id); }
        @Override public Supplier getById(Long id) { return store.get(id); }
        @Override public Supplier getReferenceById(Long id) { return store.get(id); }
        @Override public <S extends Supplier> Optional<S> findOne(Example<S> example) { return Optional.empty(); }
        @Override public <S extends Supplier> List<S> findAll(Example<S> example) { return List.of(); }
        @Override public <S extends Supplier> List<S> findAll(Example<S> example, Sort sort) { return List.of(); }
        @Override public <S extends Supplier> Page<S> findAll(Example<S> example, Pageable pageable) { return Page.empty(); }
        @Override public <S extends Supplier> long count(Example<S> example) { return 0; }
        @Override public <S extends Supplier> boolean exists(Example<S> example) { return false; }
        @Override public <S extends Supplier, R> R findBy(Example<S> example, java.util.function.Function<FetchableFluentQuery<S>, R> queryFunction) { return null; }
        @Override public List<Supplier> findAll(Sort sort) { return List.of(); }
        @Override public Page<Supplier> findAll(Pageable pageable) { return Page.empty(); }
    }
}
