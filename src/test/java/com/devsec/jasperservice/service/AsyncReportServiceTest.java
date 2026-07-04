package com.devsec.jasperservice.service;

import com.devsec.jasperservice.config.DataSourceProperties;
import com.devsec.jasperservice.dto.ReportRequest;
import com.devsec.jasperservice.dto.StatusResponse;
import com.devsec.jasperservice.security.ApiKey;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.server.ResponseStatusException;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AsyncReportServiceTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private HashOperations<String, Object, Object> hashOperations;

    @Mock
    private DataSourceProperties dataSourceProperties;

    @Mock
    private ThreadPoolTaskExecutor reportExecutor;

    private AsyncReportService asyncReportService;

    private static final ApiKey ALLOWED_API_KEY =
            new ApiKey("test-key", "TestClient", List.of("web001"), true, System.currentTimeMillis());

    private static final ApiKey DISALLOWED_API_KEY =
            new ApiKey("other-key", "OtherClient", List.of("web002"), true, System.currentTimeMillis());

    @BeforeEach
    void setUp() {
        // Mock the connections map with a valid key
        Map<String, DataSourceProperties.Credentials> connections = new HashMap<>();
        DataSourceProperties.Credentials creds = new DataSourceProperties.Credentials();
        creds.setUrl("jdbc:postgresql://localhost:5432/testdb");
        creds.setUsername("testuser");
        creds.setPassword("testpass");
        creds.setDriverClassName("org.postgresql.Driver");
        connections.put("web001", creds);
        lenient().when(dataSourceProperties.getConnections()).thenReturn(connections);

        // Mock executor's thread pool to not be full
        ThreadPoolExecutor mockThreadPool = new ThreadPoolExecutor(
                2, 5, 60, TimeUnit.SECONDS, new LinkedBlockingQueue<>(25));
        lenient().when(reportExecutor.getThreadPoolExecutor()).thenReturn(mockThreadPool);

        asyncReportService = new AsyncReportService(
                redisTemplate, new ObjectMapper(), System.getProperty("java.io.tmpdir") + "/jasper-test-reports",
                24, dataSourceProperties, reportExecutor);
    }

    @Test
    void queueReport_withValidRequestAndAllowedKey_shouldReturnJobId() {
        // Arrange
        when(redisTemplate.opsForHash()).thenReturn(hashOperations);
        when(redisTemplate.expire(anyString(), anyLong(), any(TimeUnit.class))).thenReturn(true);

        ReportRequest request = new ReportRequest();
        request.setJrxmlFileName("Simple_Report");
        request.setOutputFileName("TestReport");
        request.setOutputFormat(ReportRequest.OutputFormat.PDF);
        request.setCredentialKey("web001");

        // Act
        String jobId = asyncReportService.queueReport(request, ALLOWED_API_KEY);

        // Assert
        assertNotNull(jobId);
        assertFalse(jobId.isEmpty());
        verify(hashOperations).put(eq("job:" + jobId), eq("status"), eq(StatusResponse.Status.PENDING.name()));
        verify(hashOperations).put(eq("job:" + jobId), eq("request"), anyString());
        verify(redisTemplate).convertAndSend(eq("report-jobs"), eq(jobId));
        verify(redisTemplate).expire(eq("job:" + jobId), eq(24L), eq(TimeUnit.HOURS));
    }

    @Test
    void queueReport_withDisallowedCredentialKey_shouldThrowForbidden() {
        // Arrange
        ReportRequest request = new ReportRequest();
        request.setJrxmlFileName("Simple_Report");
        request.setOutputFileName("TestReport");
        request.setCredentialKey("web001");

        // Act & Assert — DISALLOWED_API_KEY is only allowed to use "web002"
        ResponseStatusException exception = assertThrows(ResponseStatusException.class,
                () -> asyncReportService.queueReport(request, DISALLOWED_API_KEY));
        assertEquals(403, exception.getStatusCode().value());
    }

    @Test
    void queueReport_withNullAllowedCredentialKeys_shouldThrowForbidden() {
        // Arrange
        ApiKey apiKeyWithNoAllowedKeys = new ApiKey("key", "Client", null, true, System.currentTimeMillis());
        ReportRequest request = new ReportRequest();
        request.setJrxmlFileName("Simple_Report");
        request.setOutputFileName("TestReport");
        request.setCredentialKey("web001");

        // Act & Assert
        ResponseStatusException exception = assertThrows(ResponseStatusException.class,
                () -> asyncReportService.queueReport(request, apiKeyWithNoAllowedKeys));
        assertEquals(403, exception.getStatusCode().value());
    }

    @Test
    void queueReport_withInvalidCredentialKey_shouldThrowIllegalArgumentException() {
        // Arrange
        ReportRequest request = new ReportRequest();
        request.setJrxmlFileName("Simple_Report");
        request.setOutputFileName("TestReport");
        request.setCredentialKey("nonexistent_key");

        // Act & Assert
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> asyncReportService.queueReport(request, ALLOWED_API_KEY));
        assertTrue(exception.getMessage().contains("nonexistent_key"));
    }

    @Test
    void queueReport_whenQueueIsFull_shouldThrowTooManyRequests() {
        // Arrange — fill the queue to capacity
        LinkedBlockingQueue<Runnable> fullQueue = new LinkedBlockingQueue<>(25);
        for (int i = 0; i < 25; i++) {
            fullQueue.offer(() -> {});
        }
        ThreadPoolExecutor fullExecutor = new ThreadPoolExecutor(
                2, 5, 60, TimeUnit.SECONDS, fullQueue);
        when(reportExecutor.getThreadPoolExecutor()).thenReturn(fullExecutor);

        ReportRequest request = new ReportRequest();
        request.setJrxmlFileName("Simple_Report");
        request.setOutputFileName("TestReport");
        request.setCredentialKey("web001");

        // Act & Assert
        ResponseStatusException exception = assertThrows(ResponseStatusException.class,
                () -> asyncReportService.queueReport(request, ALLOWED_API_KEY));
        assertEquals(429, exception.getStatusCode().value());
    }

    @Test
    void getReportFile_withNullFilePath_shouldReturnNull() throws Exception {
        // Arrange
        when(redisTemplate.opsForHash()).thenReturn(hashOperations);
        when(hashOperations.get("job:test-id", "filePath")).thenReturn(null);

        // Act
        var result = asyncReportService.getReportFile("test-id");

        // Assert
        assertNull(result);
    }

    @Test
    void deleteReportFile_shouldDeleteRedisHash() {
        // Arrange
        when(redisTemplate.opsForHash()).thenReturn(hashOperations);
        when(hashOperations.get("job:test-id", "filePath")).thenReturn(null);

        // Act
        asyncReportService.deleteReportFile("test-id");

        // Assert
        verify(redisTemplate).delete("job:test-id");
    }

    @Test
    void claimAndGetReportFile_withNoJobData_shouldReturnNull() throws Exception {
        when(redisTemplate.opsForHash()).thenReturn(hashOperations);
        when(hashOperations.entries("job:missing")).thenReturn(new HashMap<>());

        var result = asyncReportService.claimAndGetReportFile("missing");

        assertNull(result);
    }

    @Test
    void claimAndGetReportFile_whenNotComplete_shouldReturnNull() throws Exception {
        when(redisTemplate.opsForHash()).thenReturn(hashOperations);
        Map<Object, Object> jobData = new HashMap<>();
        jobData.put("status", StatusResponse.Status.PROCESSING.name());
        when(hashOperations.entries("job:pending")).thenReturn(jobData);

        var result = asyncReportService.claimAndGetReportFile("pending");

        assertNull(result);
    }

    @Test
    void claimAndGetReportFile_whenLoserOfRace_shouldReturnNull() throws Exception {
        // Simulate two concurrent downloads: the redis "delete" only succeeds for one of them.
        when(redisTemplate.opsForHash()).thenReturn(hashOperations);
        Map<Object, Object> jobData = new HashMap<>();
        jobData.put("status", StatusResponse.Status.COMPLETE.name());
        jobData.put("filePath", "/tmp/nonexistent-report-file.pdf");
        when(hashOperations.entries("job:race")).thenReturn(jobData);
        when(redisTemplate.delete("job:race")).thenReturn(false);

        var result = asyncReportService.claimAndGetReportFile("race");

        assertNull(result);
    }
}
