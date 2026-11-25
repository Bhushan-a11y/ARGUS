package com.supplychainai.riskai.config;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.supplychainai.riskai.service.GstRegistryImporter;

@Configuration
public class GstPrototypeDataInitializer {

    private static final Logger log = LoggerFactory.getLogger(GstPrototypeDataInitializer.class);

    @Bean
    public ApplicationRunner initGstPrototypeRegistry(GstRegistryImporter importer) {
        return args -> {
            Path csvPath = Paths.get("data", "prototype_gst_registry_v2 new.csv");
            if (Files.exists(csvPath)) {
                log.info("Loading prototype GST registry from {}", csvPath.toAbsolutePath());
                importer.importCsv(csvPath);
            } else {
                log.info("Prototype GST registry CSV not found at default path [{}]; skipping startup import.", csvPath);
            }
        };
    }
}
