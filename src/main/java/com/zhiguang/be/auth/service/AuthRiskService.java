package com.zhiguang.be.auth.service;

/**
 * 认证风控服务。
 */
public interface AuthRiskService {

    /**
     * 检查是否需要验证码挑战。
     *
     * @param identifier 登录标识
     * @return 是否需要验证码
     */
    boolean requiresCaptcha(String identifier);

    /**
     * 记录登录失败。
     *
     * @param identifier 登录标识
     */
    void recordFailure(String identifier);

    /**
     * 清除失败计数。
     *
     * @param identifier 登录标识
     */
    void clearFailures(String identifier);
}
