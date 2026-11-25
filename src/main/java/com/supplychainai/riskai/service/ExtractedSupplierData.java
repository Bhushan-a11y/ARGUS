package com.supplychainai.riskai.service;

public record ExtractedSupplierData(
        String legalName,
        double legalNameConfidence,
        String tradeName,
        double tradeNameConfidence,
        String gstin,
        double gstinConfidence,
        String pan,
        double panConfidence,
        String address,
        double addressConfidence,
        String state,
        double stateConfidence,
        String rawText
) {
    public boolean hasGstin() {
        return gstin != null && !gstin.isBlank();
    }

    public boolean isLegalNameConfident() {
        return legalName != null && !legalName.isBlank() && legalNameConfidence >= 0.70;
    }
}
