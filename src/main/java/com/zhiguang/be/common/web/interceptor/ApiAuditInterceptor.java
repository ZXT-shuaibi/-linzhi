package com.zhiguang.be.common.web.interceptor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
/**
 * 类说明。
 */
public class ApiAuditInterceptor implements HandlerInterceptor {

    /**
     * 方法说明。
     */
    private static final Logger log = LoggerFactory.getLogger(ApiAuditInterceptor.class);
    private static final String START_TIME_ATTR = "apiAudit.startTime";

    @Override
    /**
     * 方法说明。
     */
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        request.setAttribute(START_TIME_ATTR, System.currentTimeMillis());
        return true;
    }

    @Override
    /**
     * 方法说明。
     */
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

        // 审计日志仅记录请求元信息，避免输出业务载荷。
        log.info("api_audit method={} path={} status={} costMs={} principal={}",
                request.getMethod(),
                request.getRequestURI(),
                response.getStatus(),
                costMs,
                principal);
    }
}
