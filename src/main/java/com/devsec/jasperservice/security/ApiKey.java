package com.devsec.jasperservice.security;

import java.util.List;

public class ApiKey {
    private String key;
    private String clientName;
    private List<String> allowedCredentialKeys;
    private boolean active;
    private long createdAt;

    public ApiKey() {}

    public ApiKey(String key, String clientName, List<String> allowedCredentialKeys, boolean active, long createdAt) {
        this.key = key;
        this.clientName = clientName;
        this.allowedCredentialKeys = allowedCredentialKeys;
        this.active = active;
        this.createdAt = createdAt;
    }

    public String getKey() { return key; }
    public void setKey(String key) { this.key = key; }

    public String getClientName() { return clientName; }
    public void setClientName(String clientName) { this.clientName = clientName; }

    public List<String> getAllowedCredentialKeys() { return allowedCredentialKeys; }
    public void setAllowedCredentialKeys(List<String> allowedCredentialKeys) { this.allowedCredentialKeys = allowedCredentialKeys; }

    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }

    public long getCreatedAt() { return createdAt; }
    public void setCreatedAt(long createdAt) { this.createdAt = createdAt; }
}
