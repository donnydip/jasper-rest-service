package com.devsec.jasperservice.controller;

import com.devsec.jasperservice.config.CorsConfig;
import com.devsec.jasperservice.config.LoggingInterceptor;
import com.devsec.jasperservice.dto.StatusResponse;
import com.devsec.jasperservice.exception.GlobalExceptionHandler;
import com.devsec.jasperservice.security.ApiKey;
import com.devsec.jasperservice.security.ApiKeyService;
import com.devsec.jasperservice.security.RateLimitFilter;
import com.devsec.jasperservice.security.SecurityConfig;
import com.devsec.jasperservice.service.AsyncReportService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.InputStreamResource;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

import java.io.ByteArrayInputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(AsyncReportController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class, CorsConfig.class, LoggingInterceptor.class})
class AsyncReportControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AsyncReportService asyncReportService;

    @MockitoBean
    private StringRedisTemplate redisTemplate;

    @SuppressWarnings("rawtypes")
    @MockitoBean
    private HashOperations hashOperations;

    @MockitoBean
    private ApiKeyService apiKeyService;

    @MockitoBean
    private RateLimitFilter rateLimitFilter;

    private static final String VALID_API_KEY = "test-valid-key-123";

    @SuppressWarnings("unchecked")
    private void setupValidApiKey() {
        ApiKey apiKey = new ApiKey(VALID_API_KEY, "TestClient",
                List.of("web001"), true, System.currentTimeMillis());
        when(apiKeyService.getApiKey(VALID_API_KEY)).thenReturn(Optional.of(apiKey));
    }

    @org.junit.jupiter.api.BeforeEach
    void setUpFilter() throws Exception {
        org.mockito.Mockito.doAnswer(invocation -> {
            jakarta.servlet.FilterChain chain = invocation.getArgument(2);
            chain.doFilter(invocation.getArgument(0), invocation.getArgument(1));
            return null;
        }).when(rateLimitFilter).doFilter(any(), any(), any());
    }

    @Test
    void submitReport_withoutAuth_shouldReturn401() throws Exception {
        mockMvc.perform(post("/api/v2/report/async")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "jrxmlFileName": "Simple_Report",
                                    "outputFileName": "TestReport",
                                    "credentialKey": "web001"
                                }
                                """))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void submitReport_withValidRequest_shouldReturn202() throws Exception {
        setupValidApiKey();
        when(asyncReportService.queueReport(any(), any())).thenReturn("test-job-id");

        mockMvc.perform(post("/api/v2/report/async")
                        .header("Authorization", "Bearer " + VALID_API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "jrxmlFileName": "Simple_Report",
                                    "outputFileName": "TestReport",
                                    "credentialKey": "web001"
                                }
                                """))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.jobId").value("test-job-id"))
                .andExpect(jsonPath("$.statusUrl").value("/api/v2/report/status/test-job-id"));
    }

    @Test
    void submitReport_withDisallowedCredentialKey_shouldReturn403() throws Exception {
        setupValidApiKey();
        when(asyncReportService.queueReport(any(), any()))
                .thenThrow(new ResponseStatusException(HttpStatus.FORBIDDEN, "credentialKey not allowed for this client"));

        mockMvc.perform(post("/api/v2/report/async")
                        .header("Authorization", "Bearer " + VALID_API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "jrxmlFileName": "Simple_Report",
                                    "outputFileName": "TestReport",
                                    "credentialKey": "web002"
                                }
                                """))
                .andExpect(status().isForbidden());
    }

    @Test
    void submitReport_withInvalidInput_shouldReturn400() throws Exception {
        setupValidApiKey();

        mockMvc.perform(post("/api/v2/report/async")
                        .header("Authorization", "Bearer " + VALID_API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "jrxmlFileName": "../../etc/passwd",
                                    "outputFileName": "TestReport",
                                    "credentialKey": "web001"
                                }
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void submitReport_withMissingFields_shouldReturn400() throws Exception {
        setupValidApiKey();

        mockMvc.perform(post("/api/v2/report/async")
                        .header("Authorization", "Bearer " + VALID_API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @SuppressWarnings("unchecked")
    void getReportStatus_withExistingJob_shouldReturn200() throws Exception {
        setupValidApiKey();
        when(redisTemplate.opsForHash()).thenReturn(hashOperations);
        Map<Object, Object> jobData = new HashMap<>();
        jobData.put("status", StatusResponse.Status.PROCESSING.name());
        when(hashOperations.entries("job:test-job-id")).thenReturn(jobData);

        mockMvc.perform(get("/api/v2/report/status/test-job-id")
                        .header("Authorization", "Bearer " + VALID_API_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PROCESSING"))
                .andExpect(jsonPath("$.message").value("Job is PROCESSING"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void getReportStatus_withNonExistentJob_shouldReturn404() throws Exception {
        setupValidApiKey();
        when(redisTemplate.opsForHash()).thenReturn(hashOperations);
        when(hashOperations.entries("job:nonexistent")).thenReturn(new HashMap<>());

        mockMvc.perform(get("/api/v2/report/status/nonexistent")
                        .header("Authorization", "Bearer " + VALID_API_KEY))
                .andExpect(status().isNotFound());
    }

    @Test
    @SuppressWarnings("unchecked")
    void getReportStatus_whenComplete_shouldIncludeDownloadUrl() throws Exception {
        setupValidApiKey();
        when(redisTemplate.opsForHash()).thenReturn(hashOperations);
        Map<Object, Object> jobData = new HashMap<>();
        jobData.put("status", StatusResponse.Status.COMPLETE.name());
        when(hashOperations.entries("job:done-job")).thenReturn(jobData);

        mockMvc.perform(get("/api/v2/report/status/done-job")
                        .header("Authorization", "Bearer " + VALID_API_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETE"))
                .andExpect(jsonPath("$.downloadUrl").value("/api/v2/report/download/done-job"));
    }

    @Test
    void downloadReport_whenNotComplete_shouldReturn404() throws Exception {
        setupValidApiKey();
        when(asyncReportService.claimAndGetReportFile("pending-job")).thenReturn(null);

        mockMvc.perform(get("/api/v2/report/download/pending-job")
                        .header("Authorization", "Bearer " + VALID_API_KEY))
                .andExpect(status().isNotFound());
    }

    @Test
    void downloadReport_whenComplete_shouldReturnFile() throws Exception {
        setupValidApiKey();

        InputStreamResource resource = new InputStreamResource(new ByteArrayInputStream("PDF content".getBytes()));
        AsyncReportService.ReportFileClaim claim = new AsyncReportService.ReportFileClaim(resource, null, 11);
        when(asyncReportService.claimAndGetReportFile("ready-job")).thenReturn(claim);

        mockMvc.perform(get("/api/v2/report/download/ready-job")
                        .header("Authorization", "Bearer " + VALID_API_KEY))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", "attachment; filename=\"report.pdf\""));
    }

    @Test
    void downloadReport_whenSecondConcurrentRequest_shouldReturn404() throws Exception {
        // FIX-02: the loser of the atomic-claim race gets a 404, not a 500 or a double-delete.
        setupValidApiKey();
        when(asyncReportService.claimAndGetReportFile("raced-job")).thenReturn(null);

        mockMvc.perform(get("/api/v2/report/download/raced-job")
                        .header("Authorization", "Bearer " + VALID_API_KEY))
                .andExpect(status().isNotFound());
    }
}
