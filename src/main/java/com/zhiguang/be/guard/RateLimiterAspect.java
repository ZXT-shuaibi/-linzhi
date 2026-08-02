package com.zhiguang.be.guard;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import com.zhiguang.be.common.web.ClientIpResolver;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

    private static final Logger log = LoggerFactory.getLogger(RateLimiterAspect.class);

    private final StringRedisTemplate stringRedisTemplate;
    private final ClientIpResolver clientIpResolver;
    private final DefaultRedisScript<Long> rateLimiterScript;
    @Value("${security.rate-limit.interaction-write-limit:30}")
    private long interactionWriteLimit = 30L;

    /**
     * 注入 Redis 模板并初始化限流脚本。
     */
    /**
     * Convenience constructor for isolated unit tests that do not provide proxy trust configuration.
     */
    public RateLimiterAspect(StringRedisTemplate stringRedisTemplate) {
        this(stringRedisTemplate, new ClientIpResolver(""));
    }

    /**
     * Production injection point. Explicitly marking it prevents ambiguity with the test constructor.
     */
    @Autowired
    public RateLimiterAspect(StringRedisTemplate stringRedisTemplate, ClientIpResolver clientIpResolver) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.clientIpResolver = clientIpResolver;
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
        Long allowed;
        try {
            allowed = stringRedisTemplate.execute(
                    rateLimiterScript,
                    List.of(limiterKey),
                    String.valueOf(rateLimiter.windowMillis()),
                    String.valueOf(effectiveLimit(rateLimiter)),
                    String.valueOf(now),
                    member
            );
        } catch (Exception ex) {
            log.warn("Rate limiter check failed, fail closed. key={}", limiterKey, ex);
            throw new RateLimitException(rateLimiter.message());
        }
        if (allowed == null || allowed.longValue() != 1L) {
            throw new RateLimitException(rateLimiter.message());
        }
        return joinPoint.proceed();
    }

    private long effectiveLimit(RateLimiter rateLimiter) {
        if ("interaction:write".equals(rateLimiter.keyPrefix())) {
            return Math.max(1L, interactionWriteLimit);
        }
        return rateLimiter.limit();
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
            return clientIpResolver.resolve(servletAttributes.getRequest());
        }
        return "unknown";
    }
}
