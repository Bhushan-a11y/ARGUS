package com.supplychainai.riskai;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.time.Instant;

import org.bson.types.ObjectId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service 
public class DocsUploadService {

    private static final Logger log = LoggerFactory.getLogger(DocsUploadService.class);

    private final DocsStorageService docsStorageService;
    private final DocumentRepository documentRepository;
    private final DocumentStorageProperties properties;
    private final DocumentExtractionService extractionService;

    public DocsUploadService(
            DocsStorageService docsStorageService,
            DocumentRepository documentRepository,
            DocumentStorageProperties properties,
            DocumentExtractionService extractionService) {
        this.docsStorageService = docsStorageService;
        this.documentRepository = documentRepository;
        this.properties = properties;
        this.extractionService = extractionService;
    }

    public DocumentMetadata uploadDocs(MultipartFile file, String ownerId, String ownerName) throws IOException {
        if (file.isEmpty() || !properties.allowedMimeTypes().contains(file.getContentType())) {
            throw new IllegalArgumentException("Invalid file type");
        }

        String storagePath;
        try (InputStream stream = file.getInputStream()) {
            storagePath = docsStorageService.storeDocs(stream, file.getOriginalFilename());
        }

        DocumentMetadata metadata = new DocumentMetadata(
                ObjectId.get(),
                file.getOriginalFilename(),
                storagePath,
                file.getContentType(),
                ownerId,
                ownerName,
                file.getSize(),
                Instant.now()
        );
        DocumentMetadata savedMetadata = documentRepository.save(metadata);

        // Asynchronously trigger text extraction on the persisted file path
        Path storedFilePath = docsStorageService.resolvePath(storagePath);

        // Update status to PROCESSING when extraction begins
        updateStatus(savedMetadata.idd(), ExtractionStatus.PROCESSING);

        extractionService.extractTextAsync(storedFilePath, file.getOriginalFilename())
            .thenAccept(extractedText -> {
                log.info("Document [{}] extraction completed ({} chars)", savedMetadata.idd(), extractedText.length());
                updateExtractedText(savedMetadata.idd(), extractedText, ExtractionStatus.COMPLETED);
            })
            .exceptionally(ex -> {
                log.error("Async extraction failed for document [{}]", savedMetadata.idd(), ex);
                updateExtractedText(savedMetadata.idd(), null, ExtractionStatus.FAILED);
                return null;
            });

        // Return saved metadata immediately without blocking HTTP response
        return savedMetadata;
    }

    private void updateStatus(ObjectId docId, ExtractionStatus status) {
        documentRepository.findById(docId).ifPresent(existing -> {
            DocumentMetadata updated = existing.withStatus(status);
            documentRepository.save(updated);
        });
    }

    private void updateExtractedText(ObjectId docId, String extractedText, ExtractionStatus status) {
        documentRepository.findById(docId).ifPresent(existing -> {
            DocumentMetadata updated = existing.withExtractedText(extractedText, status);
            documentRepository.save(updated);
        });
    }

    public DocumentMetadata getDocumentMetadata(String docId) throws FileNotFoundException {
        System.out.println("Received docId: [" + docId + "]");
        System.out.println("Length: " + (docId == null ? "null" : docId.length()));
        System.out.println("Valid ObjectId: " + ObjectId.isValid(docId));

        if (!ObjectId.isValid(docId)) {
            throw new IllegalArgumentException("Invalid MongoDB document ID");
        }

        ObjectId objectId = new ObjectId(docId);

        return documentRepository.findById(objectId)
                .orElseThrow(() -> new FileNotFoundException("Requested file not found"));
    }
}
