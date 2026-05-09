package com.zhiguang.be.auth.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpMethod;
import org.springframework.util.AntPathMatcher;

public final class PublicApiPaths {

    public static final String[] PUBLIC_ENDPOINTS = {
            "/api/v1/auth/send-code",
            "/api/v1/auth/login",
            "/api/v1/auth/register",
            "/api/v1/auth/token/refresh",
            "/api/v1/auth/logout",
            "/api/v1/auth/password/reset",
            "/api/v1/_meta/ping",
            "/actuator/health",
            "/error"
    };

    public static final String[] PUBLIC_GET_ENDPOINTS = {
            "/api/v1/posts/feed",
            "/api/v1/knowposts/feed",
            "/api/v1/posts/*/comments",
            "/api/v1/knowposts/*/comments",
            "/api/v1/discover/nearby",
            "/api/v1/discover/map/**",
            "/api/v1/posts/*",
            "/api/v1/knowposts/*",
            "/api/v1/search/posts",
            "/api/v1/search/suggest",
            "/api/v1/feed/home",
            "/api/v1/trade/activities",
            "/api/v1/trade/activities/*",
            "/api/v1/profile/users/*",
            "/api/v1/profile/users/*/posts",
            "/api/v1/profile/users/*/following",
            "/api/v1/profile/users/*/followers",
            "/api/v1/interactions/targets/*/*/summary",
            "/api/v1/interactions/targets/*/summary-batch",
            "/api/v1/follows/status",
            "/api/v1/social/counters/users/*"
    };

    public static final String[] PUBLIC_POST_ENDPOINTS = {
            "/api/v1/discover/nearby"
    };

    private static final AntPathMatcher PATH_MATCHER = new AntPathMatcher();

    private PublicApiPaths() {
    }

    public static boolean matches(HttpServletRequest request) {
        if (request == null) {
            return false;
        }
        String method = request.getMethod();
        String path = request.getServletPath();
        return matchesAny(path, PUBLIC_ENDPOINTS)
                || (HttpMethod.GET.name().equalsIgnoreCase(method) && matchesAny(path, PUBLIC_GET_ENDPOINTS))
                || (HttpMethod.POST.name().equalsIgnoreCase(method) && matchesAny(path, PUBLIC_POST_ENDPOINTS));
    }

    private static boolean matchesAny(String path, String[] patterns) {
        if (path == null || patterns == null) {
            return false;
        }
        for (String pattern : patterns) {
            if (PATH_MATCHER.match(pattern, path)) {
                return true;
            }
        }
        return false;
    }
}
