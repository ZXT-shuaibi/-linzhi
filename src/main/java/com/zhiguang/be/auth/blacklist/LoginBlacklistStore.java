package com.zhiguang.be.auth.blacklist;

import java.time.Duration;

/**
 * 登录黑名单存储接口。
 * 用于判断某个用户是否已被风控系统阻断，并提供黑名单管理能力。
 */
public interface LoginBlacklistStore {

    /**
     * 判断指定用户是否已进入登录黑名单。
     *
     * @param userId 用户 ID
     * @return 命中黑名单返回 true，否则返回 false
     */
    boolean isBlocked(String userId);

    /**
     * 将用户加入登录黑名单。
     *
     * @param userId 用户 ID
     * @param reason 封禁原因
     * @param ttl 过期时间，null 表示永久封禁
     */
    void block(String userId, String reason, Duration ttl);

    /**
     * 将用户从登录黑名单中移除。
     *
     * @param userId 用户 ID
     */
    void unblock(String userId);
}