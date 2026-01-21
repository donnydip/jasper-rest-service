package com.devsec.jasperservice.dto;

import java.util.Map;

public class ReportRequest {

    public enum OutputFormat {
        PDF, XLSX, CSV, DOCX
    }

    private String jrxmlFileName;
    private String outputFileName;
    private OutputFormat outputFormat = OutputFormat.PDF; // Default to PDF
    private String appType;
    private String credentialKey;
    private Map<String, Object> jasperParameters;
    
    // Getters and Setters

    public String getJrxmlFileName() { return jrxmlFileName; }
    public void setJrxmlFileName(String jrxmlFileName) { this.jrxmlFileName = jrxmlFileName; }
    public String getOutputFileName() { return outputFileName; }
    public void setOutputFileName(String outputFileName) { this.outputFileName = outputFileName; }
    public OutputFormat getOutputFormat() { return outputFormat; }
    public void setOutputFormat(OutputFormat outputFormat) { this.outputFormat = outputFormat; }
    public String getAppType() { return appType; }
    public void setAppType(String appType) { this.appType = appType; }
    public String getCredentialKey() { return credentialKey; }
    public void setCredentialKey(String credentialKey) { this.credentialKey = credentialKey; }
    public Map<String, Object> getJasperParameters() { return jasperParameters; }
    public void setJasperParameters(Map<String, Object> jasperParameters) { this.jasperParameters = jasperParameters; }
}
