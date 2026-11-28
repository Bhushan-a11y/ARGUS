package com.supplychainai.riskai.causal;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.supplychainai.riskai.causal.dto.SimulateSwitchRequest;
import com.supplychainai.riskai.causal.dto.SimulateSwitchResponse;

import jakarta.validation.Valid;

/**
 * REST controller exposing endpoints for the EconML causal supplier-switch simulator
 * and engine health verification.
 */
@RestController
@RequestMapping("/api/causal")
@CrossOrigin(origins = "*")
public class CausalSimulatorController {

    private static final Logger log = LoggerFactory.getLogger(CausalSimulatorController.class);

    private final CausalSimulatorService causalSimulatorService;
    private final CausalModelRegistry modelRegistry;

    public CausalSimulatorController(
            CausalSimulatorService causalSimulatorService,
            CausalModelRegistry modelRegistry) {
        this.causalSimulatorService = causalSimulatorService;
        this.modelRegistry = modelRegistry;
    }

    @PostMapping("/simulate-switch")
    public ResponseEntity<SimulateSwitchResponse> simulateSwitch(
            @Valid @RequestBody SimulateSwitchRequest request) {
        log.info("Received causal switch simulation request: {} -> {}",
                request.supplierA().name(), request.supplierB().name());
        SimulateSwitchResponse response = causalSimulatorService.simulateSwitch(request);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> getHealth() {
        return ResponseEntity.ok(Map.of(
            "modelVersion", modelRegistry.getModelVersion(),
            "corridorCount", modelRegistry.getCorridorCount(),
            "status", "READY"
        ));
    }
}
