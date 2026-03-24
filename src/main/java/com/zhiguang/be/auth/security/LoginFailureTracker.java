package com.zhiguang.be.auth.security;

/**
 * 登录失败追踪器接口。
 */
public interface LoginFailureTracker {

    /**
     * 记录登录失败。
     *
     * @param identifier 登录标识（手机号）
     */
    void recordFailure(String identifier);

    /**
     * 获取失败次数。
     *
     * @param identifier 登录标识
     * @return 失败次数
     */
    int getFailureCount(String identifier);

    /**
     * 检查是否需要验证码。
     *
     * @param identifier 登录标识
     * @return true 需要验证码
     */
    boolean requiresCaptcha(String identifier);

    /**
     * 检查是否应该封禁。
     *
     * @param identifier 登录标识
     * @return true 应该封禁
     */
    boolean shouldBlock(String identifier);

    /**
     * 重置失败计数。
     *
     * @param identifier 登录标识
     */
    void reset(String identifier);
}
