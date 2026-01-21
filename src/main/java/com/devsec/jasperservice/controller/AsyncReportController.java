package com.devsec.jasperservice.controller;

import com.devsec.jasperservice.dto.ReportRequest;
import com.devsec.jasperservice.dto.ReportResponse;
import com.devsec.jasperservice.dto.StatusResponse;
import com.devsec.jasperservice.service.AsyncReportService;
import org.springframework.core.io.Resource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.MalformedURLException;
import java.util.Map;

@RestController
@RequestMapping("/api/v2/report")
public class AsyncReportController {

    private final AsyncReportService asyncReportService;
    private final StringRedisTemplate redisTemplate;

    public AsyncReportController(AsyncReportService asyncReportService, StringRedisTemplate redisTemplate) {
        this.asyncReportService = asyncReportService;
        this.redisTemplate = redisTemplate;
    }

    @PostMapping("/async")
    public ResponseEntity<ReportResponse> submitReport(@RequestBody ReportRequest request) {
        String jobId = asyncReportService.queueReport(request);
        return ResponseEntity.accepted().body(new ReportResponse(jobId));
    }

    @GetMapping("/status/{jobId}")
    public ResponseEntity<StatusResponse> getReportStatus(@PathVariable String jobId) {
        Map<Object, Object> jobData = redisTemplate.opsForHash().entries("job:" + jobId);
        if (jobData.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        StatusResponse.Status status = StatusResponse.Status.valueOf((String) jobData.get("status"));
        String message = "Job is " + status.name();
        if (status == StatusResponse.Status.FAILED) {
            message = "Job failed: " + jobData.get("error");
        }

        StatusResponse response = new StatusResponse(status, message);
        if (status == StatusResponse.Status.COMPLETE) {
            response.setDownloadUrl("/api/v2/report/download/" + jobId);
        }

        return ResponseEntity.ok(response);
    }

    @GetMapping("/download/{jobId}")
    public ResponseEntity<Resource> downloadReport(@PathVariable String jobId) {
        String status = (String) redisTemplate.opsForHash().get("job:" + jobId, "status");
        if (status == null || !status.equals(StatusResponse.Status.COMPLETE.name())) {
            return ResponseEntity.status(404).body(null); // Not found or not ready
        }

        try {
            Resource file = asyncReportService.getReportFile(jobId);
                    String outputFileName = "report"; 
                    String fileExtension = "pdf"; // Default
                    String contentType = "application/pdf"; // Default
            
                    String requestJson = (String) redisTemplate.opsForHash().get("job:" + jobId, "request");
                    if (requestJson != null) {
                        ReportRequest originalRequest = new com.fasterxml.jackson.databind.ObjectMapper().readValue(requestJson, ReportRequest.class);
                        outputFileName = originalRequest.getOutputFileName();
                        fileExtension = originalRequest.getOutputFormat().name().toLowerCase();
                        contentType = getContentType(originalRequest.getOutputFormat());
                    }
            
                    // File will be deleted after being sent
                    asyncReportService.deleteReportFile(jobId);
            
                    return ResponseEntity.ok()
                            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + outputFileName + "." + fileExtension + "\"")
                            .header(HttpHeaders.CONTENT_TYPE, contentType)
                            .body(file);
                } catch (Exception e) {
                    return ResponseEntity.internalServerError().build();
                }
            }
            
            private String getContentType(ReportRequest.OutputFormat format) {
                return switch (format) {
                    case XLSX -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
                    case CSV -> "text/csv";
                    case DOCX -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
                    case PDF -> "application/pdf";
                };
            }
            
}
