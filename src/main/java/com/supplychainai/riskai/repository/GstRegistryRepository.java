package com.supplychainai.riskai.repository;

import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import com.supplychainai.riskai.entity.GstRegistryRecord;

@Repository
public class GstRegistryRepository {

    private static final Logger log = LoggerFactory.getLogger(GstRegistryRepository.class);

    private final JdbcTemplate jdbcTemplate;

    public GstRegistryRepository(@Qualifier("pgvectorJdbcTemplate") JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void initSchema() {
        String createTableSql = """
            CREATE TABLE IF NOT EXISTS prototype_gst_registry (
                id BIGSERIAL PRIMARY KEY,
                gstin VARCHAR(15) NOT NULL UNIQUE,
                legal_name VARCHAR(255) NOT NULL,
                trade_name VARCHAR(255),
                status VARCHAR(50) NOT NULL,
                state VARCHAR(100) NOT NULL,
                registration_date DATE,
                taxpayer_type VARCHAR(50),
                source VARCHAR(50) NOT NULL,
                dataset_version VARCHAR(20) NOT NULL,
                last_updated TIMESTAMPTZ NOT NULL
            );
            CREATE INDEX IF NOT EXISTS idx_prototype_gst_registry_gstin ON prototype_gst_registry(gstin);
            """;
        try {
            jdbcTemplate.execute(createTableSql);
            log.info("PostgreSQL table prototype_gst_registry initialized successfully.");
        } catch (Exception ex) {
            log.warn("Notice: could not initialize prototype_gst_registry schema on startup (DB may be offline): {}", ex.getMessage());
        }
    }

    public void upsert(GstRegistryRecord record) {
        String upsertSql = """
            INSERT INTO prototype_gst_registry (
                gstin, legal_name, trade_name, status, state, registration_date, taxpayer_type, source, dataset_version, last_updated
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (gstin) DO UPDATE SET
                legal_name = EXCLUDED.legal_name,
                trade_name = EXCLUDED.trade_name,
                status = EXCLUDED.status,
                state = EXCLUDED.state,
                registration_date = EXCLUDED.registration_date,
                taxpayer_type = EXCLUDED.taxpayer_type,
                source = EXCLUDED.source,
                dataset_version = EXCLUDED.dataset_version,
                last_updated = EXCLUDED.last_updated;
            """;

        Date sqlRegDate = record.registrationDate() != null ? Date.valueOf(record.registrationDate()) : null;
        Timestamp sqlLastUpdated = record.lastUpdated() != null ? Timestamp.from(record.lastUpdated()) : Timestamp.from(Instant.now());

        jdbcTemplate.update(
                upsertSql,
                record.gstin(),
                record.legalName(),
                record.tradeName(),
                record.status(),
                record.state(),
                sqlRegDate,
                record.taxpayerType(),
                record.source(),
                record.datasetVersion(),
                sqlLastUpdated
        );
    }

    public Optional<GstRegistryRecord> findByGstin(String gstin) {
        if (gstin == null || gstin.isBlank()) {
            return Optional.empty();
        }
        String sql = "SELECT id, gstin, legal_name, trade_name, status, state, registration_date, taxpayer_type, source, dataset_version, last_updated FROM prototype_gst_registry WHERE gstin = ?";
        try {
            GstRegistryRecord record = jdbcTemplate.queryForObject(sql, new GstRegistryRowMapper(), gstin.trim().toUpperCase());
            return Optional.ofNullable(record);
        } catch (EmptyResultDataAccessException ex) {
            return Optional.empty();
        } catch (Exception ex) {
            log.error("Error looking up GSTIN [{}] in prototype registry: {}", gstin, ex.getMessage());
            return Optional.empty();
        }
    }

    public long count() {
        Long c = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM prototype_gst_registry", Long.class);
        return c != null ? c : 0L;
    }

    private static class GstRegistryRowMapper implements RowMapper<GstRegistryRecord> {
        @Override
        public GstRegistryRecord mapRow(ResultSet rs, int rowNum) throws SQLException {
            Date regDate = rs.getDate("registration_date");
            LocalDate localRegDate = regDate != null ? regDate.toLocalDate() : null;
            Timestamp lastUpdatedTs = rs.getTimestamp("last_updated");
            Instant lastUpdated = lastUpdatedTs != null ? lastUpdatedTs.toInstant() : null;

            return new GstRegistryRecord(
                    rs.getLong("id"),
                    rs.getString("gstin"),
                    rs.getString("legal_name"),
                    rs.getString("trade_name"),
                    rs.getString("status"),
                    rs.getString("state"),
                    localRegDate,
                    rs.getString("taxpayer_type"),
                    rs.getString("source"),
                    rs.getString("dataset_version"),
                    lastUpdated
            );
        }
    }
}
