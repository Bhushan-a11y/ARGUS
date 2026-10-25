package com.supplychainai.riskai;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;

import org.bson.types.ObjectId;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service 
public class DocsUploadService {
    private final DocsStorageService docsStorageService;
    private DocumentRepository documentRepository;
    private DocumentStorageProperties properties;

   

    public DocsUploadService(DocsStorageService docsStorageService, DocumentRepository documentRepository,
            DocumentStorageProperties properties) {
        this.docsStorageService = docsStorageService;
        this.documentRepository = documentRepository;
        this.properties = properties;
    }



    public DocumentMetadata uploadDocs(MultipartFile file, String ownerId, String ownerName) throws IOException
    {
        if(file.isEmpty()|| !properties.allowedMimeTypes().contains(file.getContentType()))
        {
            throw new IllegalArgumentException("Invalid file type");
        }
        String storagePath;
        try(InputStream stream = file.getInputStream())
        {
            storagePath = docsStorageService.storeDocs(stream, file.getOriginalFilename());
        }
        DocumentMetadata metadata = new DocumentMetadata(ObjectId.get(), file.getOriginalFilename(), storagePath, file.getContentType(), ownerId, ownerName, file.getSize(), Instant.now());
        return documentRepository.save(metadata);
    
    }

    public DocumentMetadata getDocumentMetadata(String docId)
        throws FileNotFoundException {
            System.out.println("Received docId: [" + docId + "]");
System.out.println("Length: " +
        (docId == null ? "null" : docId.length()));
System.out.println("Valid ObjectId: " + ObjectId.isValid(docId));

    if (!ObjectId.isValid(docId)) {
        throw new IllegalArgumentException("Invalid MongoDB document ID");
    }

    ObjectId objectId = new ObjectId(docId);

    return documentRepository.findById(objectId)
            .orElseThrow(
                () -> new FileNotFoundException("Requested file not found")
            );
}


}
