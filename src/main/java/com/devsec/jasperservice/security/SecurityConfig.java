package com.devsec.jasperservice.security;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private final ApiKeyService apiKeyService;
    private final RateLimitFilter rateLimitFilter;

    public SecurityConfig(ApiKeyService apiKeyService, RateLimitFilter rateLimitFilter) {
        this.apiKeyService = apiKeyService;
        this.rateLimitFilter = rateLimitFilter;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .csrf(csrf -> csrf.disable()) // Disable CSRF for stateless API
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .exceptionHandling(exceptions -> exceptions
                .authenticationEntryPoint((request, response, authException) -> {
                    response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                })
            )
            .authorizeHttpRequests(authz -> authz
                .requestMatchers(
                    "/docs/**",
                    "/openapi.yaml",
                    "/api-docs/**",
                    "/api/report/**" // Allow access to old v1 endpoint if it exists
                ).permitAll()
                .requestMatchers("/api/v2/**").authenticated() // Secure v2 endpoints
                .anyRequest().permitAll()
            )
            .addFilterBefore(new ApiKeyAuthFilter(apiKeyService), UsernamePasswordAuthenticationFilter.class)
            .addFilterBefore(rateLimitFilter, ApiKeyAuthFilter.class);
        return http.build();
    }
}
