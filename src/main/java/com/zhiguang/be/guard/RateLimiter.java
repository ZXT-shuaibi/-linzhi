package com.zhiguang.be.guard;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 滑动窗口限流注解。
 * 通过 Redis ZSet + Lua 脚本控制接口在时间窗口内的访问次数。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RateLimiter {

    /**
     * 限流键前缀。
     */
    String keyPrefix();

    /**
     * 时间窗口，单位毫秒。
     */
    long windowMillis();

    /**
     * 时间窗口内允许的最大请求数。
     */
    long limit();

    /**
     * 被限流时返回的提示文案。
     */
    String message() default "请求过于频繁，请稍后重试";

    /**
     * 限流维度。
     */
    RateLimitDimension dimension() default RateLimitDimension.USER;
}
