package com.devsec.jasperservice.service;

import com.devsec.jasperservice.config.DataSourceProperties;
import com.devsec.jasperservice.dto.ReportRequest;
import com.devsec.jasperservice.dto.StatusResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.sf.jasperreports.engine.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import net.sf.jasperreports.export.SimpleExporterInput;
import net.sf.jasperreports.export.SimpleOutputStreamExporterOutput;
import net.sf.jasperreports.export.SimpleWriterExporterOutput;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.InputStream;
import java.net.MalformedURLException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;

@Service
public class AsyncReportService {

    private static final Logger LOGGER = LoggerFactory.getLogger(AsyncReportService.class);
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Path reportOutputDir;
    private final DataSourceProperties dataSourceProperties;

    public AsyncReportService(StringRedisTemplate redisTemplate,
                              @Value("${report.output.dir}") String outputDir,
                              DataSourceProperties dataSourceProperties) {
        this.redisTemplate = redisTemplate;
        this.reportOutputDir = Paths.get(outputDir);
        this.dataSourceProperties = dataSourceProperties;
        try {
            Files.createDirectories(this.reportOutputDir);
        } catch (Exception e) {
            throw new RuntimeException("Could not create report output directory!", e);
        }
    }
    
    public String queueReport(ReportRequest request) {
        // Validate that the credentialKey exists before queueing
        if (!dataSourceProperties.getConnections().containsKey(request.getCredentialKey())) {
            throw new IllegalArgumentException("Invalid credentialKey: " + request.getCredentialKey());
        }

        String jobId = UUID.randomUUID().toString();
        try {
            String requestJson = objectMapper.writeValueAsString(request);
            redisTemplate.opsForHash().put("job:" + jobId, "status", StatusResponse.Status.PENDING.name());
            redisTemplate.opsForHash().put("job:" + jobId, "request", requestJson);
            redisTemplate.convertAndSend("report-jobs", jobId);
        } catch (Exception e) {
            LOGGER.error("Failed to queue job", e);
            throw new RuntimeException("Failed to queue job", e);
        }
        return jobId;
    }

    public void processReport(String jobId) {
        try {
            redisTemplate.opsForHash().put("job:" + jobId, "status", StatusResponse.Status.PROCESSING.name());

            String requestJson = (String) redisTemplate.opsForHash().get("job:" + jobId, "request");
            ReportRequest request = objectMapper.readValue(requestJson, ReportRequest.class);

            try (Connection connection = createDbConnection(request.getCredentialKey())) {
                String templatePath = "/reports/" + request.getJrxmlFileName() + ".jrxml";
                try (InputStream reportStream = AsyncReportService.class.getResourceAsStream(templatePath)) {
                    if (reportStream == null) {
                        throw new RuntimeException("JRXML template not found at: " + templatePath);
                    }
                    JasperReport jasperReport = JasperCompileManager.compileReport(reportStream);
                    JasperPrint jasperPrint = JasperFillManager.fillReport(jasperReport, request.getJasperParameters(), connection);
                    
                    // Handle different output formats
                    String outputFileName = StringUtils.cleanPath(request.getOutputFileName());
                    String fileExtension = request.getOutputFormat().name().toLowerCase();
                    Path outputFile = this.reportOutputDir.resolve(jobId + "_" + outputFileName + "." + fileExtension);

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
                    LOGGER.info("Job {} completed successfully. Report saved to {}", jobId, outputFile);
                }
            }
        } catch (Exception e) {
            LOGGER.error("Job {} failed", jobId, e);
            redisTemplate.opsForHash().put("job:" + jobId, "status", StatusResponse.Status.FAILED.name());
            redisTemplate.opsForHash().put("job:" + jobId, "error", e.getMessage());
        }
    }

    public Resource getReportFile(String jobId) throws MalformedURLException {
        String filePathStr = (String) redisTemplate.opsForHash().get("job:" + jobId, "filePath");
        if (filePathStr == null) { return null; }
        Path filePath = Paths.get(filePathStr);
        Resource resource = new UrlResource(filePath.toUri());
        if (resource.exists() || resource.isReadable()) {
            return resource;
        } else {
            throw new RuntimeException("Could not read the file!");
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

    private Connection createDbConnection(String credentialKey) throws SQLException, ClassNotFoundException {
        DataSourceProperties.Credentials creds = dataSourceProperties.getConnections().get(credentialKey);
        if (creds == null) {
            throw new IllegalArgumentException("No credentials found for key: " + credentialKey);
        }
        Class.forName(creds.getDriverClassName());
        return DriverManager.getConnection(creds.getUrl(), creds.getUsername(), creds.getPassword());
    }
}
