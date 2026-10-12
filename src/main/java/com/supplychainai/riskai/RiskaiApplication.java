package com.supplychainai.riskai;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication
@EnableConfigurationProperties(NewsApiData.class)
public class RiskaiApplication {

public static void main(String[] args) {
		SpringApplication.run(RiskaiApplication.class, args);
	}
}
