package com.zhiguang.be.auth.blacklist;

import com.zhiguang.be.auth.model.AuthUserEntity;
import com.zhiguang.be.auth.token.RefreshTokenStore;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 认证黑名单写入服务。
 * 统一封装登录黑名单、access token 失效标记和 refresh token 撤销等写操作，
 * 避免这些动作散落在多个业务入口里。
 */
@Service
public class AuthBlocklistService {

    private final LoginBlacklistStore loginBlacklistStore;
    private final AccessTokenBlocklistStore accessTokenBlocklistStore;
    private final RefreshTokenStore refreshTokenStore;

    /**
     * 构造认证黑名单写入服务。
     *
     * @param loginBlacklistStore 登录黑名单存储
     * @param accessTokenBlocklistStore access token 失效黑名单存储
     * @param refreshTokenStore refresh token 存储
     */
    public AuthBlocklistService(
            LoginBlacklistStore loginBlacklistStore,
            AccessTokenBlocklistStore accessTokenBlocklistStore,
            RefreshTokenStore refreshTokenStore
    ) {
        this.loginBlacklistStore = loginBlacklistStore;
        this.accessTokenBlocklistStore = accessTokenBlocklistStore;
        this.refreshTokenStore = refreshTokenStore;
    }

    /**
     * 记录某个用户的 access token 失效时间点。
     * 适用于登出、重置密码等“要让旧 access token 立刻失效”的场景。
     *
     * @param userId 用户 ID
     * @param ttl 黑名单保留时长
     */
    public void blockAccessTokens(String userId, Duration ttl) {
        if (!StringUtils.hasText(userId)) {
            return;
        }
        accessTokenBlocklistStore.block(userId, Instant.now(), normalizeTtl(ttl));
    }

    /**
     * 撤销指定用户全部 refresh token，并同步拉起 access token 失效标记。
     * 适用于账号异常、重置密码、被拦截后的全量会话清理。
     *
     * @param userId 用户 ID
     * @param ttl access token 黑名单保留时长
     */
    public void revokeAllSessionsAndBlockAccessTokens(String userId, Duration ttl) {
        if (!StringUtils.hasText(userId)) {
            return;
        }
        refreshTokenStore.removeAll(userId);
        blockAccessTokens(userId, ttl);
    }

    /**
     * 把某个账号相关的登录标识统一写入登录黑名单。
     * 会覆盖 userId、手机号和账号名，方便后续对封禁场景复用。
     *
     * @param account 认证域用户
     * @param ttl 登录黑名单保留时长
     */
    public void blockLogin(AuthUserEntity account, Duration ttl) {
        for (String identifier : relatedIdentifiers(account)) {
            loginBlacklistStore.block(identifier, ttl);
        }
    }

    /**
     * 把单个登录标识写入登录黑名单。
     *
     * @param identifier 登录标识
     * @param ttl 登录黑名单保留时长
     */
    public void blockLogin(String identifier, Duration ttl) {
        if (!StringUtils.hasText(identifier)) {
            return;
        }
        loginBlacklistStore.block(identifier, ttl);
    }

    /**
     * 从登录黑名单里移除某个账号相关的全部标识。
     *
     * @param account 认证域用户
     */
    public void unblockLogin(AuthUserEntity account) {
        for (String identifier : relatedIdentifiers(account)) {
            loginBlacklistStore.unblock(identifier);
        }
    }

    /**
     * 从登录黑名单里移除单个标识。
     *
     * @param identifier 登录标识
     */
    public void unblockLogin(String identifier) {
        if (!StringUtils.hasText(identifier)) {
            return;
        }
        loginBlacklistStore.unblock(identifier);
    }

    /**
     * 汇总一个账号在认证域里会用到的登录标识（userId + 手机号）。
     *
     * @param account 认证域用户
     * @return 相关标识集合
     */
    private Set<String> relatedIdentifiers(AuthUserEntity account) {
        LinkedHashSet<String> identifiers = new LinkedHashSet<>();
        if (account == null) {
            return identifiers;
        }
        if (StringUtils.hasText(account.userId())) {
            identifiers.add(account.userId());
        }
        if (StringUtils.hasText(account.phone())) {
            identifiers.add(account.phone());
        }
        return identifiers;
    }

    /**
     * 规范化黑名单 TTL。
     *
     * @param ttl 原始时长
     * @return 至少为 1 秒的有效时长
     */
    private Duration normalizeTtl(Duration ttl) {
        if (ttl == null || ttl.isNegative() || ttl.isZero()) {
            return Duration.ofSeconds(1);
        }
        return ttl;
    }
}
