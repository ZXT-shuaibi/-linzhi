package com.zhiguang.be.auth.mapper;

import com.zhiguang.be.auth.model.AuthUserEntity;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 认证用户 Mapper 的内存实现。
 * 主要用于开发调试或轻量测试场景，不依赖外部数据库。
 */
@Repository
@ConditionalOnProperty(name = "security.user-store", havingValue = "in-memory")
public class InMemoryAuthUserMapper implements AuthUserMapper {

    private final ConcurrentHashMap<String, AuthUserEntity> usersByPhone = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AuthUserEntity> usersByAccount = new ConcurrentHashMap<>();
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
     * 判断账号是否已经注册。
     *
     * @param account 登录账号
     * @return 已存在返回 true，否则返回 false
     */
    @Override
    public boolean existsByAccount(String account) {
        return usersByAccount.containsKey(account);
    }

    /**
     * 直接保存用户实体到两个索引表中。
     *
     * @param entity 用户实体
     */
    @Override
    public void save(AuthUserEntity entity) {
        usersByPhone.put(entity.phone(), entity);
        usersByAccount.put(entity.account(), entity);
        usersByUserId.put(entity.userId(), entity);
    }

    /**
     * 按手机号做原子保存，避免并发注册写入重复数据。
     *
     * @param entity 用户实体
     * @return 保存成功返回 true，否则返回 false
     */
    @Override
    public synchronized boolean saveIfAbsent(AuthUserEntity entity) {
        if (usersByPhone.containsKey(entity.phone()) || usersByAccount.containsKey(entity.account())
                || usersByUserId.containsKey(entity.userId())) {
            return false;
        }
        usersByPhone.put(entity.phone(), entity);
        usersByAccount.put(entity.account(), entity);
        usersByUserId.put(entity.userId(), entity);
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
     * 按账号查找用户。
     *
     * @param account 登录账号
     * @return 用户实体可选值
     */
    @Override
    public Optional<AuthUserEntity> findByAccount(String account) {
        return Optional.ofNullable(usersByAccount.get(account));
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
     * 更新用户实体到手机号和用户 ID 两套索引中。
     *
     * @param entity 用户实体
     */
    @Override
    public void update(AuthUserEntity entity) {
        usersByPhone.put(entity.phone(), entity);
        usersByAccount.put(entity.account(), entity);
        usersByUserId.put(entity.userId(), entity);
    }
}
