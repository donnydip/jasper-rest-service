package com.devsec.jasperservice.security;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
public class ApiKeyService {

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    
    private static final String API_KEY_PREFIX = "apikey:";

    public ApiKeyService(StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    public Optional<ApiKey> getApiKey(String key) {
        if (key == null || key.trim().isEmpty()) {
            return Optional.empty();
        }
        
        String json = redisTemplate.opsForValue().get(API_KEY_PREFIX + key);
        if (json == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(json, ApiKey.class));
        } catch (JsonProcessingException e) {
            return Optional.empty();
        }
    }
    
    public void saveApiKey(ApiKey apiKey) {
        try {
            String json = objectMapper.writeValueAsString(apiKey);
            redisTemplate.opsForValue().set(API_KEY_PREFIX + apiKey.getKey(), json);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to save API key", e);
        }
    }
}
