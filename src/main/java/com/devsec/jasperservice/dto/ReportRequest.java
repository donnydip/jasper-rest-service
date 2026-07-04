package com.devsec.jasperservice.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.util.Map;

public class ReportRequest {

    public enum OutputFormat {
        PDF, XLSX, CSV, DOCX
    }

    @NotBlank
    @Pattern(regexp = "^[a-zA-Z0-9_-]+$", message = "jrxmlFileName must be alphanumeric with underscores/hyphens only")
    private String jrxmlFileName;

    @NotBlank
    @Pattern(regexp = "^[a-zA-Z0-9_.-]+$", message = "outputFileName must be alphanumeric with underscores/hyphens/periods only")
    private String outputFileName;

    @NotNull
    private OutputFormat outputFormat = OutputFormat.PDF;

    private String appType;

    @NotBlank
    @Pattern(regexp = "^[a-zA-Z0-9_-]+$", message = "credentialKey must be alphanumeric with underscores/hyphens only")
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
