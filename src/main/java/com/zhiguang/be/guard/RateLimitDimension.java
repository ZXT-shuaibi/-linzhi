package com.zhiguang.be.guard;

/**
 * 限流维度枚举。
 * 用于决定限流键按全局、IP 还是用户维度隔离。
 */
public enum RateLimitDimension {
    GLOBAL,
    IP,
    USER
}
