package com.devsec.jasperservice.security;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
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

        String json;
        try {
            json = redisTemplate.opsForValue().get(API_KEY_PREFIX + hash(key));
        } catch (RedisConnectionFailureException e) {
            throw new ApiKeyLookupUnavailableException("Redis connection failed while looking up API key", e);
        }
        if (json == null) {
            return Optional.empty();
        }
        try {
            ApiKey apiKey = objectMapper.readValue(json, ApiKey.class);
            // FIX-08: the raw key is never persisted, restore it on the deserialized object
            // so downstream code (authorization checks, principal) still has it available.
            apiKey.setKey(key);
            return Optional.of(apiKey);
        } catch (JsonProcessingException e) {
            return Optional.empty();
        }
    }

    public void saveApiKey(ApiKey apiKey) {
        try {
            String rawKey = apiKey.getKey();
            ApiKey redacted = new ApiKey(null, apiKey.getClientName(), apiKey.getAllowedCredentialKeys(),
                    apiKey.isActive(), apiKey.getCreatedAt());
            String json = objectMapper.writeValueAsString(redacted);
            redisTemplate.opsForValue().set(API_KEY_PREFIX + hash(rawKey), json);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to save API key", e);
        }
    }

    private static String hash(String key) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = digest.digest(key.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hashBytes.length * 2);
            for (byte b : hashBytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm not available", e);
        }
    }

    public static class ApiKeyLookupUnavailableException extends RuntimeException {
        public ApiKeyLookupUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
