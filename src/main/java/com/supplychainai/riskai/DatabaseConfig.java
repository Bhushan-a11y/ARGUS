package com.supplychainai.riskai;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

@Configuration
public class DatabaseConfig {

    @Bean
    @Primary
    @ConfigurationProperties("app.datasource.tidb")
    public DataSource tidbDataSource() {
        return DataSourceBuilder.create().build();
    }

    @Bean
    @ConfigurationProperties("app.datasource.pgvector")
    public DataSource pgvectorDataSource() {
        return DataSourceBuilder.create().build();
    }

    @Bean
    public JdbcTemplate pgvectorJdbcTemplate(@Qualifier("pgvectorDataSource") DataSource pgvectorDataSource) {
        return new JdbcTemplate(pgvectorDataSource);
    }

}
