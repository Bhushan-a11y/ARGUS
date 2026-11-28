package com.supplychainai.riskai.causal.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

public record SimulateSwitchRequest(
    @Valid @NotNull LocationPoint buyer,
    @Valid @NotNull LocationPoint supplierA,
    @Valid @NotNull LocationPoint supplierB,
    @NotNull @DecimalMin("0.01") Double cargoWeightKg,
    @NotNull Boolean isQ4Peak,
    @NotNull WeatherMode weatherMode,
    Double originRain3dMm,
    Double destRain3dMm
) {}
