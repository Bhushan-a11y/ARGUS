package com.supplychainai.riskai;

import java.time.Instant;

import org.bson.types.ObjectId;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

@Document(collection = "docs_metadata")
public record DocumentMetadata(
    @Id ObjectId idd, //primary key
    String ogName,     //og name of the file
    String uuid,       //relative storage path
    String mimeType,   //file type(png,pdf)
    String supplierId,
    String supplierName,
    long size,
    Instant sentAt,
    String extractedText,
    ExtractionStatus extractionStatus
) {
    public DocumentMetadata(ObjectId idd, String ogName, String uuid, String mimeType,
                            String supplierId, String supplierName, long size, Instant sentAt) {
        this(idd, ogName, uuid, mimeType, supplierId, supplierName, size, sentAt, null, ExtractionStatus.PENDING);
    }

    public DocumentMetadata withExtractedText(String extractedText, ExtractionStatus status) {
        return new DocumentMetadata(idd, ogName, uuid, mimeType, supplierId, supplierName, size, sentAt, extractedText, status);
    }

    public DocumentMetadata withStatus(ExtractionStatus status) {
        return new DocumentMetadata(idd, ogName, uuid, mimeType, supplierId, supplierName, size, sentAt, extractedText, status);
    }
}
