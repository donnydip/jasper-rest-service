package com.devsec.jasperservice.service;

import net.sf.jasperreports.engine.*;
import net.sf.jasperreports.engine.data.JRBeanCollectionDataSource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class ReportService {

    private final ResourceLoader resourceLoader;

    public ReportService(ResourceLoader resourceLoader) {
        this.resourceLoader = resourceLoader;
    }

    // A simple sample data class
    public static class SampleData {
        private String name;
        private String country;

        public SampleData(String name, String country) {
            this.name = name;
            this.country = country;
        }

        public String getName() {
            return name;
        }

        public String getCountry() {
            return country;
        }
    }

    public byte[] generateReport(String reportName) throws JRException, IOException {
        // Load the report template from the classpath
        Resource resource = resourceLoader.getResource("classpath:reports/" + reportName + ".jrxml");
        if (!resource.exists()) {
            throw new FileNotFoundException("Report template not found: " + reportName);
        }

        try (InputStream reportStream = resource.getInputStream()) {
            JasperReport jasperReport = JasperCompileManager.compileReport(reportStream);

            // Create a sample data source
            List<SampleData> dataList = List.of(
                    new SampleData("John Doe", "USA"),
                    new SampleData("Jane Smith", "Canada"),
                    new SampleData("Peter Jones", "UK")
            );
            JRBeanCollectionDataSource dataSource = new JRBeanCollectionDataSource(dataList);

            // Set report parameters
            Map<String, Object> parameters = new HashMap<>();
            parameters.put("ReportTitle", "Simple Jasper Report");
            parameters.put("Author", "Gemini CLI");

            // Fill the report
            JasperPrint jasperPrint = JasperFillManager.fillReport(jasperReport, parameters, dataSource);

            // Export the report to PDF
            return JasperExportManager.exportReportToPdf(jasperPrint);
        }
    }
}
