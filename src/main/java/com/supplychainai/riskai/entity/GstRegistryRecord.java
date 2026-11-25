package com.supplychainai.riskai.entity;

import java.time.Instant;
import java.time.LocalDate;

public record GstRegistryRecord(
        Long id,
        String gstin,
        String legalName,
        String tradeName,
        String status,
        String state,
        LocalDate registrationDate,
        String taxpayerType,
        String source,
        String datasetVersion,
        Instant lastUpdated
) {}
