package com.supplychainai.riskai;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.DoubleSummaryStatistics;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import javax.imageio.ImageIO;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.document.Document;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import com.fasterxml.jackson.databind.ObjectMapper;

public class BenchmarkHarnessTest {

    @TempDir
    Path tempStorageDir;

    private DocsStorageService storageService;
    private DocumentStorageProperties storageProperties;
    private RestTemplate ocrRestTemplate;
    private ObjectMapper objectMapper;
    private ThreadPoolTaskExecutor testExecutor;
    private DocumentExtractionService extractionService;

    @BeforeEach
    void setUp() {
        storageProperties = new DocumentStorageProperties(
                tempStorageDir.toAbsolutePath().toString(),
                Set.of("application/pdf", "image/jpeg", "image/png", "application/msword")
        );
        storageService = new DocsStorageService(storageProperties);

        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5000);
        factory.setReadTimeout(30000);
        ocrRestTemplate = new RestTemplate(factory);
        objectMapper = new ObjectMapper();

        testExecutor = new ThreadPoolTaskExecutor();
        testExecutor.setCorePoolSize(8);
        testExecutor.setMaxPoolSize(16);
        testExecutor.setQueueCapacity(200);
        testExecutor.setThreadNamePrefix("BenchWorker-");
        testExecutor.setDaemon(true);
        testExecutor.initialize();

