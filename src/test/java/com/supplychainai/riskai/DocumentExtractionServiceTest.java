package com.supplychainai.riskai;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.client.RestTemplate;

import com.fasterxml.jackson.databind.ObjectMapper;

class DocumentExtractionServiceTest {

    private DocumentExtractionService extractionService;
    private ThreadPoolTaskExecutor testExecutor;
    private ObjectMapper objectMapper;
    private AtomicBoolean sidecarCalled;
    private RestTemplate stubRestTemplate;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        sidecarCalled = new AtomicBoolean(false);

        // Stub RestTemplate directly to avoid JDK 24 dynamic agent attachment / named pipe issues
        stubRestTemplate = new RestTemplate() {
            @Override
            @SuppressWarnings("unchecked")
            public <T> ResponseEntity<T> postForEntity(String url, Object request, Class<T> responseType, Object... uriVariables) {
                sidecarCalled.set(true);
                String stubJson = "{\"code\": 0, \"data\": [{\"text\": \"Extracted text from OCR sidecar\", \"score\": 0.99}]}";
                return new ResponseEntity<>((T) stubJson, HttpStatus.OK);
            }
        };

        testExecutor = new ThreadPoolTaskExecutor();
        testExecutor.setCorePoolSize(2);
        testExecutor.setMaxPoolSize(4);
        testExecutor.setQueueCapacity(10);
        testExecutor.setThreadNamePrefix("TestExtract-");
        testExecutor.setDaemon(true); // Ensure daemon threads do not prevent JVM shutdown
        testExecutor.initialize();

        extractionService = new DocumentExtractionService(stubRestTemplate, testExecutor, objectMapper);
    }

    @AfterEach
    void tearDown() {
        if (testExecutor != null) {
            testExecutor.destroy();
        }
    }

    @Test
    @DisplayName("Fast Lane: Digital PDF native extraction extracts text > 50 chars")
    void testDigitalPdfNativeExtraction() throws IOException {
        Path pdfPath = tempDir.resolve("test-digital.pdf");
        String sampleParagraph = "Supply Chain Risk AI: Autonomous supplier health monitoring, real-time risk score analysis, and automated document ingestion pipeline.";

        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            try (PDPageContentStream contentStream = new PDPageContentStream(doc, page)) {
                contentStream.beginText();
                contentStream.setFont(PDType1Font.HELVETICA_BOLD, 12);
                contentStream.newLineAtOffset(50, 700);
                contentStream.showText(sampleParagraph);
                contentStream.endText();
            }
            doc.save(pdfPath.toFile());
        }

        String extractedText = extractionService.extractText(pdfPath, "test-digital.pdf");

        assertNotNull(extractedText);
        assertTrue(extractedText.length() > 50, "Extracted text length should exceed 50 characters");
        assertTrue(extractedText.contains("Supply Chain Risk AI"), "Extracted text should contain document contents");
        assertFalse(sidecarCalled.get(), "Fast lane must bypass OCR sidecar entirely");
    }

    @Test
    @DisplayName("Real PDF Test: Extract text asynchronously from supplier PDF (Downloads folder)")
    void testAsyncExtractionWithRealPdf() throws ExecutionException, InterruptedException, TimeoutException {
        Path realPdfPath = Paths.get("src/test/resources/test-supplier-doc.pdf");
        assertTrue(Files.exists(realPdfPath), "Test supplier PDF must exist in test resources");

        CompletableFuture<String> future = extractionService.extractTextAsync(realPdfPath, realPdfPath.getFileName().toString());
        assertNotNull(future, "CompletableFuture must not be null");

        String extracted = future.get(10, TimeUnit.SECONDS);
        assertNotNull(extracted, "Extracted text must not be null");
        assertFalse(extracted.isBlank(), "Extracted text should not be blank");
        assertTrue(extracted.length() > 50, "Extracted text should exceed 50 characters");

        System.out.println("=== REAL PDF EXTRACTION RESULT ===");
        System.out.println("Source: " + realPdfPath.getFileName());
        System.out.println("Characters extracted: " + extracted.length());
        System.out.println("Preview: " + extracted.substring(0, Math.min(extracted.length(), 250)) + "...");
        System.out.println("==================================");
    }

    @Test
    @DisplayName("Slow Lane: Scanned/Empty PDF renders pages and falls back to RapidOCR sidecar")
    void testScannedPdfOcrFallback() throws IOException {
        Path blankPdf = tempDir.resolve("scanned-blank.pdf");
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            doc.save(blankPdf.toFile());
        }

        String result = extractionService.extractText(blankPdf, "scanned-blank.pdf");

        assertNotNull(result);
        assertTrue(result.contains("Extracted text from OCR sidecar"), "Result must contain OCR text");
        assertTrue(sidecarCalled.get(), "OCR sidecar must have been invoked for scanned PDF");
    }
}
