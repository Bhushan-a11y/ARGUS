package com.supplychainai.riskai.causal.dto;

public record IndiaCorridorAlert(
    String supplierACorridor,
    String supplierBCorridor,
    boolean supplierACorridorFound,
    Double supplierABaselineMedianDelayMinutes,
    Double supplierABaselineSevereDelay2hShare,
    Double supplierBBaselineMedianDelayMinutes,
    Double supplierBBaselineSevereDelay2hShare,
    double originMonsoonExtraCongestionPct,
    double originMonsoonExtraSevereDelay2hPts
) {}
