package com.supplychainai.riskai.service;

import java.util.Optional;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.supplychainai.riskai.entity.GstRegistryRecord;
import com.supplychainai.riskai.entity.SupplierVerificationStatus;
import com.supplychainai.riskai.repository.GstRegistryRepository;

@Service
public class GstVerificationService {

    private static final Logger log = LoggerFactory.getLogger(GstVerificationService.class);

    private static final String GST_CODES = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ";
    private static final Pattern GSTIN_PATTERN = Pattern.compile("^[0-9]{2}[A-Z]{5}[0-9]{4}[A-Z]{1}[1-9A-Z]{1}Z[0-9A-Z]{1}$");

    private final GstRegistryRepository registryRepository;

    public GstVerificationService(GstRegistryRepository registryRepository) {
        this.registryRepository = registryRepository;
    }

    public record VerificationOutcome(
            SupplierVerificationStatus status,
            String notes,
            double legalNameMatchScore,
            double tradeNameMatchScore,
            GstRegistryRecord registryRecord
    ) {}

    public VerificationOutcome verify(
            String gstin,
            String extractedLegalName,
            String extractedTradeName,
            String extractedState
    ) {
        String cleanGstin = gstin != null ? gstin.trim().toUpperCase() : "";

        // 1. GSTIN Presence and Structural Validation
        if (cleanGstin.isEmpty() || !isValidGstinFormatAndChecksum(cleanGstin)) {
            return new VerificationOutcome(
                    SupplierVerificationStatus.NEEDS_REVIEW,
                    "GSTIN is missing, unreadable, or failed checksum validation.",
                    0.0,
                    0.0,
                    null
            );
        }

        // 2. Lookup in Prototype Registry
        Optional<GstRegistryRecord> recordOpt = registryRepository.findByGstin(cleanGstin);
        if (recordOpt.isEmpty()) {
            return new VerificationOutcome(
                    SupplierVerificationStatus.NEEDS_REVIEW,
                    "GSTIN [" + cleanGstin + "] not found in prototype GST registry. Needs manual verification.",
                    0.0,
                    0.0,
                    null
            );
        }

        GstRegistryRecord record = recordOpt.get();
        String regStatus = record.status() != null ? record.status().toUpperCase() : "UNKNOWN";

        // 3. Status Invalidation Rules
        if ("CANCELLED".equals(regStatus)) {
            return new VerificationOutcome(
                    SupplierVerificationStatus.REJECTED,
                    "GST registration is CANCELLED in GST registry.",
                    0.0,
                    0.0,
                    record
            );
        }

        if ("INACTIVE".equals(regStatus)) {
            return new VerificationOutcome(
                    SupplierVerificationStatus.REJECTED,
                    "GST registration is INACTIVE in GST registry.",
                    0.0,
                    0.0,
                    record
            );
        }

        if ("SUSPENDED".equals(regStatus)) {
            return new VerificationOutcome(
                    SupplierVerificationStatus.NEEDS_REVIEW,
                    "GST registration is SUSPENDED pending review in GST registry.",
                    0.0,
                    0.0,
                    record
            );
        }

        if (!"ACTIVE".equals(regStatus)) {
            return new VerificationOutcome(
                    SupplierVerificationStatus.NEEDS_REVIEW,
                    "GST registration status [" + regStatus + "] requires administrative review.",
                    0.0,
                    0.0,
                    record
            );
        }

        // 4. Primary Legal Name Comparison
        double legalScore = computeSimilarity(extractedLegalName, record.legalName());
        double tradeScore = computeSimilarity(extractedTradeName, record.tradeName());

        log.info("GSTIN [{}] legal name similarity: {}, trade name similarity: {}", cleanGstin, legalScore, tradeScore);

        // Core Rule: Legal Name must be the primary comparison.
        // A conflicting legal name (< 0.60) must NOT pass just because trade name appears.
        if (legalScore < 0.60) {
            String note = String.format(
                    "Legal name mismatch: Extracted [%s] does not match GST registry legal name [%s] (score: %.2f).",
                    extractedLegalName, record.legalName(), legalScore
            );
            return new VerificationOutcome(
                    SupplierVerificationStatus.NEEDS_REVIEW,
                    note,
                    legalScore,
                    tradeScore,
                    record
            );
        }

        // Legal name is a strong match (>= 0.85)
        if (legalScore >= 0.85) {
            StringBuilder notes = new StringBuilder("Legal name verified against GST registry (score: " + String.format("%.2f", legalScore) + ").");
            if (record.tradeName() != null && tradeScore >= 0.70) {
                notes.append(" Supporting trade name confirmed: [").append(record.tradeName()).append("].");
            }
            return new VerificationOutcome(
                    SupplierVerificationStatus.VERIFIED,
                    notes.toString(),
                    legalScore,
                    tradeScore,
                    record
            );
        }

        // Legal name is in the ambiguous/fuzzy zone (0.60 <= legalScore < 0.85)
        // Check if trade name provides supporting evidence
        if (record.tradeName() != null && tradeScore >= 0.85) {
            String note = String.format(
                    "Legal name partially matched (%.2f); supporting trade name matched (%.2f). Requires review.",
                    legalScore, tradeScore
            );
            return new VerificationOutcome(
                    SupplierVerificationStatus.NEEDS_REVIEW,
                    note,
                    legalScore,
                    tradeScore,
                    record
            );
        }

        return new VerificationOutcome(
                SupplierVerificationStatus.NEEDS_REVIEW,
                String.format("Legal name similarity (%.2f) requires human review for abbreviation or OCR noise.", legalScore),
                legalScore,
                tradeScore,
                record
        );
    }

