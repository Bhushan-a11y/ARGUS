package com.supplychainai.riskai.causal;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.supplychainai.riskai.causal.OpenMeteoClient.WeatherFetchResult;
import com.supplychainai.riskai.causal.dto.IndiaCorridorAlert;
import com.supplychainai.riskai.causal.dto.LeverBreakdown;
import com.supplychainai.riskai.causal.dto.LocationPoint;
import com.supplychainai.riskai.causal.dto.ScenarioUsed;
import com.supplychainai.riskai.causal.dto.SimulateSwitchRequest;
import com.supplychainai.riskai.causal.dto.SimulateSwitchResponse;
import com.supplychainai.riskai.causal.dto.WeatherMode;
import com.supplychainai.riskai.causal.model.CorridorBaseline;

/**
 * Pure Java embedded causal inference engine evaluating supplier switch interventions.
 * Implements EconML LinearDML Double Machine Learning inference with 4 scientific guardrails.
 */
@Service
public class CausalSimulatorService {

    private static final Logger log = LoggerFactory.getLogger(CausalSimulatorService.class);
    private static final double EARTH_RADIUS_KM = 6371.0;

    private final CausalModelRegistry modelRegistry;
    private final OpenMeteoClient openMeteoClient;

    public CausalSimulatorService(CausalModelRegistry modelRegistry, OpenMeteoClient openMeteoClient) {
        this.modelRegistry = modelRegistry;
        this.openMeteoClient = openMeteoClient;
    }

    public SimulateSwitchResponse simulateSwitch(SimulateSwitchRequest request) {
        LocationPoint buyer = request.buyer();
        LocationPoint supplierA = request.supplierA();
        LocationPoint supplierB = request.supplierB();

        // -------------------------------------------------------------------------
        // 1. Weather Resolution
        // -------------------------------------------------------------------------
        double originRain;
        double destRain;
        boolean weatherFallbackUsed;

        if (request.weatherMode() == WeatherMode.LIVE) {
            WeatherFetchResult originWeather;
            WeatherFetchResult destWeather;
            try {
                originWeather = openMeteoClient.fetchWeatherWithStatus(supplierA.lat(), supplierA.lon());
            } catch (Exception ex) {
                log.warn("Open-Meteo client exception for supplier A (lat={}, lon={}): {}. Using 0.0 mm fallback.",
                        supplierA.lat(), supplierA.lon(), ex.getMessage());
                originWeather = new WeatherFetchResult(0.0, true);
            }

            try {
                destWeather = openMeteoClient.fetchWeatherWithStatus(buyer.lat(), buyer.lon());
            } catch (Exception ex) {
                log.warn("Open-Meteo client exception for buyer (lat={}, lon={}): {}. Using 0.0 mm fallback.",
                        buyer.lat(), buyer.lon(), ex.getMessage());
                destWeather = new WeatherFetchResult(0.0, true);
            }

            originRain = originWeather.rainMm();
            destRain = destWeather.rainMm();
            weatherFallbackUsed = originWeather.fallbackUsed() || destWeather.fallbackUsed();
        } else {
            originRain = (request.originRain3dMm() != null) ? Math.max(0.0, request.originRain3dMm()) : 0.0;
            destRain = (request.destRain3dMm() != null) ? Math.max(0.0, request.destRain3dMm()) : 0.0;
            weatherFallbackUsed = false;
        }

        // -------------------------------------------------------------------------
        // 2. Haversine Distances & Interstate Status
        // -------------------------------------------------------------------------
        double dA = haversine(supplierA.lat(), supplierA.lon(), buyer.lat(), buyer.lon());
        double dB = haversine(supplierB.lat(), supplierB.lon(), buyer.lat(), buyer.lon());

        boolean interA = !supplierA.state().trim().equalsIgnoreCase(buyer.state().trim());
        boolean interB = !supplierB.state().trim().equalsIgnoreCase(buyer.state().trim());

        // Treatment contrast: T_B - T_A
        double dt0 = Math.log1p(dB) - Math.log1p(dA);
        double dt1 = (interB ? 1.0 : 0.0) - (interA ? 1.0 : 0.0);

        // -------------------------------------------------------------------------
        // 3. Guardrail: Unit in Grams (kg * 1000.0) -> log1p(weightG)
        // -------------------------------------------------------------------------
        double weightGrams = request.cargoWeightKg() * 1000.0;
        double logWeightG = Math.log1p(weightGrams);
        double q4Flag = Boolean.TRUE.equals(request.isQ4Peak()) ? 1.0 : 0.0;

        // Scenario vector x = [1.0, origin_rain, dest_rain, log_weight_g, is_q4_peak]
        double[] x = new double[] { 1.0, originRain, destRain, logWeightG, q4Flag };

        // 10-element contrast vector v = [dt0 * x, dt1 * x]
        double[] v = new double[10];
        for (int i = 0; i < 5; i++) {
            v[i] = dt0 * x[i];
            v[5 + i] = dt1 * x[i];
        }

        // -------------------------------------------------------------------------
        // 4. Guardrail: Drive simulation strictly from outcomes.actual_transit_days
        // -------------------------------------------------------------------------
        double[] theta = modelRegistry.getThetaTransit();
        double distDelta = 0.0;
        for (int i = 0; i < 5; i++) {
            distDelta += v[i] * theta[i];
        }

        double interstateDelta = 0.0;
        for (int i = 5; i < 10; i++) {
            interstateDelta += v[i] * theta[i];
        }

        double totalDelta = distDelta + interstateDelta;

        // Analytical variance & 95% Confidence Interval from 10x10 covariance matrix
        double[][] cov = modelRegistry.getCovTransit();
        double variance = 0.0;
        for (int i = 0; i < 10; i++) {
            for (int j = 0; j < 10; j++) {
                variance += v[i] * cov[i][j] * v[j];
            }
        }

        double se = Math.sqrt(Math.max(0.0, variance));
        double ci95Lower = totalDelta - 1.96 * se;
        double ci95Upper = totalDelta + 1.96 * se;

        // Symmetry handling: if suppliers are identical, deltas and variance are 0
        boolean isSignificant = (ci95Lower > 0.0 || ci95Upper < 0.0) && Math.abs(dt0) > 1e-9;
        if (Math.abs(dt0) < 1e-9 && Math.abs(dt1) < 1e-9) {
            totalDelta = 0.0;
            distDelta = 0.0;
            interstateDelta = 0.0;
            se = 0.0;
            ci95Lower = 0.0;
            ci95Upper = 0.0;
            isSignificant = false;
        }

        // -------------------------------------------------------------------------
        // 5. Guardrail: Engine B India Monsoon (Origin Rain Only)
        // -------------------------------------------------------------------------
        double extraLogCongestion = originRain * Math.max(0.0, modelRegistry.getCongestionPerMmOriginRain());
        double originMonsoonExtraCongestionPct = (Math.exp(extraLogCongestion) - 1.0) * 100.0;
        double originMonsoonExtraSevereDelay2hPts = originRain * Math.max(0.0, modelRegistry.getSevereDelayPerMmOriginRain()) * 100.0;

        // Indian Corridor Lookup
        String supplierACorridorName = supplierA.state().trim() + " -> " + buyer.state().trim();
        String supplierBCorridorName = supplierB.state().trim() + " -> " + buyer.state().trim();

        CorridorBaseline corridorA = modelRegistry.getCorridor(supplierA.state(), buyer.state());
        CorridorBaseline corridorB = modelRegistry.getCorridor(supplierB.state(), buyer.state());

        boolean supplierACorridorFound = (corridorA != null);
        Double supplierABaselineMedianDelayMinutes = corridorA != null ? corridorA.medianDelayMinutes() : null;
        Double supplierABaselineSevereDelay2hShare = corridorA != null ? corridorA.shareSevereDelay2h() : null;
        Double supplierBBaselineMedianDelayMinutes = corridorB != null ? corridorB.medianDelayMinutes() : null;
        Double supplierBBaselineSevereDelay2hShare = corridorB != null ? corridorB.shareSevereDelay2h() : null;

        IndiaCorridorAlert corridorAlert = new IndiaCorridorAlert(
            supplierACorridorName,
            supplierBCorridorName,
            supplierACorridorFound,
            supplierABaselineMedianDelayMinutes,
            supplierABaselineSevereDelay2hShare,
            supplierBBaselineMedianDelayMinutes,
            supplierBBaselineSevereDelay2hShare,
            round(originMonsoonExtraCongestionPct, 1),
            round(originMonsoonExtraSevereDelay2hPts, 1)
        );

        // -------------------------------------------------------------------------
        // 6. Recommendation Summary
        // -------------------------------------------------------------------------
        String recommendation = buildRecommendation(
            supplierA.name(),
            supplierB.name(),
            totalDelta,
            ci95Lower,
            ci95Upper,
            isSignificant
        );

        LeverBreakdown breakdown = new LeverBreakdown(
            round(distDelta, 2),
            round(interstateDelta, 2),
            round(dA, 2),
            round(dB, 2),
            interA,
            interB
        );

        ScenarioUsed scenario = new ScenarioUsed(
            request.weatherMode(),
            round(originRain, 2),
            round(destRain, 2),
            weatherFallbackUsed,
            request.cargoWeightKg(),
            Boolean.TRUE.equals(request.isQ4Peak())
        );

        return new SimulateSwitchResponse(
            modelRegistry.getModelVersion(),
            round(totalDelta, 2),
            round(se, 4),
            round(ci95Lower, 2),
            round(ci95Upper, 2),
            isSignificant,
            breakdown,
            scenario,
            corridorAlert,
            recommendation
        );
    }

