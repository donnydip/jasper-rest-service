package com.devsec.jasperservice.jobs;

import com.devsec.jasperservice.service.AsyncReportService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.stereotype.Service;

@Service
public class ReportJobListener implements MessageListener {

    private static final Logger LOGGER = LoggerFactory.getLogger(ReportJobListener.class);
    
    private final AsyncReportService asyncReportService;

    public ReportJobListener(AsyncReportService asyncReportService) {
        this.asyncReportService = asyncReportService;
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        String jobId = new String(message.getBody());
        LOGGER.info("Received job ID for processing: {}", jobId);
        try {
            asyncReportService.processReport(jobId);
        } catch (Exception e) {
            LOGGER.error("Unhandled exception during report processing for job {}", jobId, e);
        }
    }
}