    public static boolean isValidGstinFormatAndChecksum(String gstin) {
        if (gstin == null || gstin.length() != 15) {
            return false;
        }
        String clean = gstin.trim().toUpperCase();
        if (!GSTIN_PATTERN.matcher(clean).matches()) {
            return false;
        }

        // Official Modulo-36 Luhn-like algorithm for Indian GSTIN
        int factor = 1;
        int total = 0;
        int mod = GST_CODES.length();

        for (int i = 0; i < 14; i++) {
            char c = clean.charAt(i);
            int val = GST_CODES.indexOf(c);
            if (val < 0) {
                return false;
            }
            int digit = factor * val;
            digit = (digit / mod) + (digit % mod);
            total += digit;
            factor = (factor == 1) ? 2 : 1;
        }

        int rem = total % mod;
        int checkCode = (mod - rem) % mod;
        return clean.charAt(14) == GST_CODES.charAt(checkCode);
    }

    public static double computeSimilarity(String s1, String s2) {
        if (s1 == null || s2 == null) {
            return 0.0;
        }
        String norm1 = normalizeName(s1);
        String norm2 = normalizeName(s2);

        if (norm1.isEmpty() || norm2.isEmpty()) {
            return 0.0;
        }
        if (norm1.equals(norm2)) {
            return 1.0;
        }

        // Substring / prefix check
        if (norm1.contains(norm2) || norm2.contains(norm1)) {
            double ratio = (double) Math.min(norm1.length(), norm2.length()) / Math.max(norm1.length(), norm2.length());
            if (ratio >= 0.5) {
                return Math.max(0.88, computeDiceCoefficient(norm1, norm2));
            }
        }

        return computeDiceCoefficient(norm1, norm2);
    }

    public static String normalizeName(String name) {
        if (name == null) return "";
        String s = name.toUpperCase();
        // Normalize abbreviations
        s = s.replaceAll("\\bPVT\\.?\\b", "PRIVATE");
        s = s.replaceAll("\\bLTD\\.?\\b", "LIMITED");
        s = s.replaceAll("\\bENGG\\.?\\b", "ENGINEERING");
        s = s.replaceAll("\\bWHSG\\.?\\b", "WAREHOUSING");
        s = s.replaceAll("\\bCORP\\.?\\b", "CORPORATION");
        s = s.replaceAll("\\bM/S\\b", "");
        // Remove corporate suffixes
        s = s.replaceAll("\\bPRIVATE\\b|\\bLIMITED\\b|\\bLLP\\b|\\bCORPORATION\\b|\\bCOMPANY\\b", "");
        // Remove non-alphanumeric
        s = s.replaceAll("[^A-Z0-9\\s]", " ");
        // Collapse whitespace
        return s.trim().replaceAll("\\s+", " ");
    }

    /**
     * Bigram Dice coefficient for robust character & token similarity.
     */
    private static double computeDiceCoefficient(String s1, String s2) {
        if (s1.length() < 2 || s2.length() < 2) {
            return s1.equalsIgnoreCase(s2) ? 1.0 : 0.0;
        }

        java.util.Set<String> bigrams1 = new java.util.HashSet<>();
        for (int i = 0; i < s1.length() - 1; i++) {
            bigrams1.add(s1.substring(i, i + 2));
        }

        java.util.Set<String> bigrams2 = new java.util.HashSet<>();
        for (int i = 0; i < s2.length() - 1; i++) {
            bigrams2.add(s2.substring(i, i + 2));
        }

        int intersection = 0;
        for (String bg : bigrams1) {
            if (bigrams2.contains(bg)) {
                intersection++;
            }
        }

        return (2.0 * intersection) / (bigrams1.size() + bigrams2.size());
    }
}
