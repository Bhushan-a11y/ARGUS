package com.supplychainai.riskai;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.supplychainai.riskai.entity.Supplier;
import com.supplychainai.riskai.repository.SupplierRepository;

@RestController 
public class DocsPortalController {
    
    private final SupplierRepository supplierRepository;
    private final DocsUploadService docsUploadService;
    private final DocsStorageService docsStorageService;

    DocsPortalController(DocsUploadService docsUploadService, SupplierRepository supplierRepository, DocsStorageService docsStorageService) {
        this.docsUploadService = docsUploadService;
        this.supplierRepository = supplierRepository;
        this.docsStorageService = docsStorageService;
    }
    @PostMapping("/api/supplier/docs/upload")
    public ResponseEntity<?> uploadDocs(@RequestParam("file") MultipartFile file, @AuthenticationPrincipal  Jwt jwt) {
        System.out.println("UPLOAD CONTROLLER REACHED");//logs
        System.out.println("Role : "+jwt.getClaimAsString("role"));
        if (jwt == null || !"SUPPLIER".equals(jwt.getClaimAsString("role"))) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        String supplierId = jwt.getSubject();
        Supplier supplier = supplierRepository.findById(Long.valueOf(supplierId)).orElseThrow(() -> new IllegalArgumentException("Supplier not found"));
        String supplierName = supplier.getName();
        System.out.println("BEFORE UPLOADING");
        
        try {
            DocumentMetadata metadata = docsUploadService.uploadDocs(file, supplierId, supplierName);
            System.out.println("AFTER UPLOADING");
            return ResponseEntity.ok(Map.ofEntries(
                Map.entry("docId : ", metadata.idd().toHexString()),
                Map.entry("supId : ", metadata.supplierId()),
                Map.entry("supName", metadata.supplierName())));

        } catch (IllegalArgumentException e) {
            e.printStackTrace();
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(null);
        } catch (IOException e) {
            e.printStackTrace();
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(null);
        }
    }

    @GetMapping ("/api/admin/docs/download/{docId}")
    public ResponseEntity<Resource> downloadDocs(@PathVariable("docId") String docId, @AuthenticationPrincipal Jwt jwt) throws FileNotFoundException { 
        DocumentMetadata metadata = docsUploadService.getDocumentMetadata(docId);
        System.out.println("subject : "+jwt.getSubject().toString());
        if (jwt == null || !jwt.getSubject().toString().equals (metadata.supplierId())) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        try {
            DocumentMetadata documentMetadata = docsUploadService.getDocumentMetadata(docId);
            Resource resource = docsStorageService.getFileAsResource(documentMetadata.uuid());
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + metadata.ogName() + "\"")
                    .contentType(MediaType.parseMediaType(metadata.mimeType()))
                    .body(resource);
        } catch (FileNotFoundException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(null);
        } catch (IOException e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(null);
        }
    }


}
