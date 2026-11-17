package com.tech.challenger.execution.service.demo;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
class ApiKeyFilter extends OncePerRequestFilter {
    private final String key;

    ApiKeyFilter(@Value("${app.api-key}") String key) {
        this.key = key;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return !path.equals("/budgets") && !path.startsWith("/budgets/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (key.isBlank()) {
            response.sendError(HttpStatus.SERVICE_UNAVAILABLE.value(), "BILLING_API_KEY nao configurada");
            return;
        }
        String supplied = request.getHeader("X-API-Key");
        if (supplied == null || !MessageDigest.isEqual(key.getBytes(StandardCharsets.UTF_8), supplied.getBytes(StandardCharsets.UTF_8))) {
            response.sendError(HttpStatus.UNAUTHORIZED.value(), "Credencial invalida");
            return;
        }
        chain.doFilter(request, response);
    }
}
