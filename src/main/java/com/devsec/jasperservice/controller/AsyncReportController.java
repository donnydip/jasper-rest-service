package com.devsec.jasperservice.controller;

import com.devsec.jasperservice.dto.ReportRequest;
import com.devsec.jasperservice.dto.ReportResponse;
import com.devsec.jasperservice.dto.StatusResponse;
import com.devsec.jasperservice.service.AsyncReportService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.core.io.Resource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v2/report")
@Tag(name = "Report Generation", description = "Endpoints for asynchronous report creation and retrieval")
public class AsyncReportController {

    private final AsyncReportService asyncReportService;
    private final StringRedisTemplate redisTemplate;

    public AsyncReportController(AsyncReportService asyncReportService, StringRedisTemplate redisTemplate) {
        this.asyncReportService = asyncReportService;
        this.redisTemplate = redisTemplate;
    }

    @PostMapping("/async")
    @Operation(summary = "Submit a new report generation job",
               description = "Queues a new report for generation. The request is processed asynchronously. A job ID is returned immediately.")
    @ApiResponses({
            @ApiResponse(responseCode = "202", description = "Accepted. The job has been queued successfully."),
            @ApiResponse(responseCode = "400", description = "Bad Request. Validation failed or invalid credentialKey."),
            @ApiResponse(responseCode = "401", description = "Unauthorized. Bearer token missing or invalid."),
            @ApiResponse(responseCode = "429", description = "Too Many Requests. Rate limit exceeded.")
    })
    public ResponseEntity<ReportResponse> submitReport(@Valid @RequestBody ReportRequest request) {
        String jobId = asyncReportService.queueReport(request);
        return ResponseEntity.accepted().body(new ReportResponse(jobId));
    }

    @GetMapping("/status/{jobId}")
    @Operation(summary = "Get the status of a report job",
               description = "Poll this endpoint to check the current status of a report generation job.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "OK. The current status of the job."),
            @ApiResponse(responseCode = "401", description = "Unauthorized."),
            @ApiResponse(responseCode = "404", description = "Not Found. The specified job ID does not exist.")
    })
    public ResponseEntity<StatusResponse> getReportStatus(
            @Parameter(description = "The ID of the job to check", required = true) @PathVariable String jobId) {
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
    @Operation(summary = "Download the generated report",
               description = "Downloads the completed report file. This is a one-time operation — the file is deleted after download.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "OK. The report file is returned."),
            @ApiResponse(responseCode = "401", description = "Unauthorized."),
            @ApiResponse(responseCode = "404", description = "Not Found. Job does not exist or is not yet complete.")
    })
    public ResponseEntity<Resource> downloadReport(
            @Parameter(description = "The ID of the completed job", required = true) @PathVariable String jobId) {
        String status = (String) redisTemplate.opsForHash().get("job:" + jobId, "status");
        if (status == null || !status.equals(StatusResponse.Status.COMPLETE.name())) {
            return ResponseEntity.status(404).body(null);
        }

        try {
            Resource file = asyncReportService.getReportFile(jobId);
            String outputFileName = "report";
            String fileExtension = "pdf";
            String contentType = "application/pdf";

            String requestJson = (String) redisTemplate.opsForHash().get("job:" + jobId, "request");
            if (requestJson != null) {
                ReportRequest originalRequest = new com.fasterxml.jackson.databind.ObjectMapper().readValue(requestJson, ReportRequest.class);
                outputFileName = originalRequest.getOutputFileName();
                fileExtension = originalRequest.getOutputFormat().name().toLowerCase();
                contentType = getContentType(originalRequest.getOutputFormat());
            }

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

