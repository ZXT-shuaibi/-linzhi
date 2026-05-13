package com.zhiguang.be.auth.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Collection;

public class AdminGuardFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(AdminGuardFilter.class);

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain) throws ServletException, IOException {
        if (!isAdminPath(request)) {
            filterChain.doFilter(request, response);
            return;
        }

        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            sendUnauthorized(response, "认证缺失，运维接口需要登录");
            return;
        }

        if (!hasAdminRole(authentication)) {
            sendForbidden(response, "权限不足，运维接口仅限管理员");
            return;
        }

        filterChain.doFilter(request, response);
    }

    private boolean isAdminPath(HttpServletRequest request) {
        String method = request.getMethod();
        String uri = request.getRequestURI();

        // Protect write endpoints on these paths
        if ("POST".equalsIgnoreCase(method) || "PUT".equalsIgnoreCase(method)
            || "PATCH".equalsIgnoreCase(method) || "DELETE".equalsIgnoreCase(method)) {
            return uri.startsWith("/api/v1/rag/") && (uri.contains("/reindex") || uri.contains("/posts/"))
                || uri.startsWith("/api/v1/discover/location")
                || (uri.startsWith("/api/v1/platform/cache/evict"));
        }
        return false;
    }

    private boolean hasAdminRole(Authentication authentication) {
        // Check JWT claim "role"
        if (authentication.getPrincipal() instanceof Jwt jwt) {
            Object roleClaim = jwt.getClaim("role");
            if (roleClaim instanceof String role) {
                return "ADMIN".equalsIgnoreCase(role);
            }
            if (roleClaim instanceof Collection<?> roles) {
                return roles.stream().anyMatch(r -> "ADMIN".equalsIgnoreCase(String.valueOf(r)));
            }
        }
        // Check Spring Security authorities
        return authentication.getAuthorities().stream()
            .map(GrantedAuthority::getAuthority)
            .anyMatch(auth -> auth.equals("ROLE_ADMIN") || auth.equals("ADMIN"));
    }

    private void sendUnauthorized(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write("{\"code\":\"UNAUTHORIZED\",\"message\":\"" + message + "\"}");
    }

    private void sendForbidden(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpStatus.FORBIDDEN.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write("{\"code\":\"FORBIDDEN\",\"message\":\"" + message + "\"}");
    }
}
