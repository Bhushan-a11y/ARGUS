package com.supplychainai.riskai;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.client.RestTemplate;

import com.fasterxml.jackson.databind.ObjectMapper;

class LiveOcrSidecarIntegrationTest {

    private DocumentExtractionService extractionService;
    private ThreadPoolTaskExecutor executor;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        // Skip test if Docker container is not yet listening on localhost:9090
        boolean isSidecarRunning = isPortOpen("localhost", 9090, 1000);
        assumeTrue(isSidecarRunning, "RapidOCR sidecar is not running on port 9090. Start it with: docker compose up -d");

        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5000);
        factory.setReadTimeout(30000);
        RestTemplate realRestTemplate = new RestTemplate(factory);

        executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(10);
        executor.setThreadNamePrefix("LiveOcr-");
        executor.setDaemon(true);
        executor.initialize();

        extractionService = new DocumentExtractionService(realRestTemplate, executor, new ObjectMapper());
    }

    @AfterEach
    void tearDown() {
        if (executor != null) {
            executor.destroy();
        }
    }

    @Test
    @DisplayName("End-to-End Live OCR: Send synthetic scanned image to local RapidOCR container")
    void testLiveOcrWithImage() throws IOException, ExecutionException, InterruptedException, TimeoutException {
        // Create a synthetic image with high-contrast text simulating a scanned receipt
        int width = 600;
        int height = 200;
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, width, height);
        g.setColor(Color.BLACK);
        g.setFont(new Font("Arial", Font.BOLD, 28));
        g.drawString("INVOICE #98214 - TOTAL: $1,250.00", 30, 100);
        g.dispose();

        Path imagePath = tempDir.resolve("scanned-invoice.jpg");
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            ImageIO.write(image, "JPEG", baos);
            Files.write(imagePath, baos.toByteArray());
        } finally {
            image.flush();
        }

        CompletableFuture<String> future = extractionService.extractTextAsync(imagePath, "scanned-invoice.jpg");
        String extractedText = future.get(30, TimeUnit.SECONDS);

        assertNotNull(extractedText, "OCR result should not be null");
        assertFalse(extractedText.isBlank(), "OCR result should not be blank");
        System.out.println("=== LIVE OCR RESULT FROM DOCKER ===");
        System.out.println(extractedText);
        System.out.println("===================================");
    }

    private boolean isPortOpen(String host, int port, int timeoutMs) {
        try (Socket socket = new Socket()) {
            socket.connect(new java.net.InetSocketAddress(host, port), timeoutMs);
            return true;
        } catch (Exception ex) {
            return false;
        }
    }
}
