package com.supplychainai.riskai.causal;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Service to query rolling 3-day precipitation from Open-Meteo with short timeouts,
 * in-memory spatial-temporal caching, and safe fallback to 0.0 mm on network failure.
 */
@Service
public class OpenMeteoClient {

    private static final Logger log = LoggerFactory.getLogger(OpenMeteoClient.class);
    private static final String OPEN_METEO_URL = 
        "https://api.open-meteo.com/v1/forecast?latitude={lat}&longitude={lon}&daily=precipitation_sum&past_days=2&forecast_days=1&timezone=auto";
    private static final Duration CACHE_DURATION = Duration.ofMinutes(30);

    public record WeatherFetchResult(double rainMm, boolean fallbackUsed) {}
    private record CachedRain(double rainMm, Instant expiresAt) {}

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final ConcurrentMap<String, CachedRain> cache = new ConcurrentHashMap<>();

    public OpenMeteoClient(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(2));
        factory.setReadTimeout(Duration.ofSeconds(3));
        this.restClient = RestClient.builder()
                .requestFactory(factory)
                .build();
    }

    public OpenMeteoClient(RestClient restClient, ObjectMapper objectMapper) {
        this.restClient = restClient;
        this.objectMapper = objectMapper;
    }

    /**
     * Primary method returning the 3-day cumulative precipitation in mm.
     * Falls back to 0.0 mm if the API call fails or times out.
     */
    public double fetchRolling3DayRainMm(double lat, double lon) {
        return fetchWeatherWithStatus(lat, lon).rainMm();
    }

    /**
     * Extended method returning both the rain amount and whether a fallback was triggered.
     */
    public WeatherFetchResult fetchWeatherWithStatus(double lat, double lon) {
        String cacheKey = String.format(Locale.ROOT, "%.2f,%.2f", lat, lon);
        CachedRain cached = cache.get(cacheKey);
        if (cached != null && Instant.now().isBefore(cached.expiresAt())) {
            log.debug("Weather cache hit for [{}]: {} mm", cacheKey, cached.rainMm());
            return new WeatherFetchResult(cached.rainMm(), false);
        }

        try {
            log.info("Fetching Open-Meteo 3-day weather for lat={}, lon={}", lat, lon);
            String responseBody = restClient.get()
                    .uri(OPEN_METEO_URL, lat, lon)
                    .retrieve()
                    .body(String.class);

            if (responseBody == null || responseBody.isBlank()) {
                log.warn("Open-Meteo returned empty body for lat={}, lon={}. Falling back to 0.0 mm.", lat, lon);
                return new WeatherFetchResult(0.0, true);
            }

            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode precipArray = root.path("daily").path("precipitation_sum");
            if (!precipArray.isArray()) {
                log.warn("Missing 'daily.precipitation_sum' array in Open-Meteo response for lat={}, lon={}", lat, lon);
                return new WeatherFetchResult(0.0, true);
            }

            double sum = 0.0;
            for (JsonNode val : precipArray) {
                if (val != null && !val.isNull() && val.isNumber()) {
                    sum += val.asDouble();
                }
            }

            cache.put(cacheKey, new CachedRain(sum, Instant.now().plus(CACHE_DURATION)));
            log.info("Open-Meteo weather resolved for [{}]: {} mm (cached for 30m)", cacheKey, sum);
            return new WeatherFetchResult(sum, false);

        } catch (Exception ex) {
            log.warn("Open-Meteo call failed for lat={}, lon={}: {}. Falling back to 0.0 mm.",
                    lat, lon, ex.getMessage());
            return new WeatherFetchResult(0.0, true);
        }
    }

    public void clearCache() {
        cache.clear();
    }
}
