package com.zhiguang.be.common.web.config;

import com.zhiguang.be.common.web.interceptor.ApiAuditInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web MVC 配置类。
 * 负责注册统一的请求拦截器和接口层基础行为。
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    private final ApiAuditInterceptor apiAuditInterceptor;

    /**
     * 构造 Web MVC 配置并注入 API 审计拦截器。
     *
     * @param apiAuditInterceptor API 审计拦截器
     */
    public WebMvcConfig(ApiAuditInterceptor apiAuditInterceptor) {
        this.apiAuditInterceptor = apiAuditInterceptor;
    }

    /**
     * 注册需要启用的拦截器。
     * 当前会拦截 `/api/**` 路径，并排除健康检查接口。
     *
     * @param registry 拦截器注册表
     */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(apiAuditInterceptor)
                .addPathPatterns("/api/**")
                .excludePathPatterns("/api/v1/_meta/ping");
    }
}