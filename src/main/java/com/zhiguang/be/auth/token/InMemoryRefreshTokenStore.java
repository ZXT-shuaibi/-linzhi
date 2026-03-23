package com.zhiguang.be.auth.token;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Component
@ConditionalOnProperty(name = "security.refresh-store", havingValue = "in-memory")
/**
 * 类说明。
 */
public class InMemoryRefreshTokenStore implements RefreshTokenStore {

    /**
     * 方法：未命名方法。
     */
    private final ConcurrentHashMap<String, Instant> refreshKeyToExpiresAt = new ConcurrentHashMap<>();
    /**
     * 方法：未命名方法。
     */
    private final ConcurrentHashMap<String, Set<String>> userToKeys = new ConcurrentHashMap<>();

    @Override
    /**
     * 方法说明。
     */
    public void save(String userId, String jti, Instant expiresAt) {
        String key = toKey(userId, jti);
        refreshKeyToExpiresAt.put(key, expiresAt);
        userToKeys.computeIfAbsent(userId, ignored -> ConcurrentHashMap.newKeySet()).add(key);
    }

    @Override
    /**
     * 方法说明。
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
     * 方法说明。
     */
    public void remove(String userId, String jti) {
        String key = toKey(userId, jti);
        refreshKeyToExpiresAt.remove(key);
        Set<String> keys = userToKeys.get(userId);
        if (keys != null) {
            keys.remove(key);
            if (keys.isEmpty()) {
                userToKeys.remove(userId);
            }
        }
    }

    @Override
    /**
     * 方法说明。
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
     * 方法说明。
     */
    private String toKey(String userId, String jti) {
        return userId + ":" + jti;
    }
}
