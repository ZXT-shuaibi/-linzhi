package com.zhiguang.be.auth.mapper;

import com.zhiguang.be.auth.model.AuthUserEntity;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 认证用户 Mapper 的内存实现。
 * 主要用于开发调试或轻量测试场景，不依赖外部数据库。
 */
@Repository
public class InMemoryAuthUserMapper implements AuthUserMapper {

    private final ConcurrentHashMap<String, AuthUserEntity> usersByPhone = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AuthUserEntity> usersByUsername = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AuthUserEntity> usersByUserId = new ConcurrentHashMap<>();

    /**
     * 判断手机号是否已注册。
     *
     * @param phone 手机号
     * @return 已存在返回 true，否则返回 false
     */
    @Override
    public boolean existsByPhone(String phone) {
        return usersByPhone.containsKey(phone);
    }

    /**
     * 直接保存用户实体到三个索引表中。
     *
     * @param entity 用户实体
     */
    @Override
    public void save(AuthUserEntity entity) {
        usersByPhone.put(entity.phone(), entity);
        usersByUsername.put(entity.username(), entity);
        usersByUserId.put(entity.userId(), entity);
    }

    /**
     * 按手机号和用户名做原子保存，避免并发注册写入重复数据。
     *
     * @param entity 用户实体
     * @return 保存成功返回 true，否则返回 false
     */
    @Override
    public boolean saveIfPhoneAbsent(AuthUserEntity entity) {
        AuthUserEntity phoneConflict = usersByPhone.putIfAbsent(entity.phone(), entity);
        if (phoneConflict != null) {
            return false;
        }

        AuthUserEntity usernameConflict = usersByUsername.putIfAbsent(entity.username(), entity);
        if (usernameConflict != null) {
            usersByPhone.remove(entity.phone(), entity);
            return false;
        }

        AuthUserEntity userIdConflict = usersByUserId.putIfAbsent(entity.userId(), entity);
        if (userIdConflict != null) {
            usersByPhone.remove(entity.phone(), entity);
            usersByUsername.remove(entity.username(), entity);
            return false;
        }
        return true;
    }

    /**
     * 按手机号查找用户。
     *
     * @param phone 手机号
     * @return 用户实体可选值
     */
    @Override
    public Optional<AuthUserEntity> findByPhone(String phone) {
        return Optional.ofNullable(usersByPhone.get(phone));
    }

    /**
     * 按用户名查找用户。
     *
     * @param username 用户名
     * @return 用户实体可选值
     */
    @Override
    public Optional<AuthUserEntity> findByUsername(String username) {
        return Optional.ofNullable(usersByUsername.get(username));
    }

    /**
     * 按手机号或用户名查找用户。
     * 先尝试按手机号查找，如果找不到再按用户名查找。
     *
     * @param identifier 手机号或用户名
     * @return 用户实体可选值
     */
    @Override
    public Optional<AuthUserEntity> findByPhoneOrUsername(String identifier) {
        AuthUserEntity user = usersByPhone.get(identifier);
        if (user != null) {
            return Optional.of(user);
        }
        return Optional.ofNullable(usersByUsername.get(identifier));
    }

    /**
     * 按用户 ID 查找用户。
     *
     * @param userId 用户 ID
     * @return 用户实体可选值
     */
    @Override
    public Optional<AuthUserEntity> findByUserId(String userId) {
        return Optional.ofNullable(usersByUserId.get(userId));
    }

    /**
     * 更新用户实体到手机号、用户名和用户 ID 三套索引中。
     *
     * @param entity 用户实体
     */
    @Override
    public void update(AuthUserEntity entity) {
        usersByPhone.put(entity.phone(), entity);
        usersByUsername.put(entity.username(), entity);
        usersByUserId.put(entity.userId(), entity);
    }
}