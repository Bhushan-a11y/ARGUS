package com.supplychainai.riskai;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "google.news.api")
public record NewsApiData(String url) {

}
