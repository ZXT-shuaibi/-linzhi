package com.zhiguang.be.auth.mapper;

import com.zhiguang.be.auth.model.AuthUserEntity;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Repository
@ConditionalOnProperty(name = "security.user-store", havingValue = "in-memory")
public class InMemoryAuthUserMapper implements AuthUserMapper {

    private final ConcurrentHashMap<String, AuthUserEntity> usersByPhone = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AuthUserEntity> usersByUserId = new ConcurrentHashMap<>();

    @Override
    public boolean existsByPhone(String phone) {
        return usersByPhone.containsKey(phone);
    }

    @Override
    public void save(AuthUserEntity entity) {
        usersByPhone.put(entity.phone(), entity);
        usersByUserId.put(entity.userId(), entity);
    }

    @Override
    public synchronized boolean saveIfAbsent(AuthUserEntity entity) {
        if (usersByPhone.containsKey(entity.phone()) || usersByUserId.containsKey(entity.userId())) {
            return false;
        }
        usersByPhone.put(entity.phone(), entity);
        usersByUserId.put(entity.userId(), entity);
        return true;
    }

    @Override
    public Optional<AuthUserEntity> findByPhone(String phone) {
        return Optional.ofNullable(usersByPhone.get(phone));
    }

    @Override
    public Optional<AuthUserEntity> findByUserId(String userId) {
        return Optional.ofNullable(usersByUserId.get(userId));
    }

    @Override
    public void update(AuthUserEntity entity) {
        usersByPhone.put(entity.phone(), entity);
        usersByUserId.put(entity.userId(), entity);
    }
}
