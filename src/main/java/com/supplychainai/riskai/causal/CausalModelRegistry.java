package com.supplychainai.riskai.causal;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.supplychainai.riskai.causal.model.CorridorBaseline;

import jakarta.annotation.PostConstruct;

/**
 * Loads and holds immutable in-memory representations of the EconML LinearDML model
 * coefficients and Indian highway corridor baselines.
 */
@Component
public class CausalModelRegistry {

    private static final Logger log = LoggerFactory.getLogger(CausalModelRegistry.class);

    private static final String COEFFICIENTS_PATH = "models/argus_causal_coefficients.json";
    private static final String CORRIDORS_PATH = "models/india_corridor_baselines.csv";

    private final ObjectMapper objectMapper;

    private String modelVersion;
    private double[] thetaTransit; // 10 elements
    private double[][] covTransit; // 10x10 matrix
    private double congestionPerMmOriginRain;
    private double severeDelayPerMmOriginRain;
    private Map<String, CorridorBaseline> corridorMap;

    public CausalModelRegistry(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    public void init() {
        log.info("Initializing CausalModelRegistry from classpath artifacts...");
        loadCoefficients();
        loadCorridorBaselines();
        log.info("CausalModelRegistry successfully initialized. Model version: {}, Corridors loaded: {}",
                modelVersion, corridorMap.size());
    }

    private void loadCoefficients() {
        try (InputStream is = getResourceStream(COEFFICIENTS_PATH)) {
            if (is == null) {
                throw new IllegalStateException("Causal model coefficients not found on classpath: " + COEFFICIENTS_PATH);
            }

            JsonNode root = objectMapper.readTree(is);
            this.modelVersion = root.path("model_version").asText("unknown");

            JsonNode actualTransit = root.path("outcomes").path("actual_transit_days");
            if (actualTransit.isMissingNode()) {
                throw new IllegalStateException("Missing 'outcomes.actual_transit_days' in " + COEFFICIENTS_PATH);
            }

            JsonNode interceptNode = actualTransit.path("intercept");
            JsonNode cateWeightsNode = actualTransit.path("cate_weights");
            JsonNode covNode = actualTransit.path("covariance_matrix");

            if (!interceptNode.isArray() || interceptNode.size() < 2) {
                throw new IllegalStateException("Invalid 'intercept' array in " + COEFFICIENTS_PATH);
            }
            if (!cateWeightsNode.isArray() || cateWeightsNode.size() < 2) {
                throw new IllegalStateException("Invalid 'cate_weights' array in " + COEFFICIENTS_PATH);
            }
            if (!covNode.isArray() || covNode.size() != 10) {
                throw new IllegalStateException("Invalid 'covariance_matrix' array in " + COEFFICIENTS_PATH);
            }

            // Flatten outcomes.actual_transit_days into a 10-element double[] thetaTransit:
            // [intercept[0], cate_weights[0][0..3], intercept[1], cate_weights[1][0..3]]
            this.thetaTransit = new double[10];
            this.thetaTransit[0] = interceptNode.get(0).asDouble();
            for (int j = 0; j < 4; j++) {
                this.thetaTransit[1 + j] = cateWeightsNode.get(0).get(j).asDouble();
            }
            this.thetaTransit[5] = interceptNode.get(1).asDouble();
            for (int j = 0; j < 4; j++) {
                this.thetaTransit[6 + j] = cateWeightsNode.get(1).get(j).asDouble();
            }

            // 10x10 covariance matrix
            this.covTransit = new double[10][10];
            for (int i = 0; i < 10; i++) {
                JsonNode row = covNode.get(i);
                if (!row.isArray() || row.size() != 10) {
                    throw new IllegalStateException("Row " + i + " of covariance_matrix is not size 10");
                }
                for (int j = 0; j < 10; j++) {
                    this.covTransit[i][j] = row.get(j).asDouble();
                }
            }

            // Monsoon multipliers from engine_b_india
            JsonNode monsoon = root.path("engine_b_india").path("monsoon_multipliers");
            this.congestionPerMmOriginRain = monsoon.path("congestion_per_mm_origin_rain").asDouble(0.0);
            this.severeDelayPerMmOriginRain = monsoon.path("severe_delay_per_mm_origin_rain").asDouble(0.0);

        } catch (Exception ex) {
            throw new IllegalStateException("Failed to load or parse causal model coefficients: " + ex.getMessage(), ex);
        }
    }

    private void loadCorridorBaselines() {
        Map<String, CorridorBaseline> map = new HashMap<>();
        try (InputStream is = getResourceStream(CORRIDORS_PATH)) {
            if (is == null) {
                throw new IllegalStateException("Corridor baselines CSV not found on classpath: " + CORRIDORS_PATH);
            }

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                String header = reader.readLine();
                if (header == null) {
                    throw new IllegalStateException("Corridor baselines CSV is empty");
                }

                String line;
                while ((line = reader.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty()) {
                        continue;
                    }
                    String[] tokens = line.split(",", -1);
                    if (tokens.length < 8) {
                        continue;
                    }

                    String corridorId = tokens[0].trim();
                    String sourceState = tokens[1].trim();
                    String destState = tokens[2].trim();
                    Double medianDelay = parseDouble(tokens[3]);
                    Double medianLogCongestion = parseDouble(tokens[4]);
                    Double shareSevereDelay2h = parseDouble(tokens[5]);
                    Double meanOsrmDistanceKm = parseDouble(tokens[6]);
                    Integer tripCount = parseInt(tokens[7]);

                    CorridorBaseline baseline = new CorridorBaseline(
                        corridorId,
                        sourceState,
                        destState,
                        medianDelay,
                        medianLogCongestion,
                        shareSevereDelay2h,
                        meanOsrmDistanceKm,
                        tripCount
                    );

                    String key = makeKey(sourceState, destState);
                    map.put(key, baseline);
                }
            }
            this.corridorMap = Collections.unmodifiableMap(map);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to load corridor baselines CSV: " + ex.getMessage(), ex);
        }
    }

    private Double parseDouble(String str) {
        if (str == null || str.isBlank()) return null;
        try {
            return Double.valueOf(str.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Integer parseInt(String str) {
        if (str == null || str.isBlank()) return null;
        try {
            return Integer.valueOf(str.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private InputStream getResourceStream(String path) {
        InputStream is = Thread.currentThread().getContextClassLoader().getResourceAsStream(path);
        if (is == null) {
            is = getClass().getClassLoader().getResourceAsStream(path);
        }
        return is;
    }

    public static String makeKey(String sourceState, String destState) {
        String src = sourceState != null ? sourceState.trim().toUpperCase() : "";
        String dst = destState != null ? destState.trim().toUpperCase() : "";
        return src + "->" + dst;
    }

    public CorridorBaseline getCorridor(String sourceState, String destState) {
        return corridorMap.get(makeKey(sourceState, destState));
    }

    public String getModelVersion() {
        return modelVersion;
    }

    public double[] getThetaTransit() {
        return thetaTransit;
    }

    public double[][] getCovTransit() {
        return covTransit;
    }

    public double getCongestionPerMmOriginRain() {
        return congestionPerMmOriginRain;
    }

    public double getSevereDelayPerMmOriginRain() {
        return severeDelayPerMmOriginRain;
    }

    public int getCorridorCount() {
        return corridorMap != null ? corridorMap.size() : 0;
    }

    public Map<String, CorridorBaseline> getCorridorMap() {
        return corridorMap;
    }
}
