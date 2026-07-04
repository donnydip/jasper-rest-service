package com.devsec.jasperservice.service;

import com.devsec.jasperservice.config.DataSourceProperties;
import com.devsec.jasperservice.dto.ReportRequest;
import com.devsec.jasperservice.dto.StatusResponse;
import com.devsec.jasperservice.security.ApiKey;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.sf.jasperreports.engine.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;
import net.sf.jasperreports.export.SimpleExporterInput;
import net.sf.jasperreports.export.SimpleOutputStreamExporterOutput;
import net.sf.jasperreports.export.SimpleWriterExporterOutput;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;

import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.sql.Connection;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

@Service
public class AsyncReportService {

    private static final Logger LOGGER = LoggerFactory.getLogger(AsyncReportService.class);
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final Path reportOutputDir;
    private final DataSourceProperties dataSourceProperties;
    private final ThreadPoolTaskExecutor reportExecutor;
    private final ConcurrentHashMap<String, JasperReport> reportCache = new ConcurrentHashMap<>();
    private final long retentionHours;

    public AsyncReportService(StringRedisTemplate redisTemplate,
                              ObjectMapper objectMapper,
                              @Value("${report.output.dir}") String outputDir,
                              @Value("${report.retention-hours:24}") long retentionHours,
                              DataSourceProperties dataSourceProperties,
                              @Qualifier("reportExecutor") ThreadPoolTaskExecutor reportExecutor) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.reportOutputDir = Paths.get(outputDir);
        this.retentionHours = retentionHours;
        this.dataSourceProperties = dataSourceProperties;
        this.reportExecutor = reportExecutor;
        try {
            Files.createDirectories(this.reportOutputDir);
        } catch (Exception e) {
            throw new RuntimeException("Could not create report output directory!", e);
        }
    }
    
    public String queueReport(ReportRequest request, ApiKey apiKey) {
        // PERF-04: Add concurrency limits for jobs
        if (reportExecutor.getThreadPoolExecutor().getQueue().size() >= 25) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Server is too busy");
        }

        // Validate that the credentialKey exists before queueing
        if (!dataSourceProperties.getConnections().containsKey(request.getCredentialKey())) {
            throw new IllegalArgumentException("Invalid credentialKey: " + request.getCredentialKey());
        }

        // FIX-01: Enforce per-client credentialKey authorization
        if (apiKey.getAllowedCredentialKeys() == null || !apiKey.getAllowedCredentialKeys().contains(request.getCredentialKey())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "credentialKey not allowed for this client");
        }

        String jobId = UUID.randomUUID().toString();
        try {
            String requestJson = objectMapper.writeValueAsString(request);
            redisTemplate.opsForHash().put("job:" + jobId, "status", StatusResponse.Status.PENDING.name());
            redisTemplate.opsForHash().put("job:" + jobId, "request", requestJson);
            redisTemplate.convertAndSend("report-jobs", jobId);
            
            // REL-02: Add Redis job TTL
            redisTemplate.expire("job:" + jobId, 24, TimeUnit.HOURS);
        } catch (RedisConnectionFailureException e) {
            // REL-03: Handle Redis connection failures gracefully
            LOGGER.error("Redis connection failed while queuing job", e);
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Service is currently unavailable", e);
        } catch (Exception e) {
            LOGGER.error("Failed to queue job", e);
            throw new RuntimeException("Failed to queue job", e);
        }
        return jobId;
    }

    @Async("reportExecutor")
    public void processReport(String jobId) {
        Path outputFile = null;
        try {
            redisTemplate.opsForHash().put("job:" + jobId, "status", StatusResponse.Status.PROCESSING.name());
            redisTemplate.expire("job:" + jobId, 24, TimeUnit.HOURS);

            String requestJson = (String) redisTemplate.opsForHash().get("job:" + jobId, "request");
            if (requestJson == null) {
                LOGGER.error("Job {} request payload missing", jobId);
                return;
            }

            ReportRequest request = objectMapper.readValue(requestJson, ReportRequest.class);

            // PERF-02 & PERF-05: Use Hikari connection pool
            try (Connection connection = dataSourceProperties.getDataSource(request.getCredentialKey()).getConnection()) {
                String templatePath = "/reports/" + request.getJrxmlFileName() + ".jrxml";

                // PERF-03: Cache compiled JasperReport objects
                JasperReport jasperReport = reportCache.computeIfAbsent(templatePath, path -> {
                    try (InputStream reportStream = AsyncReportService.class.getResourceAsStream(path)) {
                        if (reportStream == null) {
                            throw new RuntimeException("JRXML template not found at: " + path);
                        }
                        return JasperCompileManager.compileReport(reportStream);
                    } catch (Exception e) {
                        throw new RuntimeException("Failed to compile report: " + path, e);
                    }
                });

                JasperPrint jasperPrint = JasperFillManager.fillReport(jasperReport, request.getJasperParameters(), connection);

                // Handle different output formats
                String outputFileName = StringUtils.cleanPath(request.getOutputFileName());
                String fileExtension = request.getOutputFormat().name().toLowerCase();
                outputFile = this.reportOutputDir.resolve(jobId + "_" + outputFileName + "." + fileExtension);

                switch (request.getOutputFormat()) {
                    case XLSX:
                        net.sf.jasperreports.engine.export.ooxml.JRXlsxExporter exporterXlsx = new net.sf.jasperreports.engine.export.ooxml.JRXlsxExporter();
                        exporterXlsx.setExporterInput(new SimpleExporterInput(jasperPrint));
                        exporterXlsx.setExporterOutput(new SimpleOutputStreamExporterOutput(outputFile.toFile()));
                        exporterXlsx.exportReport();
                        break;
                    case CSV:
                        net.sf.jasperreports.engine.export.JRCsvExporter exporterCsv = new net.sf.jasperreports.engine.export.JRCsvExporter();
                        exporterCsv.setExporterInput(new SimpleExporterInput(jasperPrint));
                        exporterCsv.setExporterOutput(new SimpleWriterExporterOutput(outputFile.toFile()));
                        exporterCsv.exportReport();
                        break;
                    case DOCX:
                        net.sf.jasperreports.engine.export.ooxml.JRDocxExporter exporterDocx = new net.sf.jasperreports.engine.export.ooxml.JRDocxExporter();
                        exporterDocx.setExporterInput(new SimpleExporterInput(jasperPrint));
                        exporterDocx.setExporterOutput(new SimpleOutputStreamExporterOutput(outputFile.toFile()));
                        exporterDocx.exportReport();
                        break;
                    case PDF:
                    default:
                        JasperExportManager.exportReportToPdfFile(jasperPrint, outputFile.toString());
                        break;
                }

                redisTemplate.opsForHash().put("job:" + jobId, "status", StatusResponse.Status.COMPLETE.name());
                redisTemplate.opsForHash().put("job:" + jobId, "filePath", outputFile.toString());
                redisTemplate.expire("job:" + jobId, 1, TimeUnit.HOURS); // Shorter TTL for COMPLETE jobs
                LOGGER.info("Job {} completed successfully. Report saved to {}", jobId, outputFile);
            }
        } catch (Exception e) {
            LOGGER.error("Job {} failed", jobId, e);

            // FIX-07: clean up any partial output file left behind by a failed export
            if (outputFile != null) {
                try {
                    Files.deleteIfExists(outputFile);
                } catch (Exception deleteEx) {
                    LOGGER.warn("Could not delete partial output file for job {}", jobId, deleteEx);
                }
            }

            // FIX-07: guard the FAILED-status Redis write against a Redis outage
            try {
                redisTemplate.opsForHash().put("job:" + jobId, "status", StatusResponse.Status.FAILED.name());
                redisTemplate.opsForHash().put("job:" + jobId, "error", e.getMessage());
                redisTemplate.expire("job:" + jobId, 24, TimeUnit.HOURS);
            } catch (RedisConnectionFailureException redisEx) {
                LOGGER.error("Could not record FAILED status for job {}: Redis unavailable", jobId, redisEx);
            }
        }
    }

    /**
     * FIX-07: sweep the report output directory for stale files (e.g. from jobs whose
     * completion status expired before download, or partial files that survived a crash)
     * and delete anything older than the configured retention window.
     */
    @Scheduled(fixedDelayString = "${report.retention-sweep-interval-ms:3600000}")
    public void cleanupExpiredReportFiles() {
        Instant cutoff = Instant.now().minus(retentionHours, ChronoUnit.HOURS);
        try (var files = Files.list(reportOutputDir)) {
            files.filter(Files::isRegularFile).forEach(path -> {
                try {
                    Instant lastModified = Files.getLastModifiedTime(path).toInstant();
                    if (lastModified.isBefore(cutoff)) {
                        Files.deleteIfExists(path);
                        LOGGER.info("Deleted expired report file {}", path);
                    }
                } catch (Exception e) {
                    LOGGER.warn("Could not evaluate/delete report file {}", path, e);
                }
            });
        } catch (Exception e) {
            LOGGER.error("Report retention sweep failed", e);
        }
    }

    public Resource getReportFile(String jobId) throws MalformedURLException {
        String filePathStr = (String) redisTemplate.opsForHash().get("job:" + jobId, "filePath");
        if (filePathStr == null) { return null; }
        Path filePath = Paths.get(filePathStr);
        Resource resource = new UrlResource(filePath.toUri());
        if (resource.exists() && resource.isReadable()) {
            return resource;
        } else {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Could not read the file!");
        }
    }

    public void deleteReportFile(String jobId) {
        try {
            String filePathStr = (String) redisTemplate.opsForHash().get("job:" + jobId, "filePath");
            if (filePathStr != null) {
                Files.deleteIfExists(Paths.get(filePathStr));
            }
            redisTemplate.delete("job:" + jobId);
        } catch (Exception e) {
            LOGGER.error("Error deleting report file for job {}", jobId, e);
        }
    }

    /**
     * FIX-02: Atomically claim a completed job's report file so that concurrent
     * downloads cannot both pass the COMPLETE check and race to delete the same file.
     * Only the caller whose redis delete actually removes the hash "wins" the claim;
     * the file is streamed with DELETE_ON_CLOSE so it is removed only after the
     * response body has been fully written.
     */
    public ReportFileClaim claimAndGetReportFile(String jobId) throws IOException {
        Map<Object, Object> jobData = redisTemplate.opsForHash().entries("job:" + jobId);
        if (jobData.isEmpty()) {
            return null;
        }

        String status = (String) jobData.get("status");
        if (status == null || !status.equals(StatusResponse.Status.COMPLETE.name())) {
            return null;
        }

        String filePathStr = (String) jobData.get("filePath");
        if (filePathStr == null) {
            return null;
        }

        Boolean claimed = redisTemplate.delete("job:" + jobId);
        if (!Boolean.TRUE.equals(claimed)) {
            // Another concurrent request already claimed this job.
            return null;
        }

        Path path = Paths.get(filePathStr);
        if (!Files.exists(path) || !Files.isReadable(path)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Report file not found");
        }

        ReportRequest request = null;
        String requestJson = (String) jobData.get("request");
        if (requestJson != null) {
            request = objectMapper.readValue(requestJson, ReportRequest.class);
        }

        long contentLength = Files.size(path);
        InputStreamResource resource = new InputStreamResource(
                Files.newInputStream(path, StandardOpenOption.DELETE_ON_CLOSE));
        return new ReportFileClaim(resource, request, contentLength);
    }

    public static class ReportFileClaim {
        private final Resource resource;
        private final ReportRequest request;
        private final long contentLength;

        public ReportFileClaim(Resource resource, ReportRequest request, long contentLength) {
            this.resource = resource;
            this.request = request;
            this.contentLength = contentLength;
        }

        public Resource getResource() { return resource; }
        public ReportRequest getRequest() { return request; }
        public long getContentLength() { return contentLength; }
    }
}
