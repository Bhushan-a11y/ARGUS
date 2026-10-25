package com.supplychainai.riskai;

import java.time.Instant;

import org.bson.types.ObjectId;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

@Document(collection = "docs_metadata")
public record DocumentMetadata(@Id ObjectId idd, 
    String ogName,
    String uuid,
    String mimeType,
    String supplierId,
    String supplierName,
    long size,
    Instant sentAt
) {}
