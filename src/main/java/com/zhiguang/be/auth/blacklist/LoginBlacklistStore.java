package com.zhiguang.be.auth.blacklist;

import java.time.Duration;

/**
 * 登录黑名单存储接口。
 * 用于判断某个登录标识是否已被风控系统阻断。
 */
public interface LoginBlacklistStore {

    /**
     * 判断指定标识是否已经进入登录黑名单。
     *
     * @param identifier 登录标识，当前通常为手机号、账号或用户 ID
     * @return 命中黑名单返回 true，否则返回 false
     */
    boolean isBlocked(String identifier);

    /**
     * 把指定标识加入登录黑名单。
     * 默认空实现，便于不同存储方式按需提供写入能力。
     *
     * @param identifier 登录标识
     * @param ttl 黑名单有效时长，为空或非正数时表示不设置过期
     */
    default void block(String identifier, Duration ttl) {
    }

    /**
     * 从登录黑名单中移除指定标识。
     * 默认空实现，便于测试桩或只读实现保持最小能力集。
     *
     * @param identifier 登录标识
     */
    default void unblock(String identifier) {
    }
}
