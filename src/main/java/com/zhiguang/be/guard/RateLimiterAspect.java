package com.zhiguang.be.guard;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 滑动窗口限流切面。
 * 通过 Redis ZSet + Lua 脚本实现高并发下的原子限流。
 */
@Aspect
@Component
public class RateLimiterAspect {

    private final StringRedisTemplate stringRedisTemplate;
    private final DefaultRedisScript<Long> rateLimiterScript;

    /**
     * 注入 Redis 模板并初始化限流脚本。
     */
    public RateLimiterAspect(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.rateLimiterScript = new DefaultRedisScript<Long>();
        this.rateLimiterScript.setResultType(Long.class);
        this.rateLimiterScript.setScriptText(
                "local key = KEYS[1]\n"
                        + "local window = tonumber(ARGV[1])\n"
                        + "local limit = tonumber(ARGV[2])\n"
                        + "local now = tonumber(ARGV[3])\n"
                        + "local member = ARGV[4]\n"
                        + "redis.call('ZREMRANGEBYSCORE', key, 0, now - window)\n"
                        + "local count = redis.call('ZCARD', key)\n"
                        + "if count < limit then\n"
                        + "  redis.call('ZADD', key, now, member)\n"
                        + "  redis.call('PEXPIRE', key, window + 1000)\n"
                        + "  return 1\n"
                        + "end\n"
                        + "return 0\n"
        );
    }

    /**
     * 在标注限流注解的方法外包裹滑动窗口控制逻辑。
     */
    @Around("@annotation(rateLimiter)")
    public Object around(ProceedingJoinPoint joinPoint, RateLimiter rateLimiter) throws Throwable {
        String limiterKey = buildKey(rateLimiter);
        long now = Instant.now().toEpochMilli();
        String member = now + ":" + UUID.randomUUID();
        Long allowed = stringRedisTemplate.execute(
                rateLimiterScript,
                List.of(limiterKey),
                String.valueOf(rateLimiter.windowMillis()),
                String.valueOf(rateLimiter.limit()),
                String.valueOf(now),
                member
        );
        if (allowed == null || allowed.longValue() != 1L) {
            throw new RateLimitException(rateLimiter.message());
        }
        return joinPoint.proceed();
    }

    /**
     * 构造限流键。
     */
    private String buildKey(RateLimiter rateLimiter) {
        String dimensionValue;
        if (rateLimiter.dimension() == RateLimitDimension.GLOBAL) {
            dimensionValue = "global";
        } else if (rateLimiter.dimension() == RateLimitDimension.IP) {
            dimensionValue = resolveIp();
        } else {
            dimensionValue = resolveUserIdOrIp();
        }
        return "guard:rate:" + rateLimiter.keyPrefix() + ":" + dimensionValue;
    }

    /**
     * 获取当前用户 ID；若匿名则回退为 IP。
     */
    private String resolveUserIdOrIp() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof Jwt jwt) {
            String subject = jwt.getSubject();
            if (subject != null && !subject.isBlank()) {
                return "user:" + subject;
            }
        }
        return "ip:" + resolveIp();
    }

    /**
     * 获取请求来源 IP。
     */
    private String resolveIp() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (attributes instanceof ServletRequestAttributes servletAttributes) {
            HttpServletRequest request = servletAttributes.getRequest();
            String forwardedFor = request.getHeader("X-Forwarded-For");
            if (forwardedFor != null && !forwardedFor.isBlank()) {
                return forwardedFor.split(",")[0].trim();
            }
            return request.getRemoteAddr();
        }
        return "unknown";
    }
}
