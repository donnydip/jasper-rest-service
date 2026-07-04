package com.devsec.jasperservice.service;

import com.devsec.jasperservice.config.DataSourceProperties;
import com.devsec.jasperservice.dto.ReportRequest;
import com.devsec.jasperservice.dto.StatusResponse;
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
                redisTemplate, System.getProperty("java.io.tmpdir") + "/jasper-test-reports",
                dataSourceProperties, reportExecutor);
    }

    @Test
    void queueReport_withValidRequest_shouldReturnJobId() {
        // Arrange
        when(redisTemplate.opsForHash()).thenReturn(hashOperations);
        when(redisTemplate.expire(anyString(), anyLong(), any(TimeUnit.class))).thenReturn(true);

        ReportRequest request = new ReportRequest();
        request.setJrxmlFileName("Simple_Report");
        request.setOutputFileName("TestReport");
        request.setOutputFormat(ReportRequest.OutputFormat.PDF);
        request.setCredentialKey("web001");

        // Act
        String jobId = asyncReportService.queueReport(request);

        // Assert
        assertNotNull(jobId);
        assertFalse(jobId.isEmpty());
        verify(hashOperations).put(eq("job:" + jobId), eq("status"), eq(StatusResponse.Status.PENDING.name()));
        verify(hashOperations).put(eq("job:" + jobId), eq("request"), anyString());
        verify(redisTemplate).convertAndSend(eq("report-jobs"), eq(jobId));
        verify(redisTemplate).expire(eq("job:" + jobId), eq(24L), eq(TimeUnit.HOURS));
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
                () -> asyncReportService.queueReport(request));
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
                () -> asyncReportService.queueReport(request));
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
}
