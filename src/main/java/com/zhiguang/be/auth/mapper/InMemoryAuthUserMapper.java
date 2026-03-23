package com.zhiguang.be.auth.mapper;

import com.zhiguang.be.auth.model.AuthUserEntity;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * AuthUserMapper 的内存实现。
 */
@Repository
public class InMemoryAuthUserMapper implements AuthUserMapper {

    /**
     * 按手机号索引用户。
     */
    private final ConcurrentHashMap<String, AuthUserEntity> usersByPhone = new ConcurrentHashMap<>();

    /**
     * 按用户 ID 索引用户。
     */
    private final ConcurrentHashMap<String, AuthUserEntity> usersByUserId = new ConcurrentHashMap<>();

    @Override
    /**
     * 判断手机号是否存在。
     */
    public boolean existsByPhone(String phone) {
        return usersByPhone.containsKey(phone);
    }

    @Override
    /**
     * 保存用户实体。
     */
    public void save(AuthUserEntity entity) {
        usersByPhone.put(entity.phone(), entity);
        usersByUserId.put(entity.userId(), entity);
    }

    @Override
    /**
     * 按手机号原子写入新用户。
     */
    public boolean saveIfPhoneAbsent(AuthUserEntity entity) {
        AuthUserEntity existed = usersByPhone.putIfAbsent(entity.phone(), entity);
        if (existed != null) {
            return false;
        }

        AuthUserEntity userIdConflict = usersByUserId.putIfAbsent(entity.userId(), entity);
        if (userIdConflict != null) {
            usersByPhone.remove(entity.phone(), entity);
            return false;
        }
        return true;
    }

    @Override
    /**
     * 按手机号查询用户。
     */
    public Optional<AuthUserEntity> findByPhone(String phone) {
        return Optional.ofNullable(usersByPhone.get(phone));
    }

    @Override
    /**
     * 按用户 ID 查询用户。
     */
    public Optional<AuthUserEntity> findByUserId(String userId) {
        return Optional.ofNullable(usersByUserId.get(userId));
    }

    @Override
    /**
     * 更新用户实体。
     */
    public void update(AuthUserEntity entity) {
        usersByPhone.put(entity.phone(), entity);
        usersByUserId.put(entity.userId(), entity);
    }
}