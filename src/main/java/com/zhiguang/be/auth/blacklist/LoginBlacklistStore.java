package com.zhiguang.be.auth.blacklist;

import java.time.Duration;

/**
 * 登录黑名单存储接口。
 * 用于判断登录标识（如手机号）是否被风控系统拉黑。
 */
public interface LoginBlacklistStore {

    /**
     * 判断登录标识是否命中黑名单。
     *
     * @param identifier 登录标识（当前实现为手机号）
     * @return true 表示命中黑名单，false 表示未命中
     */
    boolean isBlocked(String identifier);

    /**
     * 拉黑指定登录标识。
     *
     * @param identifier 登录标识
     * @param ttl 黑名单有效期
     */
    default void block(String identifier, Duration ttl) {
        // default noop
    }
}
