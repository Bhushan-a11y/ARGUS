package com.supplychainai.riskai.causal.dto;

public record SimulateSwitchResponse(
    String modelVersion,
    double transitDaysDelta,
    double standardError,
    double ci95Lower,
    double ci95Upper,
    boolean isStatisticallySignificant,
    LeverBreakdown breakdown,
    ScenarioUsed scenario,
    IndiaCorridorAlert indiaCorridorAlert,
    String recommendation
) {}
