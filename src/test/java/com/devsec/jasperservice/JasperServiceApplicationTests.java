package com.devsec.jasperservice;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Integration test that verifies the Spring application context loads successfully.
 * Mocks Redis dependencies since infrastructure is not available in CI/test environments.
 */
@SpringBootTest
@TestPropertySource(properties = {
	"report.output.dir=target/generated-reports",
	"management.health.redis.enabled=false",
	"spring.data.redis.port=6379",
	"spring.data.redis.host=localhost",
	"POSTGRES_USER=test",
	"POSTGRES_PASSWORD=test"
})
class JasperServiceApplicationTests {

	@MockitoBean
	private StringRedisTemplate redisTemplate;

	@MockitoBean
	private LettuceConnectionFactory redisConnectionFactory;

	@MockitoBean
	private RedisMessageListenerContainer redisMessageListenerContainer;

	@MockitoBean
	private ObjectMapper objectMapper;

	@Test
	void contextLoads() {
	}

}
