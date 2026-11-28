package com.supplychainai.riskai.causal.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record LocationPoint(
    String supplierId,
    String name,
    @NotBlank String city,
    @NotBlank String state,
    @NotNull Double lat,
    @NotNull Double lon
) {}
