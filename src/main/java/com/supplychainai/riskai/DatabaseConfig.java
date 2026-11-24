package com.supplychainai.riskai;

import javax.sql.DataSource;

import com.zaxxer.hikari.HikariDataSource;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration
public class DatabaseConfig {

    @Bean
    @Primary
    @ConfigurationProperties("app.datasource.tidb")
    public org.springframework.boot.jdbc.autoconfigure.DataSourceProperties tidbDataSourceProperties() {
        return new org.springframework.boot.jdbc.autoconfigure.DataSourceProperties();
    }

    @Bean
    @Primary
    @ConfigurationProperties("app.datasource.tidb.configuration")
    public HikariDataSource tidbDataSource(
            @Qualifier("tidbDataSourceProperties")
            DataSourceProperties properties) {
        return properties.initializeDataSourceBuilder()
                .type(HikariDataSource.class)
                .build();
    }

    @Bean
    @ConfigurationProperties("app.datasource.pgvector")
    public DataSourceProperties pgvectorDataSourceProperties() {
        return new DataSourceProperties();
    }

    @Bean
    @ConfigurationProperties("app.datasource.pgvector.configuration")
    public HikariDataSource pgvectorDataSource(
            @Qualifier("pgvectorDataSourceProperties")
            DataSourceProperties properties) {
        return properties.initializeDataSourceBuilder()
                .type(HikariDataSource.class)
                .build();
    }

    @Bean
    public JdbcTemplate pgvectorJdbcTemplate(
            @Qualifier("pgvectorDataSource")
            DataSource pgvectorDataSource) {
        return new JdbcTemplate(pgvectorDataSource);
    }
}