package com.supplychainai.riskai.causal.model;

/**
 * Immutable representation of a highway corridor baseline between two Indian states.
 */
public record CorridorBaseline(
    String corridorId,
    String sourceState,
    String destState,
    Double medianDelayMinutes,
    Double medianLogCongestion,
    Double shareSevereDelay2h,
    Double meanOsrmDistanceKm,
    Integer tripCount
) {}
