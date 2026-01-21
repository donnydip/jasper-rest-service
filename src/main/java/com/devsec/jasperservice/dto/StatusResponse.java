package com.devsec.jasperservice.dto;

public class StatusResponse {
    public enum Status {
        PENDING, PROCESSING, COMPLETE, FAILED
    }

    private Status status;
    private String message;
    private String downloadUrl;

    public StatusResponse(Status status, String message) {
        this.status = status;
        this.message = message;
    }
    
    // Getters and Setters
    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
    public String getDownloadUrl() { return downloadUrl; }
    public void setDownloadUrl(String downloadUrl) { this.downloadUrl = downloadUrl; }
}
