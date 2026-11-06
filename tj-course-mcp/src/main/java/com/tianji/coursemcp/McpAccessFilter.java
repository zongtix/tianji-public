package com.tianji.coursemcp;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Service authentication only; the server does not accept end-user identity from model arguments. */
public final class McpAccessFilter extends OncePerRequestFilter {
    private final byte[] credential;

    public McpAccessFilter(String token) {
        this.credential = ("Bearer " + token).getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getServletPath().equals("/mcp");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        // This service-to-service pilot has no browser client. Reject every browser Origin.
        if (request.getHeader("Origin") != null) {
            response.setStatus(403); return;
        }
        String authorization = request.getHeader("Authorization");
        if (authorization == null || !MessageDigest.isEqual(credential, authorization.getBytes(StandardCharsets.UTF_8))) {
            response.setStatus(401); return;
        }
        chain.doFilter(request, response);
    }
}
