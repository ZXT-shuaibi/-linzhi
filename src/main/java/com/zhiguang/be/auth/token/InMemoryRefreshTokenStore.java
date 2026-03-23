package com.zhiguang.be.auth.token;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Component
@ConditionalOnProperty(name = "security.refresh-store", havingValue = "in-memory")
/**
 * InMemory 刷新令牌白名单实现。
 */
public class InMemoryRefreshTokenStore implements RefreshTokenStore {

    /**
     * key: userId:jti, value: expiresAt。
     */
    private final ConcurrentHashMap<String, Instant> refreshKeyToExpiresAt = new ConcurrentHashMap<>();

    /**
     * key: userId, value: 该用户所有 refresh key 集合。
     */
    private final ConcurrentHashMap<String, Set<String>> userToKeys = new ConcurrentHashMap<>();

    @Override
    /**
     * 保存刷新令牌白名单记录。
     */
    public void save(String userId, String jti, Instant expiresAt) {
        String key = toKey(userId, jti);
        refreshKeyToExpiresAt.put(key, expiresAt);
        userToKeys.computeIfAbsent(userId, ignored -> ConcurrentHashMap.newKeySet()).add(key);
    }

    @Override
    /**
     * 判断刷新令牌是否有效。
     */
    public boolean isValid(String userId, String jti) {
        String key = toKey(userId, jti);
        Instant expiresAt = refreshKeyToExpiresAt.get(key);
        if (expiresAt == null) {
            return false;
        }
        if (Instant.now().isAfter(expiresAt)) {
            remove(userId, jti);
            return false;
        }
        return true;
    }

    @Override
    /**
     * 原子消费刷新令牌，确保同一个 refresh token 只能使用一次。
     */
    public boolean consumeIfValid(String userId, String jti) {
        String key = toKey(userId, jti);
        Instant expiresAt = refreshKeyToExpiresAt.remove(key);
        if (expiresAt == null) {
            return false;
        }

        removeUserIndex(userId, key);
        return !Instant.now().isAfter(expiresAt);
    }

    @Override
    /**
     * 撤销单个刷新令牌。
     */
    public void remove(String userId, String jti) {
        String key = toKey(userId, jti);
        refreshKeyToExpiresAt.remove(key);
        removeUserIndex(userId, key);
    }

    @Override
    /**
     * 撤销用户全部刷新令牌。
     */
    public void removeAll(String userId) {
        Set<String> keys = userToKeys.remove(userId);
        if (keys == null) {
            return;
        }
        for (String key : keys) {
            refreshKeyToExpiresAt.remove(key);
        }
    }

    /**
     * 移除用户索引中的 refresh key。
     */
    private void removeUserIndex(String userId, String key) {
        Set<String> keys = userToKeys.get(userId);
        if (keys != null) {
            keys.remove(key);
            if (keys.isEmpty()) {
                userToKeys.remove(userId);
            }
        }
    }

    /**
     * 拼装 refresh key。
     */
    private String toKey(String userId, String jti) {
        return userId + ":" + jti;
    }
}