package com.supplychainai.riskai.causal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.supplychainai.riskai.causal.OpenMeteoClient.WeatherFetchResult;
import com.supplychainai.riskai.causal.dto.LocationPoint;
import com.supplychainai.riskai.causal.dto.SimulateSwitchRequest;
import com.supplychainai.riskai.causal.dto.SimulateSwitchResponse;
import com.supplychainai.riskai.causal.dto.WeatherMode;

class CausalSimulatorServiceTest {

    private static CausalModelRegistry registry;
    private static ObjectMapper objectMapper;

    @BeforeAll
    static void initRegistry() {
        objectMapper = new ObjectMapper();
        registry = new CausalModelRegistry(objectMapper);
        registry.init();
    }

    private final LocationPoint buyerBengaluru = new LocationPoint(
            "buyer-1", "Bengaluru DC", "Bengaluru", "Karnataka", 12.9716, 77.5946
    );

    private final LocationPoint supplierAAhmedabad = new LocationPoint(
            "sup-A", "Ahmedabad Plant", "Ahmedabad", "Gujarat", 23.0225, 72.5714
    );

    private final LocationPoint supplierBHubballi = new LocationPoint(
            "sup-B", "Hubballi Works", "Hubballi", "Karnataka", 15.3647, 75.1240
    );

    @Test
    @DisplayName("Test 1: Dry Switch (Ahmedabad Gujarat vs Hubballi Karnataka -> Bengaluru Karnataka, 3.5kg, Q4=false, MANUAL 0mm/0mm)")
    void testDrySwitchIntervention() {
        OpenMeteoClient mockWeather = new OpenMeteoClient(objectMapper) {
            @Override
            public WeatherFetchResult fetchWeatherWithStatus(double lat, double lon) {
                return new WeatherFetchResult(0.0, false);
            }
        };

        CausalSimulatorService service = new CausalSimulatorService(registry, mockWeather);

        SimulateSwitchRequest req = new SimulateSwitchRequest(
                buyerBengaluru,
                supplierAAhmedabad,
                supplierBHubballi,
                3.5,
                false,
                WeatherMode.MANUAL,
                0.0,
                0.0
        );

        SimulateSwitchResponse res = service.simulateSwitch(req);

        assertNotNull(res);
        assertEquals("1.0.0", res.modelVersion());

        // Assert transitDaysDelta is -2.89 (tolerance 0.02)
        assertEquals(-2.89, res.transitDaysDelta(), 0.02, "Expected transitDaysDelta ~ -2.89");

        // Assert ci95Lower ~ -3.10 and ci95Upper ~ -2.67 (tolerance 0.03)
        assertEquals(-3.10, res.ci95Lower(), 0.03, "Expected ci95Lower ~ -3.10");
        assertEquals(-2.67, res.ci95Upper(), 0.03, "Expected ci95Upper ~ -2.67");

        // Assert statistical significance
        assertTrue(res.isStatisticallySignificant(), "Intervention must be statistically significant");

        // Verify corridor alerts
        assertTrue(res.indiaCorridorAlert().supplierACorridorFound());
        assertEquals("Gujarat -> Karnataka", res.indiaCorridorAlert().supplierACorridor());
        assertEquals("Karnataka -> Karnataka", res.indiaCorridorAlert().supplierBCorridor());
        assertNotNull(res.indiaCorridorAlert().supplierABaselineMedianDelayMinutes());
        assertNotNull(res.indiaCorridorAlert().supplierBBaselineMedianDelayMinutes());
    }

    @Test
    @DisplayName("Test 2: Monsoon Switch (same locations, 3.5kg, Q4=false, MANUAL 50mm origin / 30mm dest)")
    void testMonsoonSwitchIntervention() {
        OpenMeteoClient mockWeather = new OpenMeteoClient(objectMapper) {
            @Override
            public WeatherFetchResult fetchWeatherWithStatus(double lat, double lon) {
                return new WeatherFetchResult(0.0, false);
            }
        };

        CausalSimulatorService service = new CausalSimulatorService(registry, mockWeather);

        SimulateSwitchRequest req = new SimulateSwitchRequest(
                buyerBengaluru,
                supplierAAhmedabad,
                supplierBHubballi,
                3.5,
                false,
                WeatherMode.MANUAL,
                50.0,
                30.0
        );

        SimulateSwitchResponse res = service.simulateSwitch(req);

        assertNotNull(res);

        // Assert transitDaysDelta is -5.22 (tolerance 0.02)
        assertEquals(-5.22, res.transitDaysDelta(), 0.02, "Expected transitDaysDelta ~ -5.22");

        // Assert ci95Lower ~ -5.75 and ci95Upper ~ -4.69 (tolerance 0.03)
        assertEquals(-5.75, res.ci95Lower(), 0.03, "Expected ci95Lower ~ -5.75");
        assertEquals(-4.69, res.ci95Upper(), 0.03, "Expected ci95Upper ~ -4.69");

        // Assert per-lever breakdown
        // savedFromShorterDistance ~ -1.90 and savedFromAvoidingStateBorder ~ -3.32 (tolerance 0.02)
        assertEquals(-1.90, res.breakdown().savedFromShorterDistance(), 0.02, "Expected savedFromShorterDistance ~ -1.90");
        assertEquals(-3.32, res.breakdown().savedFromAvoidingStateBorder(), 0.02, "Expected savedFromAvoidingStateBorder ~ -3.32");

        // Assert monsoon multipliers
        // originMonsoonExtraCongestionPct ~ 4.1 (tolerance 0.2) and originMonsoonExtraSevereDelay2hPts ~ 2.3 (tolerance 0.2)
        assertEquals(4.1, res.indiaCorridorAlert().originMonsoonExtraCongestionPct(), 0.2, "Expected monsoon extra congestion ~ 4.1%");
        assertEquals(2.3, res.indiaCorridorAlert().originMonsoonExtraSevereDelay2hPts(), 0.2, "Expected monsoon extra severe delay ~ 2.3 pts");
    }

