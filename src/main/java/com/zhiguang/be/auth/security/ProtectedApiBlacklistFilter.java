package com.zhiguang.be.auth.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhiguang.be.auth.blacklist.AccessTokenBlocklistStore;
import com.zhiguang.be.auth.blacklist.AuthBlocklistService;
import com.zhiguang.be.auth.blacklist.LoginBlacklistStore;
import com.zhiguang.be.common.api.ErrorResponse;
import com.zhiguang.be.common.exception.ErrorCode;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;

/**
 * 受保护接口黑名单过滤器。
 * 对已经完成鉴权的请求补做一次黑名单校验，确保旧 access token 能立即失效。
 */
@Component
public class ProtectedApiBlacklistFilter extends OncePerRequestFilter {

    private static final Set<String> PUBLIC_PATHS = Set.of(
            "/api/v1/auth/send-code",
            "/api/v1/auth/login",
            "/api/v1/auth/register",
            "/api/v1/auth/token/refresh",
            "/api/v1/auth/logout",
            "/api/v1/auth/password/reset",
            "/api/v1/_meta/ping",
            "/actuator/health",
            "/error"
    );

    private final AuthBlocklistService blocklistService;
    private final AccessTokenBlocklistStore accessTokenBlocklistStore;
    private final LoginBlacklistStore loginBlacklistStore;
    private final ObjectMapper objectMapper;

    /**
     * 构造受保护接口黑名单过滤器。
     *
     * @param blocklistService 认证黑名单写入服务
     * @param accessTokenBlocklistStore 访问令牌失效黑名单存储组件
     * @param loginBlacklistStore 登录黑名单存储组件
     * @param objectMapper JSON 序列化组件
     */
    public ProtectedApiBlacklistFilter(
            AuthBlocklistService blocklistService,
            AccessTokenBlocklistStore accessTokenBlocklistStore,
            LoginBlacklistStore loginBlacklistStore,
            ObjectMapper objectMapper
    ) {
        this.blocklistService = blocklistService;
        this.accessTokenBlocklistStore = accessTokenBlocklistStore;
        this.loginBlacklistStore = loginBlacklistStore;
        this.objectMapper = objectMapper;
    }

    /**
     * 公开接口不执行黑名单二次校验。
     *
     * @param request 当前 HTTP 请求
     * @return 公开接口返回 true，其余返回 false
     */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return PUBLIC_PATHS.contains(request.getServletPath());
    }

    /**
     * 对受保护请求执行黑名单校验。
     * 命中账号黑名单或 access token 失效黑名单时，立即拒绝访问并撤销 refresh token。
     *
     * @param request 当前 HTTP 请求
     * @param response 当前 HTTP 响应
     * @param filterChain 过滤器链
     * @throws ServletException Servlet 异常
     * @throws IOException IO 异常
     */
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!(authentication instanceof JwtAuthenticationToken jwtAuthenticationToken) || !authentication.isAuthenticated()) {
            filterChain.doFilter(request, response);
            return;
        }

        String userId = jwtAuthenticationToken.getToken().getSubject();
        Instant issuedAt = jwtAuthenticationToken.getToken().getIssuedAt();
        if (userId == null || userId.isBlank()) {
            filterChain.doFilter(request, response);
            return;
        }

        boolean blockedByLoginBlacklist = loginBlacklistStore.isBlocked(userId);
        boolean blockedByAccessTokenBlocklist = accessTokenBlocklistStore.isBlocked(userId, issuedAt);
        if (!blockedByLoginBlacklist && !blockedByAccessTokenBlocklist) {
            filterChain.doFilter(request, response);
            return;
        }

        blocklistService.revokeAllSessionsAndBlockAccessTokens(userId, resolveTokenTtl(jwtAuthenticationToken));
        SecurityContextHolder.clearContext();
        writeBlockedResponse(response);
    }

    /**
     * 计算当前 access token 剩余生存时间。
     *
     * @param jwtAuthenticationToken 当前认证令牌
     * @return 令牌剩余 TTL
     */
    private Duration resolveTokenTtl(JwtAuthenticationToken jwtAuthenticationToken) {
        Instant expiresAt = jwtAuthenticationToken.getToken().getExpiresAt();
        if (expiresAt == null) {
            return Duration.ofSeconds(1);
        }
        Duration ttl = Duration.between(Instant.now(), expiresAt);
        if (ttl.isNegative() || ttl.isZero()) {
            return Duration.ofSeconds(1);
        }
        return ttl;
    }

    /**
     * 输出统一的黑名单拒绝响应。
     *
     * @param response HTTP 响应对象
     * @throws IOException 响应写出异常
     */
    private void writeBlockedResponse(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        ErrorResponse body = ErrorResponse.of(
                ErrorCode.LOGIN_BLOCKED.code(),
                ErrorCode.LOGIN_BLOCKED.defaultMessage(),
                List.of()
        );
        response.getWriter().write(objectMapper.writeValueAsString(body));
    }
}
