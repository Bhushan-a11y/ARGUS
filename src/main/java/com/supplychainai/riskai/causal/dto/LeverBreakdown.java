package com.supplychainai.riskai.causal.dto;

public record LeverBreakdown(
    double savedFromShorterDistance,
    double savedFromAvoidingStateBorder,
    double supplierADistanceKm,
    double supplierBDistanceKm,
    boolean supplierAInterstate,
    boolean supplierBInterstate
) {}
