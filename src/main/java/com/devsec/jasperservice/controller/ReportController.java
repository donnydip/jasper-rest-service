package com.devsec.jasperservice.controller;

import com.devsec.jasperservice.service.ReportService;
import net.sf.jasperreports.engine.JRException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.FileNotFoundException;
import java.io.IOException;

@RestController
@RequestMapping("/api/report")
public class ReportController {

    private final ReportService reportService;

    public ReportController(ReportService reportService) {
        this.reportService = reportService;
    }

    @GetMapping("/{reportName}")
    public ResponseEntity<byte[]> generateReport(@PathVariable String reportName) {
        try {
            byte[] reportContent = reportService.generateReport(reportName);
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_PDF);
            // This header makes the browser download the file
            headers.setContentDispositionFormData("attachment", reportName + ".pdf");
            return ResponseEntity.ok().headers(headers).body(reportContent);
        } catch (JRException | IOException e) {
            // Handle exceptions appropriately
            e.printStackTrace();
            return ResponseEntity.internalServerError().build();
        }
    }
}
