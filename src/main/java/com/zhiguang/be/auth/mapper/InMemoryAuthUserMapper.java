package com.zhiguang.be.auth.mapper;

import com.zhiguang.be.auth.model.AuthUserEntity;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Repository
/**
 * 类说明。
 */
public class InMemoryAuthUserMapper implements AuthUserMapper {

    /**
     * 方法：未命名方法。
     */
    private final ConcurrentHashMap<String, AuthUserEntity> usersByPhone = new ConcurrentHashMap<>();

    @Override
    /**
     * 方法说明。
     */
    public boolean existsByPhone(String phone) {
        return usersByPhone.containsKey(phone);
    }

    @Override
    /**
     * 方法说明。
     */
    public void save(AuthUserEntity entity) {
        usersByPhone.put(entity.phone(), entity);
    }

    @Override
    /**
     * 方法说明。
     */
    public Optional<AuthUserEntity> findByPhone(String phone) {
        return Optional.ofNullable(usersByPhone.get(phone));
    }

    @Override
    /**
     * 方法说明。
     */
    public void update(AuthUserEntity entity) {
        usersByPhone.put(entity.phone(), entity);
    }
}
