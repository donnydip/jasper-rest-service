package com.devsec.jasperservice.dto;

public class ReportResponse {
    private String jobId;
    private String statusUrl;

    public ReportResponse(String jobId) {
        this.jobId = jobId;
        this.statusUrl = "/api/v2/report/status/" + jobId;
    }

    // Getters and Setters
    public String getJobId() { return jobId; }
    public void setJobId(String jobId) { this.jobId = jobId; }
    public String getStatusUrl() { return statusUrl; }
    public void setStatusUrl(String statusUrl) { this.statusUrl = statusUrl; }
}
