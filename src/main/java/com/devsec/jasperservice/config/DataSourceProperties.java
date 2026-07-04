package com.devsec.jasperservice.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.annotation.PreDestroy;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Configuration
@ConfigurationProperties(prefix = "datasource")
public class DataSourceProperties {

    private final Map<String, Credentials> connections = new HashMap<>();
    private final Map<String, HikariDataSource> dataSources = new ConcurrentHashMap<>();

    public Map<String, Credentials> getConnections() {
        return connections;
    }

    public synchronized HikariDataSource getDataSource(String key) {
        Credentials creds = connections.get(key);
        if (creds == null) {
            throw new IllegalArgumentException("No credentials found for key: " + key);
        }
        return dataSources.computeIfAbsent(key, k -> {
            HikariConfig config = new HikariConfig();
            config.setJdbcUrl(creds.getUrl());
            config.setUsername(creds.getUsername());
            config.setPassword(creds.getPassword());
            config.setDriverClassName(creds.getDriverClassName());
            config.setMaximumPoolSize(10);
            return new HikariDataSource(config);
        });
    }

    @PreDestroy
    public void close() {
        dataSources.values().forEach(HikariDataSource::close);
    }

    public static class Credentials {
        private String url;
        private String username;
        private String password;
        private String driverClassName;

        // Getters and setters
        public String getUrl() { return url; }
        public void setUrl(String url) { this.url = url; }
        public String getUsername() { return username; }
        public void setUsername(String username) { this.username = username; }
        public String getPassword() { return password; }
        public void setPassword(String password) { this.password = password; }
        public String getDriverClassName() { return driverClassName; }
        public void setDriverClassName(String driverClassName) { this.driverClassName = driverClassName; }
    }
}
