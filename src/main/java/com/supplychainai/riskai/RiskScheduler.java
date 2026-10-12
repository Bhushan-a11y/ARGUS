package com.supplychainai.riskai;

import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@EnableScheduling 
@Configuration 
@Component
public class RiskScheduler {
    private final RiskService riskService;

    RiskScheduler(RiskService riskService) {
        this.riskService = riskService;
    }
     @Scheduled (fixedRate = 60000)// 60 seconds
    public void scheduleRiskTask() {
        List<RiskResponse> riskScore = riskService.getRiskScore();
        System.out.println("Risk Score: " + riskScore);
        
    }

}
