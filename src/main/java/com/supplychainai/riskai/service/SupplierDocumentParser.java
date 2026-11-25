package com.supplychainai.riskai.service;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class SupplierDocumentParser {

    private static final Logger log = LoggerFactory.getLogger(SupplierDocumentParser.class);

    private static final Pattern GSTIN_REGEX = Pattern.compile("\\b([0-9]{2}[A-Z]{5}[0-9]{4}[A-Z]{1}[1-9A-Z]{1}Z[0-9A-Z]{1})\\b");
    private static final Pattern PAN_REGEX = Pattern.compile("\\b([A-Z]{5}[0-9]{4}[A-Z]{1})\\b");
    private static final Pattern LEGAL_NAME_LABEL = Pattern.compile("(?i)(?:legal\\s*name|company(?:\\s*name)?|name\\s*of\\s*business|supplier\\s*name)\\s*[:=-]\\s*([^\\r\\n]+)");
    private static final Pattern TRADE_NAME_LABEL = Pattern.compile("(?i)(?:trade\\s*name|dba|brand\\s*name)\\s*[:=-]\\s*([^\\r\\n]+)");
    private static final Pattern ADDRESS_LABEL = Pattern.compile("(?i)(?:address|registered\\s*office|place\\s*of\\s*business)\\s*[:=-]\\s*([^\\r\\n]+)");

    private static final Map<String, String> STATE_CODE_MAP = Map.of(
            "27", "Maharashtra",
            "24", "Gujarat",
            "29", "Karnataka",
            "33", "Tamil Nadu",
            "07", "Delhi",
            "36", "Telangana",
            "09", "Uttar Pradesh",
            "06", "Haryana",
            "19", "West Bengal",
            "08", "Rajasthan"
    );

    public ExtractedSupplierData parseDocumentText(String rawText) {
        if (rawText == null || rawText.isBlank()) {
            return new ExtractedSupplierData(null, 0.0, null, 0.0, null, 0.0, null, 0.0, null, 0.0, null, 0.0, "");
        }

        // 1. Extract GSTIN
        String extractedGstin = null;
        double gstinConfidence = 0.0;
        Matcher gstinMatcher = GSTIN_REGEX.matcher(rawText);
        while (gstinMatcher.find()) {
            String candidate = gstinMatcher.group(1).trim().toUpperCase();
            if (GstVerificationService.isValidGstinFormatAndChecksum(candidate)) {
                extractedGstin = candidate;
                gstinConfidence = 0.98;
                break;
            }
        }

        // 2. Extract PAN (prefer GSTIN slice 2..12 if valid, else standalone pattern)
        String extractedPan = null;
        double panConfidence = 0.0;
        if (extractedGstin != null && extractedGstin.length() == 15) {
            extractedPan = extractedGstin.substring(2, 12);
            panConfidence = 0.99;
        } else {
            Matcher panMatcher = PAN_REGEX.matcher(rawText);
            if (panMatcher.find()) {
                extractedPan = panMatcher.group(1).trim().toUpperCase();
                panConfidence = 0.90;
            }
        }

        // 3. Extract State (from GSTIN prefix or text keyword)
        String extractedState = null;
        double stateConfidence = 0.0;
        if (extractedGstin != null && extractedGstin.length() >= 2) {
            String stateCode = extractedGstin.substring(0, 2);
            extractedState = STATE_CODE_MAP.get(stateCode);
            if (extractedState != null) {
                stateConfidence = 0.98;
            }
        }
        if (extractedState == null) {
            for (String sName : STATE_CODE_MAP.values()) {
                if (rawText.toLowerCase().contains(sName.toLowerCase())) {
                    extractedState = sName;
                    stateConfidence = 0.85;
                    break;
                }
            }
        }

        // 4. Extract Legal Name
        String legalName = null;
        double legalConfidence = 0.0;
        Matcher legalMatcher = LEGAL_NAME_LABEL.matcher(rawText);
        if (legalMatcher.find()) {
            legalName = cleanName(legalMatcher.group(1));
            legalConfidence = 0.92;
        } else {
            // Heuristic search for lines ending in Pvt Ltd / Limited / LLP
            String[] lines = rawText.split("\\r?\\n");
            for (String line : lines) {
                String trimmed = line.trim();
                if (isProbableCorporateName(trimmed)) {
                    legalName = cleanName(trimmed);
                    legalConfidence = 0.75;
                    break;
                }
            }
        }

        // 5. Extract Trade Name
        String tradeName = null;
        double tradeConfidence = 0.0;
        Matcher tradeMatcher = TRADE_NAME_LABEL.matcher(rawText);
        if (tradeMatcher.find()) {
            tradeName = cleanName(tradeMatcher.group(1));
            tradeConfidence = 0.90;
        }

        // 6. Extract Address
        String address = null;
        double addressConfidence = 0.0;
        Matcher addrMatcher = ADDRESS_LABEL.matcher(rawText);
        if (addrMatcher.find()) {
            address = addrMatcher.group(1).trim();
            addressConfidence = 0.85;
        } else {
            // Search for lines mentioning Industrial Area, MIDC, Road, etc.
            String[] lines = rawText.split("\\r?\\n");
            for (String line : lines) {
                String trimmed = line.trim();
                if (trimmed.toLowerCase().contains("industrial area") || trimmed.toLowerCase().contains("midc")
                        || trimmed.toLowerCase().contains("plot no") || trimmed.toLowerCase().contains("road")) {
                    address = trimmed;
                    addressConfidence = 0.70;
                    break;
                }
            }
        }

        log.debug("Extracted Supplier Data: legalName=[{}] (conf: {}), tradeName=[{}], gstin=[{}]",
                legalName, legalConfidence, tradeName, extractedGstin);

        return new ExtractedSupplierData(
                legalName,
                legalConfidence,
                tradeName,
                tradeConfidence,
                extractedGstin,
                gstinConfidence,
                extractedPan,
                panConfidence,
                address,
                addressConfidence,
                extractedState,
                stateConfidence,
                rawText
        );
    }

    private static boolean isProbableCorporateName(String line) {
        String lower = line.toLowerCase();
        return (lower.contains("private limited") || lower.contains("pvt ltd") || lower.contains("llp")
                || lower.contains("limited") || lower.contains("corporation") || lower.startsWith("m/s"))
                && line.length() >= 6 && line.length() <= 120;
    }

    private static String cleanName(String name) {
        if (name == null) return null;
        String cleaned = name.trim();
        // Remove trailing commas, pipes, or semicolons
        cleaned = cleaned.replaceAll("[,|;]+$", "").trim();
        return cleaned.isEmpty() ? null : cleaned;
    }
}
