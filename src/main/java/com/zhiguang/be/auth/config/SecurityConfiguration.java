package com.zhiguang.be.auth.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhiguang.be.auth.security.ProtectedApiBlacklistFilter;
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
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;

import java.util.List;

/**
 * Spring Security 配置类。
 * 负责定义认证放行路径、JWT 资源服务器能力以及统一异常输出格式。
 */
@Configuration
@EnableWebSecurity
public class SecurityConfiguration {

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
            ObjectMapper objectMapper
    ) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(config -> config.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(
                                "/api/v1/auth/send-code",
                                "/api/v1/auth/login",
                                "/api/v1/auth/register",
                                "/api/v1/auth/token/refresh",
                                "/api/v1/auth/logout",
                                "/api/v1/auth/password/reset",
                                "/api/v1/_meta/ping",
                                "/actuator/health",
                                "/error"
                        ).permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/posts/mine").authenticated()
                        .requestMatchers(HttpMethod.GET, "/api/v1/posts/feed").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/discover/nearby").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/discover/nearby").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/posts/*").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/search/posts").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/search/suggest").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/feed/home").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/interactions/targets/*/*/summary").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/interactions/targets/*/summary-batch").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/follows/status").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/social/counters/users/*").permitAll()
                        .anyRequest().authenticated()
                )
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> jwt.decoder(accessJwtDecoder)))
                .addFilterBefore(requestIdFilter, BearerTokenAuthenticationFilter.class)
                .addFilterAfter(protectedApiBlacklistFilter, BearerTokenAuthenticationFilter.class)
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
        } catch (Exception ignored) {
            response.setStatus(status);
        }
    }
}
