package com.supplychainai.riskai;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;

import javax.imageio.ImageIO;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.tika.Tika;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

@Service
public class DocumentExtractionService {

    private static final Logger log = LoggerFactory.getLogger(DocumentExtractionService.class);
    private static final int TEXT_LENGTH_THRESHOLD = 50;
    private static final float PDF_RENDER_DPI = 300.0f;

    private final Tika tika;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final Executor extractionExecutor;

    @Value("${app.ocr.sidecar-url:http://localhost:9090/ocr}")
    private String ocrSidecarUrl = "http://localhost:9090/ocr";

    public DocumentExtractionService(
            @Qualifier("ocrRestTemplate") RestTemplate restTemplate,
            @Qualifier("documentExtractionExecutor") Executor extractionExecutor,
            ObjectMapper objectMapper) {
        this.tika = new Tika();
        this.restTemplate = restTemplate;
        this.extractionExecutor = extractionExecutor;
        this.objectMapper = objectMapper;
    }

    /**
     * Asynchronously executes text extraction on the dedicated extraction thread pool.
     */
    public CompletableFuture<String> extractTextAsync(Path filePath, String originalFilename) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return extractText(filePath, originalFilename);
            } catch (Exception ex) {
                log.error("Text extraction pipeline failed for file: [{}]", originalFilename, ex);
                throw new CompletionException(ex);
            }
        }, extractionExecutor);
    }

    /**
     * Synchronous extraction engine handling MIME classification and routing.
     */
    public String extractText(Path filePath, String originalFilename) throws IOException {
        if (!Files.exists(filePath)) {
            throw new IllegalArgumentException("File does not exist: " + filePath);
        }

        String trueMimeType = detectMimeType(filePath, originalFilename);
        log.info("Detected MIME [{}] for file [{}]", trueMimeType, originalFilename);

        // FAST LANE: Word Document (.docx / .doc)
        if (trueMimeType.contains("wordprocessingml") || trueMimeType.equals("application/msword")) {
            String nativeText = extractWordNatively(filePath);
            if (nativeText != null && nativeText.trim().length() > TEXT_LENGTH_THRESHOLD) {
                log.info("Fast Lane: Native Word extraction completed ({} chars). Bypassing OCR.", nativeText.trim().length());
                return nativeText.trim();
            }
            return nativeText != null ? nativeText.trim() : "";
        }

        // FAST LANE & SLOW LANE: PDF Handling
        if ("application/pdf".equalsIgnoreCase(trueMimeType)) {
            return processPdf(filePath, originalFilename);
        }

        // SLOW LANE: Image Direct OCR
        if (trueMimeType.startsWith("image/")) {
            log.info("Slow Lane: Direct image OCR fallback for [{}]", originalFilename);
            byte[] imageBytes = Files.readAllBytes(filePath);
            return callOcrSidecar(imageBytes, originalFilename != null ? originalFilename : "image.jpg");
        }

        // Fallback for text/plain or other supported native formats
        try (InputStream is = Files.newInputStream(filePath)) {
            String fallbackText = tika.parseToString(is);
            return fallbackText != null ? fallbackText.trim() : "";
        } catch (Exception ex) {
            log.warn("Generic fallback extraction failed for [{}]: {}", originalFilename, ex.getMessage());
            return "";
        }
    }

    private String detectMimeType(Path filePath, String originalFilename) throws IOException {
        Metadata metadata = new Metadata();
        if (originalFilename != null) {
            metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, originalFilename);
        }
        try (InputStream is = Files.newInputStream(filePath)) {
            return tika.detect(is, metadata);
        }
    }

    private String extractWordNatively(Path filePath) {
        try (InputStream is = Files.newInputStream(filePath)) {
            return tika.parseToString(is);
        } catch (Exception ex) {
            log.warn("Native Word extraction failed: {}", ex.getMessage());
            return "";
        }
    }

    private String processPdf(Path filePath, String originalFilename) throws IOException {
        try (PDDocument document = PDDocument.load(filePath.toFile())) {
            // Attempt native text extraction first
            PDFTextStripper stripper = new PDFTextStripper();
            String extractedText = stripper.getText(document);

            if (extractedText != null && extractedText.trim().length() > TEXT_LENGTH_THRESHOLD) {
                log.info("Fast Lane: Digital-born PDF extracted natively ({} chars). Bypassing OCR.", extractedText.trim().length());
                return extractedText.trim();
            }

            // Scanned PDF fallback: render pages to 300 DPI JPEG and send sequentially to OCR
            log.info("Slow Lane: PDF [{}] yielded <= {} chars. Executing 300 DPI OCR pipeline.", originalFilename, TEXT_LENGTH_THRESHOLD);
            return renderScannedPdfAndOcr(document);
        }
    }

    private String renderScannedPdfAndOcr(PDDocument document) throws IOException {
        PDFRenderer renderer = new PDFRenderer(document);
        int pageCount = document.getNumberOfPages();
        StringBuilder combinedText = new StringBuilder();

        for (int pageIndex = 0; pageIndex < pageCount; pageIndex++) {
            BufferedImage pageImage = renderer.renderImageWithDPI(pageIndex, PDF_RENDER_DPI, ImageType.RGB);
            byte[] jpegBytes;

            try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
                ImageIO.write(pageImage, "JPEG", baos);
                baos.flush();
                jpegBytes = baos.toByteArray();
            } finally {
                pageImage.flush(); // Free native unmanaged graphics memory
            }

            String pageText = callOcrSidecar(jpegBytes, "page_" + (pageIndex + 1) + ".jpg");
            if (pageText != null && !pageText.isBlank()) {
                if (combinedText.length() > 0) {
                    combinedText.append("\n");
                }
                combinedText.append(pageText.trim());
            }
        }

        return combinedText.toString();
    }

    private String callOcrSidecar(byte[] imageBytes, String filename) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);

        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();

        // Anonymous subclass overriding getFilename() ensures Spring RestTemplate
        // sets 'filename="..."' in the Content-Disposition header required by OCR sidecars.
        ByteArrayResource fileResource = new ByteArrayResource(imageBytes) {
            @Override
            public String getFilename() {
                return (filename != null && !filename.isBlank()) ? filename : "image.jpg";
            }
        };
        body.add("image_file", fileResource);

        HttpEntity<MultiValueMap<String, Object>> requestEntity = new HttpEntity<>(body, headers);

        try {
            ResponseEntity<String> response = restTemplate.postForEntity(ocrSidecarUrl, requestEntity, String.class);
            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                return parseOcrResponse(response.getBody());
            } else {
                log.warn("OCR sidecar call failed with status: {}", response.getStatusCode());
                return "";
            }
        } catch (RestClientException ex) {
            log.error("Failed to connect to RapidOCR sidecar at [{}]: {}", ocrSidecarUrl, ex.getMessage());
            return "";
        }
    }

    private String parseOcrResponse(String responseBody) {
        if (responseBody == null || responseBody.isBlank()) {
            return "";
        }
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            if (root.isObject()) {
                if (root.has("data")) {
                    JsonNode dataNode = root.get("data");
                    if (dataNode.isTextual()) {
                        return dataNode.asText();
                    } else if (dataNode.isArray()) {
                        return extractTextFromArray(dataNode);
                    }
                }
                if (root.has("text")) {
                    return root.get("text").asText();
                }
            } else if (root.isArray()) {
                return extractTextFromArray(root);
            }
            return responseBody;
        } catch (Exception ex) {
            // Plain text response fallback
            return responseBody.trim();
        }
    }

    private String extractTextFromArray(JsonNode arrayNode) {
        StringBuilder sb = new StringBuilder();
        for (JsonNode item : arrayNode) {
            if (item.isTextual()) {
                if (sb.length() > 0) sb.append("\n");
                sb.append(item.asText());
            } else if (item.isObject() && item.has("text")) {
                if (sb.length() > 0) sb.append("\n");
                sb.append(item.get("text").asText());
            } else if (item.isArray() && item.size() >= 2 && item.get(1).isTextual()) {
                // RapidOCR / PaddleOCR standard tuple: [bbox, text, score]
                if (sb.length() > 0) sb.append("\n");
                sb.append(item.get(1).asText());
            }
        }
        return sb.toString();
    }
}

