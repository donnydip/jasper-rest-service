package com.devsec.jasperservice.jobs;

import com.devsec.jasperservice.service.AsyncReportService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.connection.Message;

import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ReportJobListenerTest {

    @Mock
    private AsyncReportService asyncReportService;

    @Mock
    private Message message;

    @InjectMocks
    private ReportJobListener reportJobListener;

    @Test
    void onMessage_shouldDelegateToAsyncReportService() {
        // Arrange
        String jobId = "test-job-id-123";
        when(message.getBody()).thenReturn(jobId.getBytes());

        // Act
        reportJobListener.onMessage(message, null);

        // Assert
        verify(asyncReportService).processReport(jobId);
    }

    @Test
    void onMessage_whenServiceThrows_shouldNotPropagate() {
        // Arrange
        String jobId = "failing-job";
        when(message.getBody()).thenReturn(jobId.getBytes());
        doThrow(new RuntimeException("Processing failed")).when(asyncReportService).processReport(jobId);

        // Act — should not throw
        reportJobListener.onMessage(message, null);

        // Assert
        verify(asyncReportService).processReport(jobId);
    }
}
