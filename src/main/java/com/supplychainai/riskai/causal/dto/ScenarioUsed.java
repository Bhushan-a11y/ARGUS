package com.supplychainai.riskai.causal.dto;

public record ScenarioUsed(
    WeatherMode weatherMode,
    double originRain3dMm,
    double destRain3dMm,
    boolean weatherFallbackUsed,
    double cargoWeightKg,
    boolean isQ4Peak
) {}