        extractionService = new DocumentExtractionService(ocrRestTemplate, testExecutor, objectMapper);
    }

    @AfterEach
    void tearDown() {
        if (testExecutor != null) {
            testExecutor.destroy();
        }
    }

    // =========================================================================
    // 1. FILE UPLOAD SERVICE BENCHMARKS
    // =========================================================================
    @Test
    @DisplayName("BENCHMARK 1: File Upload Service Ingestion Throughput & Latency")
    void benchmarkFileUploadService() throws Exception {
        System.out.println("\n=======================================================");
        System.out.println(">>> STARTING BENCHMARK 1: FILE UPLOAD SERVICE <<<");
        System.out.println("=======================================================");

        int[] fileCounts = {50, 100, 200};
        int[] fileSizesKb = {50, 500, 2048}; // 50KB, 500KB, 2MB
        int[] concurrencyLevels = {1, 4, 8, 16};

        // Test across variable payloads
        for (int sizeKb : fileSizesKb) {
            byte[] payload = new byte[sizeKb * 1024];
            Arrays.fill(payload, (byte) 65); // Fill with 'A'
            int count = 100;

            List<Long> latencies = new ArrayList<>();
            long startTotal = System.nanoTime();

            for (int i = 0; i < count; i++) {
                String filename = "upload_bench_" + sizeKb + "kb_" + i + ".pdf";
                long t0 = System.nanoTime();
                try (InputStream is = new ByteArrayInputStream(payload)) {
                    String storedPath = storageService.storeDocs(is, filename);
                    assertNotNull(storedPath);
                }
                long t1 = System.nanoTime();
                latencies.add((t1 - t0) / 1_000_000); // ms
            }

            long totalDurationMs = (System.nanoTime() - startTotal) / 1_000_000;
            double throughputFilesPerSec = (count * 1000.0) / totalDurationMs;
            double totalMb = (count * sizeKb) / 1024.0;
            double throughputMbPerSec = (totalMb * 1000.0) / totalDurationMs;

            printStats("Sequential Ingestion [" + sizeKb + " KB files]", latencies, throughputFilesPerSec, throughputMbPerSec);
        }

        // Test Concurrency scaling (500 KB files)
        int sizeKb = 500;
        byte[] payload = new byte[sizeKb * 1024];
        Arrays.fill(payload, (byte) 66);

        for (int concurrency : concurrencyLevels) {
            int totalFiles = 100;
            ExecutorService pool = Executors.newFixedThreadPool(concurrency);
            CountDownLatch latch = new CountDownLatch(totalFiles);
            List<Long> latencies = Collections.synchronizedList(new ArrayList<>());

            long startTotal = System.nanoTime();
            for (int i = 0; i < totalFiles; i++) {
                final int fileIndex = i;
                pool.submit(() -> {
                    String filename = "concurrent_bench_c" + concurrency + "_" + fileIndex + ".pdf";
                    long t0 = System.nanoTime();
                    try (InputStream is = new ByteArrayInputStream(payload)) {
                        String storedPath = storageService.storeDocs(is, filename);
                        assertNotNull(storedPath);
                    } catch (IOException e) {
                        e.printStackTrace();
                    } finally {
                        long t1 = System.nanoTime();
                        latencies.add((t1 - t0) / 1_000_000);
                        latch.countDown();
                    }
                });
            }
            latch.await(60, TimeUnit.SECONDS);
            pool.shutdown();

            long totalDurationMs = (System.nanoTime() - startTotal) / 1_000_000;
            double throughputFilesPerSec = (totalFiles * 1000.0) / totalDurationMs;
            double totalMb = (totalFiles * sizeKb) / 1024.0;
            double throughputMbPerSec = (totalMb * 1000.0) / totalDurationMs;

            printStats("Concurrent Ingestion [Concurrency=" + concurrency + ", 500 KB]", latencies, throughputFilesPerSec, throughputMbPerSec);
        }
    }

    // =========================================================================
    // 2. OCR BENCHMARKS (RAPIDOCR SIDECAR & PDF RASTERIZATION)
    // =========================================================================
    @Test
    @DisplayName("BENCHMARK 2: OCR Pipeline Throughput, Latency & Concurrency")
    void benchmarkOcrPipeline() throws Exception {
        System.out.println("\n=======================================================");
        System.out.println(">>> STARTING BENCHMARK 2: OCR SIDECAR PIPELINE <<<");
        System.out.println("=======================================================");

        // Prepare synthetic scanned invoice images with realistic text
        List<byte[]> sampleImages = new ArrayList<>();
        String[] invoiceTexts = {
            "GLOBAL LOGISTICS CORP - INVOICE #83912 - TOTAL: $4,580.00",
            "BILL OF LADING - VESSEL: EVERGREEN - CONTAINER ID: MSKU9182341",
            "PACKING LIST: 400 PALLETS INDUSTRIAL RESIN - DESTINATION: ROTTERDAM",
            "SUPPLIER COMPLIANCE AUDIT CERTIFICATE - ISO 9001:2015 PASSED",
            "CUSTOMS DECLARATION TARIFF CODE: 8542.31.0000 - DUTY PAID: $1,200"
        };

        for (int i = 0; i < invoiceTexts.length; i++) {
            sampleImages.add(createSampleInvoiceImage(invoiceTexts[i]));
        }

        // Test 2.1: OCR Sequential Latency & Accuracy
        int testCount = 20;
        List<Long> ocrLatencies = new ArrayList<>();
        int successCount = 0;
        int totalCharsExtracted = 0;

        long startOcrSeq = System.nanoTime();
        for (int i = 0; i < testCount; i++) {
            byte[] imageBytes = sampleImages.get(i % sampleImages.size());
            long t0 = System.nanoTime();
            String ocrText = callSidecarDirect(imageBytes, "invoice_" + i + ".jpg");
            long t1 = System.nanoTime();

            ocrLatencies.add((t1 - t0) / 1_000_000);
            if (ocrText != null && !ocrText.isBlank()) {
                successCount++;
                totalCharsExtracted += ocrText.length();
            }
        }
        long totalOcrDurationMs = (System.nanoTime() - startOcrSeq) / 1_000_000;
        double throughputImgPerSec = (testCount * 1000.0) / totalOcrDurationMs;

        printStats("OCR Sequential Processing (" + testCount + " images)", ocrLatencies, throughputImgPerSec, (totalCharsExtracted * 1000.0) / totalOcrDurationMs);
        System.out.println("  -> Success Rate: " + ((successCount * 100.0) / testCount) + "%");
        System.out.println("  -> Total Chars Extracted: " + totalCharsExtracted);

        // Test 2.2: OCR Concurrency Scaling (1, 2, 4 concurrent threads)
        int[] concurrencyLevels = {1, 2, 4};
        for (int concurrency : concurrencyLevels) {
            int requests = 16;
            ExecutorService pool = Executors.newFixedThreadPool(concurrency);
            CountDownLatch latch = new CountDownLatch(requests);
            List<Long> concurrentLatencies = Collections.synchronizedList(new ArrayList<>());

            long startBatch = System.nanoTime();
            for (int i = 0; i < requests; i++) {
                final int idx = i;
                pool.submit(() -> {
                    byte[] imageBytes = sampleImages.get(idx % sampleImages.size());
                    long t0 = System.nanoTime();
                    String text = callSidecarDirect(imageBytes, "batch_" + idx + ".jpg");
                    long t1 = System.nanoTime();
                    concurrentLatencies.add((t1 - t0) / 1_000_000);
                    latch.countDown();
                });
            }
            latch.await(60, TimeUnit.SECONDS);
            pool.shutdown();

            long batchDurationMs = (System.nanoTime() - startBatch) / 1_000_000;
            double concurrentThroughput = (requests * 1000.0) / batchDurationMs;
            printStats("OCR Concurrent Execution [Concurrency=" + concurrency + ", Requests=" + requests + "]", concurrentLatencies, concurrentThroughput, 0);
        }

        // Test 2.3: PDFBox 300 DPI Rendering Benchmark
        List<Long> renderLatencies = new ArrayList<>();
        int pdfPagesToRender = 10;
        Path samplePdf = createTestPdfWithPages(pdfPagesToRender);

        long startRender = System.nanoTime();
        try (PDDocument doc = PDDocument.load(samplePdf.toFile())) {
            PDFRenderer renderer = new PDFRenderer(doc);
            for (int p = 0; p < pdfPagesToRender; p++) {
                long t0 = System.nanoTime();
                BufferedImage bim = renderer.renderImageWithDPI(p, 300.0f, ImageType.RGB);
                bim.flush();
                long t1 = System.nanoTime();
                renderLatencies.add((t1 - t0) / 1_000_000);
            }
        }
        long totalRenderMs = (System.nanoTime() - startRender) / 1_000_000;
        double renderPagesPerSec = (pdfPagesToRender * 1000.0) / totalRenderMs;
        printStats("PDFBox 300 DPI Rasterization (" + pdfPagesToRender + " pages)", renderLatencies, renderPagesPerSec, 0);
    }

    // =========================================================================
    // 3. NEWS FETCHING & DE-DUPLICATION BENCHMARKS
    // =========================================================================
    @Test
    @DisplayName("BENCHMARK 3: News RSS Fetching, Parsing & DSU De-Duplication")
    void benchmarkNewsFetchingAndDeduplication() throws Exception {
        System.out.println("\n=======================================================");
        System.out.println(">>> STARTING BENCHMARK 3: NEWS FETCHING & DE-DUPLICATION <<<");
        System.out.println("=======================================================");

        NewsApiData newsApiData = new NewsApiData("https://news.google.com/rss/search");
        NewsService newsService = new NewsService(newsApiData);
        EmbeddingService embeddingService = new EmbeddingService();

        String[] testQueries = {
            "semiconductor supply chain shortage",
            "global port congestion shipping delays",
            "automotive raw materials price inflation",
            "taiwan manufacturing disruption risk",
            "red sea shipping freight route crisis"
        };

        // 3.1: Live RSS Fetching & XML Parsing Speed
        List<Document> allFetchedDocuments = new ArrayList<>();
        List<Long> fetchLatencies = new ArrayList<>();

        System.out.println("\n--- Testing Live Google News RSS Fetching & Ingestion ---");
        for (String q : testQueries) {
            long t0 = System.nanoTime();
            List<Document> docs = newsService.getDocuments(q);
            long t1 = System.nanoTime();
            long durationMs = (t1 - t0) / 1_000_000;
            fetchLatencies.add(durationMs);
            allFetchedDocuments.addAll(docs);

            System.out.printf("  Query: '%-42s' -> Fetched: %2d docs | Latency: %4d ms%n",
                    q, docs.size(), durationMs);
        }

        double avgFetchLatency = fetchLatencies.stream().mapToLong(Long::longValue).average().orElse(0);
        int totalRawArticles = allFetchedDocuments.size();
        System.out.printf("Total Raw Articles Fetched: %d | Avg Latency per Query: %.1f ms%n", totalRawArticles, avgFetchLatency);

        // 3.2: Async Parallel Fan-Out Comparison
        System.out.println("\n--- Testing Async Parallel Fan-Out (RssAsyncService) ---");
        long asyncStart = System.nanoTime();
        List<CompletableFuture<List<Document>>> futures = new ArrayList<>();
        for (String q : testQueries) {
            futures.add(CompletableFuture.supplyAsync(() -> newsService.getDocuments(q), testExecutor));
        }
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        long asyncDurationMs = (System.nanoTime() - asyncStart) / 1_000_000;

        long sequentialTotalMs = fetchLatencies.stream().mapToLong(Long::longValue).sum();
        System.out.printf("Sequential Total Duration: %d ms%n", sequentialTotalMs);
        System.out.printf("Parallel Fan-Out Duration:  %d ms (Speedup: %.2fx)%n",
                asyncDurationMs, (double) sequentialTotalMs / Math.max(1, asyncDurationMs));

        // 3.3: De-Duplication & Clustering Analysis using DSU & Cosine Similarity
        System.out.println("\n--- Testing DSU + Cosine Similarity De-Duplication Clustering ---");

        // Measure text uniqueness across the raw articles
        Set<String> uniqueTitles = new HashSet<>();
        for (Document d : allFetchedDocuments) {
            uniqueTitles.add(d.getText().split("\n")[0].trim().toLowerCase());
        }
        int uniqueTitleCount = uniqueTitles.size();
        int exactDuplicates = totalRawArticles - uniqueTitleCount;
        double exactDeduplicationRate = totalRawArticles > 0 ? (exactDuplicates * 100.0 / totalRawArticles) : 0.0;

        System.out.printf("Exact Title De-duplication: %d duplicates found (%.1f%% duplicate rate)%n",
                exactDuplicates, exactDeduplicationRate);

        // 3.4: Semantic / Embedding Clustering with DSU (Benchmarking algorithm at N = 100, 250, 500, 1000)
        int[] dsuScales = {50, 100, 250, 500, 1000};
        double similarityThreshold = 0.7669; // From RiskService.java

        System.out.println("\n--- Benchmarking DSU Graph Clustering Execution Scaling ---");
        for (int n : dsuScales) {
            // Generate synthetic normalized embedding vectors (dimension = 384, typical small transformer)
            List<float[]> syntheticEmbeddings = generateSyntheticEmbeddings(n, 384, 0.25); // 25% near-duplicates

            long dsuStart = System.nanoTime();
            DSU dsu = new DSU(n);
            int pairwiseComparisons = 0;
            int duplicateEdges = 0;

            for (int i = 0; i < n; i++) {
                for (int j = i + 1; j < n; j++) {
                    pairwiseComparisons++;
                    double sim = embeddingService.cosineSimalarity(syntheticEmbeddings.get(i), syntheticEmbeddings.get(j));
                    if (sim > similarityThreshold) {
                        dsu.union(i, j);
                        duplicateEdges++;
                    }
                }
            }

            Map<Integer, List<Integer>> clusters = new HashMap<>();
            for (int i = 0; i < n; i++) {
                int root = dsu.find(i);
                clusters.computeIfAbsent(root, k -> new ArrayList<>()).add(i);
            }

            long dsuDurationMs = (System.nanoTime() - dsuStart) / 1_000_000;
            int uniqueClusters = clusters.size();
            int eliminatedDuplicates = n - uniqueClusters;
            double dedupRate = (eliminatedDuplicates * 100.0) / n;

            System.out.printf("  N=%-4d | Comparisons: %-7d | Edges: %-4d | Retained: %-4d | Dedup Rate: %5.1f%% | Duration: %4d ms%n",
                    n, pairwiseComparisons, duplicateEdges, uniqueClusters, dedupRate, dsuDurationMs);
        }
    }

    // =========================================================================
    // HELPER METHODS
    // =========================================================================
    private void printStats(String title, List<Long> latencies, double throughputPerSec, double secondaryThroughput) {
        if (latencies.isEmpty()) return;
        Collections.sort(latencies);
        long min = latencies.get(0);
        long max = latencies.get(latencies.size() - 1);
        long p50 = latencies.get((int) (latencies.size() * 0.50));
        long p90 = latencies.get((int) (latencies.size() * 0.90));
        long p95 = latencies.get((int) (latencies.size() * 0.95));
        long p99 = latencies.get((int) (latencies.size() * 0.99));
        double avg = latencies.stream().mapToLong(Long::longValue).average().orElse(0);

        System.out.println("\n-------------------------------------------------------------");
        System.out.println("METRIC: " + title);
        System.out.printf("  Samples: %d | Throughput: %.2f ops/sec", latencies.size(), throughputPerSec);
        if (secondaryThroughput > 0) {
            System.out.printf(" (%.2f MB/sec / chars/sec)", secondaryThroughput);
        }
        System.out.println();
        System.out.printf("  Min: %d ms | Mean: %.1f ms | Median(p50): %d ms | p90: %d ms | p95: %d ms | p99: %d ms | Max: %d ms%n",
                min, avg, p50, p90, p95, p99, max);
        System.out.println("-------------------------------------------------------------");
    }

    private String callSidecarDirect(byte[] imageBytes, String filename) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();

        ByteArrayResource resource = new ByteArrayResource(imageBytes) {
            @Override
            public String getFilename() {
                return filename;
            }
        };
        body.add("image_file", resource);
        HttpEntity<MultiValueMap<String, Object>> entity = new HttpEntity<>(body, headers);

        try {
            ResponseEntity<String> response = ocrRestTemplate.postForEntity("http://localhost:9090/ocr", entity, String.class);
            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                return response.getBody();
            }
        } catch (Exception e) {
            // Handle connection failure gracefully
        }
        return "";
    }

    private byte[] createSampleInvoiceImage(String text) throws IOException {
        int width = 700;
        int height = 180;
        BufferedImage bim = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = bim.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, width, height);
        g.setColor(Color.BLACK);
        g.setFont(new Font("Arial", Font.BOLD, 22));
        g.drawString(text, 25, 95);
        g.dispose();

        try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            ImageIO.write(bim, "JPEG", baos);
            return baos.toByteArray();
        } finally {
            bim.flush();
        }
    }

    private Path createTestPdfWithPages(int numPages) throws IOException {
        Path pdfPath = tempStorageDir.resolve("render_test_" + numPages + "_pages.pdf");
        try (PDDocument doc = new PDDocument()) {
            for (int i = 0; i < numPages; i++) {
                doc.addPage(new org.apache.pdfbox.pdmodel.PDPage());
            }
            doc.save(pdfPath.toFile());
        }
        return pdfPath;
    }

    private List<float[]> generateSyntheticEmbeddings(int count, int dim, double duplicateRatio) {
        Random rand = new Random(42);
        List<float[]> list = new ArrayList<>(count);

        int duplicateGroupCount = (int) (count * duplicateRatio);
        List<float[]> baseDuplicates = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            baseDuplicates.add(randomNormalizedVector(dim, rand));
        }

        for (int i = 0; i < count; i++) {
            if (i < duplicateGroupCount) {
                // Slightly perturbed vector (> 0.7669 cosine similarity)
                float[] base = baseDuplicates.get(i % baseDuplicates.size());
                float[] perturbed = new float[dim];
                for (int d = 0; d < dim; d++) {
                    perturbed[d] = base[d] + (float) ((rand.nextDouble() - 0.5) * 0.08);
                }
                list.add(normalize(perturbed));
            } else {
                list.add(randomNormalizedVector(dim, rand));
            }
        }
        return list;
    }

    private float[] randomNormalizedVector(int dim, Random rand) {
        float[] v = new float[dim];
        for (int i = 0; i < dim; i++) {
            v[i] = (float) (rand.nextDouble() - 0.5);
        }
        return normalize(v);
    }

    private float[] normalize(float[] v) {
        double sumSq = 0;
        for (float val : v) sumSq += val * val;
        double norm = Math.sqrt(sumSq);
        float[] normV = new float[v.length];
        for (int i = 0; i < v.length; i++) {
            normV[i] = (float) (v[i] / norm);
        }
        return normV;
    }
}
