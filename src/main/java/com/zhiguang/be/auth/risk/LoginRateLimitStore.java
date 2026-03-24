package com.zhiguang.be.auth.risk;

import java.time.Duration;

/**
 * 登录限流存储接口。
 */
public interface LoginRateLimitStore {

    /**
     * 尝试记录一次登录请求。
     *
     * @param phone 手机号
     * @param accessToken 登录请求访问令牌
     * @param minInterval 同手机号最小间隔
     * @return true 允许本次请求，false 表示触发频率限制
     */
    boolean tryAcquire(String phone, String accessToken, Duration minInterval);

    /**
     * 增加登录失败次数。
     *
     * @param phone 手机号
     * @param ttl 失败计数 TTL
     * @return 当前失败次数
     */
    int incrementFailure(String phone, Duration ttl);

    /**
     * 清理失败计数。
     *
     * @param phone 手机号
     */
    void resetFailures(String phone);
}
