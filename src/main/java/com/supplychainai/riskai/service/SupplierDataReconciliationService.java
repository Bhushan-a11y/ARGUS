package com.supplychainai.riskai.service;

import java.time.Instant;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.supplychainai.riskai.entity.Supplier;
import com.supplychainai.riskai.entity.SupplierVerificationStatus;
import com.supplychainai.riskai.repository.SupplierRepository;
import com.supplychainai.riskai.service.GstVerificationService.VerificationOutcome;

@Service
public class SupplierDataReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(SupplierDataReconciliationService.class);

    private final SupplierRepository supplierRepository;
    private final SupplierDocumentParser documentParser;
    private final GstVerificationService gstVerificationService;

    public SupplierDataReconciliationService(
            SupplierRepository supplierRepository,
            SupplierDocumentParser documentParser,
            GstVerificationService gstVerificationService) {
        this.supplierRepository = supplierRepository;
        this.documentParser = documentParser;
        this.gstVerificationService = gstVerificationService;
    }

    @Transactional
    public Supplier reconcileSupplierDocument(Long supplierId, String rawExtractedText) {
        if (supplierId == null) {
            log.error("Cannot reconcile document: supplierId is null");
            return null;
        }

        Optional<Supplier> supplierOpt = supplierRepository.findById(supplierId);
        if (supplierOpt.isEmpty()) {
            log.error("Cannot reconcile document: supplier [{}] not found in database", supplierId);
            return null;
        }

        Supplier supplier = supplierOpt.get();
        ExtractedSupplierData extracted = documentParser.parseDocumentText(rawExtractedText);

        log.info("Reconciling document data for supplier [{}] (name: '{}', status: {})",
                supplierId, supplier.getName(), supplier.getVerificationStatus());

        boolean isAlreadyVerified = (supplier.getVerificationStatus() == SupplierVerificationStatus.VERIFIED);
        boolean conflictDetected = false;
        StringBuilder conflictNotes = new StringBuilder();

        // 1. Reconcile Legal Name (Existing-Data Protection)
        if (extracted.legalName() != null && !extracted.legalName().isBlank()) {
            String incomingName = extracted.legalName().trim();
            if (supplier.getName() == null || supplier.getName().isBlank()) {
                supplier.setName(incomingName);
                log.info("Populated empty legal name with OCR extracted value: [{}]", incomingName);
            } else if (isNormalizedMatch(supplier.getName(), incomingName)) {
                log.info("OCR extracted legal name [{}] confirmed existing name [{}]", incomingName, supplier.getName());
            } else {
                // Clear conflict!
                if (isAlreadyVerified) {
                    // DO NOT OVERWRITE VERIFIED DATA
                    conflictDetected = true;
                    String msg = String.format("[CONFLICT] Document legal name '%s' conflicts with verified supplier name '%s'. Overwrite blocked.",
                            incomingName, supplier.getName());
                    conflictNotes.append(msg).append(" ");
                    log.warn("Existing-data protection: {}", msg);
                } else {
                    // Unverified supplier: allow update if high confidence, else flag for review
                    if (extracted.legalNameConfidence() >= 0.85) {
                        String oldName = supplier.getName();
                        supplier.setName(incomingName);
                        conflictNotes.append(String.format("[UPDATED] Name updated from '%s' to '%s' (conf: %.2f). ", oldName, incomingName, extracted.legalNameConfidence()));
                    } else {
                        conflictDetected = true;
                        conflictNotes.append(String.format("[CONFLICT] Uncertain name change: existing '%s' vs incoming '%s' (conf: %.2f). ",
                                supplier.getName(), incomingName, extracted.legalNameConfidence()));
                    }
                }
            }
        }

        // 2. Reconcile Trade Name
        if (extracted.tradeName() != null && !extracted.tradeName().isBlank()) {
            String incomingTrade = extracted.tradeName().trim();
            if (supplier.getTradeName() == null || supplier.getTradeName().isBlank()) {
                supplier.setTradeName(incomingTrade);
            } else if (!isNormalizedMatch(supplier.getTradeName(), incomingTrade)) {
                if (isAlreadyVerified) {
                    conflictNotes.append(String.format("[NOTE] Document trade name '%s' differs from existing trade name '%s'. ", incomingTrade, supplier.getTradeName()));
                } else {
                    supplier.setTradeName(incomingTrade);
                }
            }
        }

        // 3. Reconcile Address
        if (extracted.address() != null && !extracted.address().isBlank()) {
            if (supplier.getAddress() == null || supplier.getAddress().isBlank()) {
                supplier.setAddress(extracted.address().trim());
            }
        }

        // 4. Reconcile State
        if (extracted.state() != null && !extracted.state().isBlank()) {
            if (supplier.getState() == null || supplier.getState().isBlank()) {
                supplier.setState(extracted.state().trim());
            }
        }

        // 5. Reconcile PAN
        if (extracted.pan() != null && !extracted.pan().isBlank()) {
            if (supplier.getPan() == null || supplier.getPan().isBlank()) {
                supplier.setPan(extracted.pan().trim());
            }
        }

        // 6. Reconcile GSTIN
        if (extracted.gstin() != null && !extracted.gstin().isBlank()) {
            String incomingGstin = extracted.gstin().trim().toUpperCase();
            if (supplier.getGstin() == null || supplier.getGstin().isBlank()) {
                supplier.setGstin(incomingGstin);
            } else if (!supplier.getGstin().equalsIgnoreCase(incomingGstin)) {
                if (isAlreadyVerified) {
                    conflictDetected = true;
                    String msg = String.format("[CONFLICT] Document GSTIN '%s' conflicts with verified GSTIN '%s'. Overwrite blocked.",
                            incomingGstin, supplier.getGstin());
                    conflictNotes.append(msg).append(" ");
                    log.warn("Existing-data protection: {}", msg);
                } else {
                    supplier.setGstin(incomingGstin);
                }
            }
        }

        // 7. Trigger GST Verification
        String effectiveGstin = supplier.getGstin();
        if (effectiveGstin != null && !effectiveGstin.isBlank()) {
            VerificationOutcome outcome = gstVerificationService.verify(
                    effectiveGstin,
                    supplier.getName(),
                    supplier.getTradeName(),
                    supplier.getState()
            );

            // If a conflict against verified data occurred, force status to NEEDS_REVIEW
            if (conflictDetected) {
                supplier.setVerificationStatus(SupplierVerificationStatus.NEEDS_REVIEW);
                supplier.setVerificationNotes(conflictNotes.toString().trim() + " | Registry Verification: " + outcome.notes());
            } else {
                supplier.setVerificationStatus(outcome.status());
                supplier.setVerificationNotes(outcome.notes());
            }
            supplier.setLastVerifiedAt(Instant.now());
        } else {
            // Missing GSTIN
            supplier.setVerificationStatus(SupplierVerificationStatus.NEEDS_REVIEW);
            supplier.setVerificationNotes("No valid GSTIN detected on uploaded document. " + conflictNotes.toString().trim());
            supplier.setLastVerifiedAt(Instant.now());
        }

        Supplier saved = supplierRepository.save(supplier);
        log.info("Supplier [{}] saved with verification status: [{}]", saved.getId(), saved.getVerificationStatus());
        return saved;
    }

    private static boolean isNormalizedMatch(String val1, String val2) {
        if (val1 == null || val2 == null) return false;
        String n1 = GstVerificationService.normalizeName(val1);
        String n2 = GstVerificationService.normalizeName(val2);
        return !n1.isEmpty() && n1.equalsIgnoreCase(n2);
    }
}
