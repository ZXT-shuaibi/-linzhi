package com.zhiguang.be.auth.security;

/**
 * 登录失败跟踪器接口。
 * 用于统计失败次数，并基于阈值决定是否需要验证码或封禁。
 */
public interface LoginFailureTracker {

    /**
     * 记录一次登录失败。
     *
     * @param identifier 登录标识
     */
    void recordFailure(String identifier);

    /**
     * 获取当前累计失败次数。
     *
     * @param identifier 登录标识
     * @return 失败次数
     */
    int getFailureCount(String identifier);

    /**
     * 判断当前登录标识是否需要验证码。
     *
     * @param identifier 登录标识
     * @return 需要验证码返回 true
     */
    boolean requiresCaptcha(String identifier);

    /**
     * 判断当前登录标识是否应被封禁。
     *
     * @param identifier 登录标识
     * @return 应封禁返回 true
     */
    boolean shouldBlock(String identifier);

    /**
     * 重置登录失败计数。
     *
     * @param identifier 登录标识
     */
    void reset(String identifier);
}