package com.devsec.jasperservice.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Collections;
import java.util.Optional;

public class ApiKeyAuthFilter extends OncePerRequestFilter {

    private final ApiKeyService apiKeyService;

    public ApiKeyAuthFilter(ApiKeyService apiKeyService) {
        this.apiKeyService = apiKeyService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        
        String header = request.getHeader("Authorization");

        if (StringUtils.hasText(header) && header.startsWith("Bearer ")) {
            String token = header.substring(7);

            if (StringUtils.hasText(token)) {
                try {
                    Optional<ApiKey> apiKeyOpt = apiKeyService.getApiKey(token);
                    if (apiKeyOpt.isPresent() && apiKeyOpt.get().isActive()) {
                        ApiKeyAuthenticationToken authentication = new ApiKeyAuthenticationToken(
                            apiKeyOpt.get(),
                            Collections.singletonList(new SimpleGrantedAuthority("ROLE_API_USER"))
                        );
                        SecurityContextHolder.getContext().setAuthentication(authentication);
                    }
                } catch (ApiKeyService.ApiKeyLookupUnavailableException e) {
                    response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
                    response.setContentType("application/json");
                    response.getWriter().write("{\"error\":\"Service is currently unavailable\"}");
                    return;
                }
            }
        }

        filterChain.doFilter(request, response);
    }
}
