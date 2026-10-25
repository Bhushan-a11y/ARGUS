package com.supplychainai.riskai;

import java.util.Set;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.docs-storage")
public record DocumentStorageProperties(
    String basePath,
    Set<String> allowedMimeTypes
) {}
