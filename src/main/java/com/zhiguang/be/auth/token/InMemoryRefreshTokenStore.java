package com.zhiguang.be.auth.token;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 基于内存的刷新令牌白名单实现。
 * 适合本地开发和单实例测试环境，不适合多节点生产部署。
 */
@Component
@ConditionalOnProperty(name = "security.refresh-store", havingValue = "in-memory")
public class InMemoryRefreshTokenStore implements RefreshTokenStore {

    private final ConcurrentHashMap<String, Instant> refreshKeyToExpiresAt = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Set<String>> userToKeys = new ConcurrentHashMap<>();

    /**
     * 保存刷新令牌及其过期时间，并维护用户到令牌集合的索引。
     *
     * @param userId 用户 ID
     * @param jti 令牌唯一标识
     * @param expiresAt 过期时间
     */
    @Override
    public synchronized void save(String userId, String jti, Instant expiresAt) {
        String key = toKey(userId, jti);
        refreshKeyToExpiresAt.put(key, expiresAt);
        userToKeys.computeIfAbsent(userId, ignored -> ConcurrentHashMap.newKeySet()).add(key);
    }

    /**
     * 判断令牌是否存在且尚未过期。
     *
     * @param userId 用户 ID
     * @param jti 令牌唯一标识
     * @return 有效返回 true，否则返回 false
     */
    @Override
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

    /**
     * 消费刷新令牌，确保同一令牌只能成功使用一次。
     *
     * @param userId 用户 ID
     * @param jti 令牌唯一标识
     * @return 消费成功返回 true，否则返回 false
     */
    @Override
    public synchronized boolean consumeIfValid(String userId, String jti) {
        String key = toKey(userId, jti);
        Instant expiresAt = refreshKeyToExpiresAt.remove(key);
        if (expiresAt == null) {
            return false;
        }

        removeUserIndex(userId, key);
        return !Instant.now().isAfter(expiresAt);
    }

    /**
     * 原子轮换刷新令牌。
     * 仅当旧令牌存在且未过期时，才会删除旧令牌并写入新的刷新令牌。
     *
     * @param userId 用户 ID
     * @param oldJti 旧令牌唯一标识
     * @param newJti 新令牌唯一标识
     * @param newExpiresAt 新令牌过期时间
     * @return 轮换成功返回 true，否则返回 false
     */
    @Override
    public synchronized boolean rotate(String userId, String oldJti, String newJti, Instant newExpiresAt) {
        String oldKey = toKey(userId, oldJti);
        Instant oldExpiresAt = refreshKeyToExpiresAt.get(oldKey);
        if (oldExpiresAt == null || Instant.now().isAfter(oldExpiresAt)) {
            remove(userId, oldJti);
            return false;
        }

        refreshKeyToExpiresAt.remove(oldKey);
        removeUserIndex(userId, oldKey);

        String newKey = toKey(userId, newJti);
        refreshKeyToExpiresAt.put(newKey, newExpiresAt);
        userToKeys.computeIfAbsent(userId, ignored -> ConcurrentHashMap.newKeySet()).add(newKey);
        return true;
    }

    /**
     * 撤销单个刷新令牌。
     *
     * @param userId 用户 ID
     * @param jti 令牌唯一标识
     */
    @Override
    public void remove(String userId, String jti) {
        String key = toKey(userId, jti);
        refreshKeyToExpiresAt.remove(key);
        removeUserIndex(userId, key);
    }

    /**
     * 撤销指定用户的全部刷新令牌。
     *
     * @param userId 用户 ID
     */
    @Override
    public synchronized void removeAll(String userId) {
        Set<String> keys = userToKeys.remove(userId);
        if (keys == null) {
            return;
        }
        for (String key : keys) {
            refreshKeyToExpiresAt.remove(key);
        }
    }

    /**
     * 从用户索引中移除指定令牌 key。
     *
     * @param userId 用户 ID
     * @param key 令牌组合 key
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
     * 生成内存存储使用的组合 key。
     *
     * @param userId 用户 ID
     * @param jti 令牌唯一标识
     * @return 组合 key
     */
    private String toKey(String userId, String jti) {
        return userId + ":" + jti;
    }
}
