package com.zhiguang.be.common.web.interceptor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * API 审计拦截器。
 * 在请求处理前后记录接口访问的关键元信息，用于行为审计和问题分析。
 */
@Component
public class ApiAuditInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(ApiAuditInterceptor.class);
    private static final String START_TIME_ATTR = "apiAudit.startTime";

    /**
     * 在请求进入控制器前记录起始时间。
     *
     * @param request HTTP 请求对象
     * @param response HTTP 响应对象
     * @param handler 当前处理器
     * @return 始终返回 true，表示继续执行后续链路
     */
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        request.setAttribute(START_TIME_ATTR, System.currentTimeMillis());
        return true;
    }

    /**
     * 在请求完成后输出审计日志。
     * 日志中包含方法、路径、状态码、耗时和当前认证主体。
     *
     * @param request HTTP 请求对象
     * @param response HTTP 响应对象
     * @param handler 当前处理器
     * @param ex 请求处理过程中抛出的异常，可为空
     */
    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        Object start = request.getAttribute(START_TIME_ATTR);
        long costMs = 0L;
        if (start instanceof Long) {
            long startTime = (Long) start;
            costMs = System.currentTimeMillis() - startTime;
        }

        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        String principal = (authentication != null && authentication.isAuthenticated())
                ? String.valueOf(authentication.getPrincipal())
                : "anonymous";

        log.info("api_audit method={} path={} status={} costMs={} principal={}",
                request.getMethod(),
                request.getRequestURI(),
                response.getStatus(),
                costMs,
                principal);
    }
}