    public static double haversine(double lat1, double lon1, double lat2, double lon2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2.0) * Math.sin(dLat / 2.0)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2.0) * Math.sin(dLon / 2.0);
        double c = 2.0 * Math.atan2(Math.sqrt(a), Math.sqrt(1.0 - a));
        return EARTH_RADIUS_KM * c;
    }

    private String buildRecommendation(
            String nameA,
            String nameB,
            double delta,
            double ciLower,
            double ciUpper,
            boolean isSignificant) {
        String sA = (nameA != null && !nameA.isBlank()) ? nameA : "Supplier A";
        String sB = (nameB != null && !nameB.isBlank()) ? nameB : "Supplier B";

        if (isSignificant) {
            if (delta < 0.0) {
                return String.format(Locale.ROOT,
                    "Switching from %s to %s is statistically significant, saving an estimated %.2f transit days (95%% CI: [%.2f, %.2f] days).",
                    sA, sB, Math.abs(delta), ciLower, ciUpper);
            } else {
                return String.format(Locale.ROOT,
                    "Switching from %s to %s is statistically significant, but increases transit time by an estimated %.2f days (95%% CI: [%.2f, %.2f] days).",
                    sA, sB, delta, ciLower, ciUpper);
            }
        } else {
            return String.format(Locale.ROOT,
                "The transit time difference between %s and %s is not statistically significant at the 95%% confidence level (95%% CI: [%.2f, %.2f] days).",
                sA, sB, ciLower, ciUpper);
        }
    }

    private static double round(double val, int decimals) {
        if (Double.isNaN(val) || Double.isInfinite(val)) return val;
        return BigDecimal.valueOf(val).setScale(decimals, RoundingMode.HALF_UP).doubleValue();
    }
}
