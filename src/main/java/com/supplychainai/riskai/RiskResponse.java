package com.supplychainai.riskai;

public record RiskResponse(String articleTitle, int riskScore, String riskLevel, String reason) {

}