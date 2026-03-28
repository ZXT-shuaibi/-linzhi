package com.zhiguang.be.auth.blacklist;

/**
 * 登录黑名单存储接口。
 * 用于判断某个登录标识是否已被风控系统阻断。
 */
public interface LoginBlacklistStore {

    /**
     * 判断指定标识是否已进入登录黑名单。
     *
     * @param identifier 登录标识，当前通常为手机号
     * @return 命中黑名单返回 true，否则返回 false
     */
    boolean isBlocked(String identifier);
}