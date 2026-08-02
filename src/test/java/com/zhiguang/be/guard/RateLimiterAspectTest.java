package com.zhiguang.be.guard;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.aspectj.lang.ProceedingJoinPoint;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RateLimiterAspectTest {

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void resolveIpShouldIgnoreForwardedHeadersWhenRemoteAddressIsNotTrusted() {
        RateLimiterAspect aspect = new RateLimiterAspect(mock(StringRedisTemplate.class));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("203.0.113.9");
        request.addHeader("X-Forwarded-For", "198.51.100.8");
        request.addHeader("X-Real-IP", "198.51.100.7");
        bind(request);

        String ip = ReflectionTestUtils.invokeMethod(aspect, "resolveIp");

        assertEquals("203.0.113.9", ip);
    }

    @Test
    void aroundShouldFailClosedWhenRedisCheckFails() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenThrow(new RuntimeException("redis down"));
        RateLimiterAspect aspect = new RateLimiterAspect(redisTemplate);
        RateLimiter rateLimiter = mock(RateLimiter.class);
        when(rateLimiter.keyPrefix()).thenReturn("login");
        when(rateLimiter.windowMillis()).thenReturn(60_000L);
        when(rateLimiter.limit()).thenReturn(10L);
        when(rateLimiter.dimension()).thenReturn(RateLimitDimension.GLOBAL);
        when(rateLimiter.message()).thenReturn("too many");

        assertThrows(RateLimitException.class, () -> aspect.around(mock(ProceedingJoinPoint.class), rateLimiter));
    }

    @Test
    void interactionLimitShouldUseConfiguredPerfOverride() {
        RateLimiterAspect aspect = new RateLimiterAspect(mock(StringRedisTemplate.class));
        ReflectionTestUtils.setField(aspect, "interactionWriteLimit", 1000L);
        RateLimiter rateLimiter = mock(RateLimiter.class);
        when(rateLimiter.keyPrefix()).thenReturn("interaction:write");
        when(rateLimiter.limit()).thenReturn(30L);

        assertEquals(1000L, ((Long) ReflectionTestUtils.invokeMethod(aspect, "effectiveLimit", rateLimiter)).longValue());
    }

    private void bind(HttpServletRequest request) {
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }
}