    @Test
    @DisplayName("Test 3: Identical Supplier Symmetry (supplierA == supplierB)")
    void testIdenticalSupplierSymmetry() {
        OpenMeteoClient mockWeather = new OpenMeteoClient(objectMapper) {
            @Override
            public WeatherFetchResult fetchWeatherWithStatus(double lat, double lon) {
                return new WeatherFetchResult(10.0, false);
            }
        };

        CausalSimulatorService service = new CausalSimulatorService(registry, mockWeather);

        SimulateSwitchRequest req = new SimulateSwitchRequest(
                buyerBengaluru,
                supplierAAhmedabad,
                supplierAAhmedabad, // identical supplier A and B
                5.0,
                false,
                WeatherMode.MANUAL,
                0.0,
                0.0
        );

        SimulateSwitchResponse res = service.simulateSwitch(req);

        assertEquals(0.0, res.transitDaysDelta(), 0.0001, "Transit delta must be 0 for identical suppliers");
        assertEquals(0.0, res.standardError(), 0.0001, "Standard error must be 0 for identical suppliers");
        assertFalse(res.isStatisticallySignificant(), "Identical supplier switch cannot be statistically significant");
    }

    @Test
    @DisplayName("Test 4: Open-Meteo Fallback (LIVE mode exception gracefully falls back to 0.0mm)")
    void testOpenMeteoFallback() {
        // Mock OpenMeteoClient that throws an unhandled network error
        OpenMeteoClient failingWeatherClient = new OpenMeteoClient(objectMapper) {
            @Override
            public WeatherFetchResult fetchWeatherWithStatus(double lat, double lon) {
                throw new RuntimeException("Simulated Open-Meteo socket timeout error");
            }
        };

        CausalSimulatorService service = new CausalSimulatorService(registry, failingWeatherClient);

        SimulateSwitchRequest req = new SimulateSwitchRequest(
                buyerBengaluru,
                supplierAAhmedabad,
                supplierBHubballi,
                3.5,
                false,
                WeatherMode.LIVE,
                null,
                null
        );

        // Service should execute cleanly without throwing
        SimulateSwitchResponse res = service.simulateSwitch(req);

        assertNotNull(res);
        assertTrue(res.scenario().weatherFallbackUsed(), "Fallback flag must be set to true");
        assertEquals(0.0, res.scenario().originRain3dMm(), "Origin rain must fall back to 0.0 mm");
        assertEquals(0.0, res.scenario().destRain3dMm(), "Dest rain must fall back to 0.0 mm");
        // Result should match the dry switch intervention
        assertEquals(-2.89, res.transitDaysDelta(), 0.02);

        // Verify controller returns HTTP 200 with the response
        CausalSimulatorController controller = new CausalSimulatorController(service, registry);
        ResponseEntity<SimulateSwitchResponse> httpResponse = controller.simulateSwitch(req);

        assertEquals(HttpStatus.OK, httpResponse.getStatusCode());
        assertNotNull(httpResponse.getBody());
        assertTrue(httpResponse.getBody().scenario().weatherFallbackUsed());

        // Verify /health endpoint
        ResponseEntity<?> healthResponse = controller.getHealth();
        assertEquals(HttpStatus.OK, healthResponse.getStatusCode());
    }

    @Test
    @DisplayName("Test 5: Live Open-Meteo Integration (Real network call to api.open-meteo.com)")
    void testLiveOpenMeteoRealCall() {
        OpenMeteoClient realClient = new OpenMeteoClient(objectMapper);
        // Call for Bengaluru (lat: 12.9716, lon: 77.5946)
        OpenMeteoClient.WeatherFetchResult result = realClient.fetchWeatherWithStatus(12.9716, 77.5946);
        assertNotNull(result);
        assertFalse(result.fallbackUsed(), "Live Open-Meteo call should succeed without fallback");
        assertTrue(result.rainMm() >= 0.0, "Rainfall must be non-negative");

        // Verify cache hit on consecutive call
        OpenMeteoClient.WeatherFetchResult cachedResult = realClient.fetchWeatherWithStatus(12.9716, 77.5946);
        assertEquals(result.rainMm(), cachedResult.rainMm(), 0.001);
        assertFalse(cachedResult.fallbackUsed());

        // Run full simulation in LIVE mode with real weather client
        CausalSimulatorService service = new CausalSimulatorService(registry, realClient);
        SimulateSwitchRequest req = new SimulateSwitchRequest(
                buyerBengaluru,
                supplierAAhmedabad,
                supplierBHubballi,
                3.5,
                false,
                WeatherMode.LIVE,
                null,
                null
        );
        SimulateSwitchResponse simRes = service.simulateSwitch(req);
        assertNotNull(simRes);
        assertFalse(simRes.scenario().weatherFallbackUsed(), "LIVE mode should use real weather");
        assertTrue(simRes.isStatisticallySignificant());
    }
}
