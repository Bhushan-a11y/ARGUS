package com.supplychainai.riskai.service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.supplychainai.riskai.entity.GstRegistryRecord;
import com.supplychainai.riskai.repository.GstRegistryRepository;

@Service
public class GstRegistryImporter {

    private static final Logger log = LoggerFactory.getLogger(GstRegistryImporter.class);

    private final GstRegistryRepository repository;

    public GstRegistryImporter(GstRegistryRepository repository) {
        this.repository = repository;
    }

    public record ImportSummary(int totalProcessed, int successfulUpserts, int failedRows) {}

    public ImportSummary importCsv(Path csvPath) throws IOException {
        try (InputStream is = Files.newInputStream(csvPath)) {
            return importCsv(is);
        }
    }

    public ImportSummary importCsv(InputStream inputStream) throws IOException {
        repository.initSchema();

        int processed = 0;
        int successful = 0;
        int failed = 0;

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            String headerLine = reader.readLine();
            if (headerLine == null) {
                return new ImportSummary(0, 0, 0);
            }

            String line;
            while ((line = reader.readLine()) != null) {
                if (line.trim().isEmpty()) {
                    continue;
                }
                processed++;
                try {
                    List<String> tokens = parseCsvLine(line);
                    if (tokens.size() < 10) {
                        log.warn("Skipping invalid CSV line [{}]: insufficient columns ({})", line, tokens.size());
                        failed++;
                        continue;
                    }

                    String gstin = tokens.get(0).trim().toUpperCase();
                    String legalName = tokens.get(1).trim();
                    String tradeName = tokens.get(2).trim();
                    String status = tokens.get(3).trim().toUpperCase();
                    String state = tokens.get(4).trim();
                    String regDateStr = tokens.get(5).trim();
                    String taxpayerType = tokens.get(6).trim();
                    String source = tokens.get(7).trim();
                    String datasetVersion = tokens.get(8).trim();
                    String lastUpdatedStr = tokens.get(9).trim();

                    LocalDate regDate = !regDateStr.isEmpty() ? LocalDate.parse(regDateStr, DateTimeFormatter.ISO_LOCAL_DATE) : null;
                    Instant lastUpdated = !lastUpdatedStr.isEmpty() ? Instant.parse(lastUpdatedStr) : Instant.now();

                    GstRegistryRecord record = new GstRegistryRecord(
                            null,
                            gstin,
                            legalName,
                            tradeName.isEmpty() ? null : tradeName,
                            status,
                            state,
                            regDate,
                            taxpayerType,
                            source,
                            datasetVersion,
                            lastUpdated
                    );

                    repository.upsert(record);
                    successful++;
                } catch (Exception ex) {
                    log.error("Failed to parse/upsert CSV line [{}]: {}", line, ex.getMessage());
                    failed++;
                }
            }
        }

        log.info("Prototype GST registry CSV import completed: {} processed, {} upserted, {} failed.",
                processed, successful, failed);
        return new ImportSummary(processed, successful, failed);
    }

    /**
     * RFC 4180 compliant CSV line tokenizer handling embedded commas within double quotes.
     */
    public static List<String> parseCsvLine(String line) {
        List<String> result = new ArrayList<>();
        StringBuilder curVal = new StringBuilder();
        boolean inQuotes = false;

        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (ch == '\"') {
                if (inQuotes && i + 1 < line.length() && line.charAt(i + 1) == '\"') {
                    curVal.append('\"');
                    i++; // skip escaped quote
                } else {
                    inQuotes = !inQuotes;
                }
            } else if (ch == ',' && !inQuotes) {
                result.add(curVal.toString());
                curVal.setLength(0);
            } else {
                curVal.append(ch);
            }
        }
        result.add(curVal.toString());
        return result;
    }
}
