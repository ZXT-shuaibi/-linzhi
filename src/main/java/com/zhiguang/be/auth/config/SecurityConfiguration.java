package com.zhiguang.be.auth.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhiguang.be.auth.mapper.AuthUserMapper;
import com.zhiguang.be.auth.model.AuthUserEntity;
import com.zhiguang.be.auth.security.AdminGuardFilter;
import com.zhiguang.be.auth.security.ProtectedApiBlacklistFilter;
import com.zhiguang.be.auth.security.PublicApiPaths;
import com.zhiguang.be.common.api.ErrorResponse;
import com.zhiguang.be.common.exception.ErrorCode;
import com.zhiguang.be.common.web.filter.RequestIdFilter;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Spring Security 配置类。
 * 负责定义认证放行路径、JWT 资源服务器能力以及统一异常输出格式。
 */
@Configuration
@EnableWebSecurity
public class SecurityConfiguration {

    static final String RAG_PUBLIC_REINDEX_PATH = "/api/v1/rag/reindex/public";
    static final String RAG_POST_REINDEX_PATH = "/api/v1/rag/posts/*/reindex";
    static final List<String> DEFAULT_CORS_ALLOWED_ORIGINS = List.of(
            "http://localhost:5173",
            "http://127.0.0.1:5173"
    );

    private static final Logger log = LoggerFactory.getLogger(SecurityConfiguration.class);

    /**
     * 构建应用的安全过滤链。
     * 当前配置启用了无状态会话、JWT 鉴权、请求 ID 过滤器以及统一的认证失败响应。
     *
     * @param http HttpSecurity 构建器
     * @param accessJwtDecoder 访问令牌解码器
     * @param requestIdFilter 请求 ID 过滤器
     * @param objectMapper JSON 序列化组件
     * @return 安全过滤链
     * @throws Exception 安全配置构建异常
     */
    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            @Qualifier("accessJwtDecoder") JwtDecoder accessJwtDecoder,
            RequestIdFilter requestIdFilter,
            ProtectedApiBlacklistFilter protectedApiBlacklistFilter,
            AuthUserMapper authUserMapper,
            ObjectMapper objectMapper
    ) throws Exception {
        AuthorizationManager<RequestAuthorizationContext> adminAccess = latestRoleAuthorizationManager(authUserMapper, "ADMIN");

        http
                .cors(Customizer.withDefaults())
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(config -> config.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(PublicApiPaths.PUBLIC_ENDPOINTS).permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/posts/mine", "/api/v1/knowposts/mine").authenticated()
                        .requestMatchers(HttpMethod.GET, PublicApiPaths.PUBLIC_GET_ENDPOINTS).permitAll()
                        .requestMatchers(HttpMethod.POST, PublicApiPaths.PUBLIC_POST_ENDPOINTS).permitAll()
                        .requestMatchers("/api/v1/platform/**").access(adminAccess)
                        .requestMatchers(HttpMethod.POST, RAG_PUBLIC_REINDEX_PATH, RAG_POST_REINDEX_PATH).access(adminAccess)
                        .requestMatchers(HttpMethod.POST, "/api/v1/discover/location").access(adminAccess)
                        .requestMatchers(HttpMethod.DELETE, "/api/v1/discover/location").access(adminAccess)
                        .anyRequest().authenticated()
                )
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt
                                .decoder(accessJwtDecoder)
                                .jwtAuthenticationConverter(jwtAuthenticationConverter())))
                .addFilterBefore(requestIdFilter, BearerTokenAuthenticationFilter.class)
                .addFilterAfter(protectedApiBlacklistFilter, BearerTokenAuthenticationFilter.class)
                .addFilterAfter(new AdminGuardFilter(), BearerTokenAuthenticationFilter.class)
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint((request, response, authException) ->
                                writeError(response, HttpServletResponse.SC_UNAUTHORIZED, ErrorCode.UNAUTHORIZED, objectMapper))
                        .accessDeniedHandler((request, response, accessDeniedException) ->
                                writeError(response, HttpServletResponse.SC_FORBIDDEN, ErrorCode.FORBIDDEN, objectMapper))
                )
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .rememberMe(AbstractHttpConfigurer::disable)
                .anonymous(Customizer.withDefaults());

        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource(
            @Value("${security.cors.allowed-origins:}") List<String> allowedOrigins
    ) {
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", buildCorsConfiguration(allowedOrigins));
        return source;
    }

    static CorsConfiguration buildCorsConfiguration(List<String> allowedOrigins) {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowCredentials(true);
        configuration.setAllowedOrigins(normalizeAllowedOrigins(allowedOrigins));
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("Authorization", "Content-Type", "X-Request-Id"));
        configuration.setExposedHeaders(List.of("X-Request-Id"));
        configuration.setMaxAge(3600L);
        return configuration;
    }

    private static List<String> normalizeAllowedOrigins(List<String> allowedOrigins) {
        List<String> normalized = new ArrayList<>();
        if (allowedOrigins != null) {
            for (String origin : allowedOrigins) {
                if (origin == null || origin.isBlank() || "*".equals(origin.trim())) {
                    continue;
                }
                normalized.add(origin.trim());
            }
        }
        return normalized.isEmpty() ? DEFAULT_CORS_ALLOWED_ORIGINS : normalized;
    }

    /**
     * 将 JWT 中的 role claim 映射为 Spring Security 的 ROLE_ 前缀授权。
     * USER → ROLE_USER, ADMIN → ROLE_ADMIN。
     */
    private JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(jwt -> {
            String role = jwt.getClaimAsString("role");
            if (role == null) {
                return Collections.emptyList();
            }
            return List.of(new SimpleGrantedAuthority("ROLE_" + role));
        });
        return converter;
    }

    private AuthorizationManager<RequestAuthorizationContext> latestRoleAuthorizationManager(
            AuthUserMapper authUserMapper,
            String requiredRole
    ) {
        return (authenticationSupplier, context) -> {
            Authentication authentication = authenticationSupplier.get();
            if (authentication == null || !authentication.isAuthenticated()) {
                return new AuthorizationDecision(false);
            }
            boolean granted = authUserMapper.findByUserId(authentication.getName())
                    .map(AuthUserEntity::role)
                    .map(role -> requiredRole.equalsIgnoreCase(role))
                    .orElse(false);
            return new AuthorizationDecision(granted);
        };
    }

    /**
     * 输出统一 JSON 错误响应。
     * 当序列化失败时会至少保证返回正确的 HTTP 状态码。
     *
     * @param response HTTP 响应对象
     * @param status HTTP 状态码
     * @param errorCode 业务错误码
     * @param objectMapper JSON 序列化组件
     */
    private void writeError(HttpServletResponse response, int status, ErrorCode errorCode, ObjectMapper objectMapper) {
        try {
            response.setStatus(status);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            ErrorResponse body = ErrorResponse.of(errorCode.code(), errorCode.defaultMessage(), List.of());
            response.getWriter().write(objectMapper.writeValueAsString(body));
        } catch (Exception ex) {
            log.warn("Failed to write authentication error response", ex);
            response.setStatus(status);
        }
    }
}
