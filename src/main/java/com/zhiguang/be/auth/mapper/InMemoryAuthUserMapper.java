package com.zhiguang.be.auth.mapper;

import com.zhiguang.be.auth.model.AuthUserEntity;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * AuthUserMapper 的内存实现。
 * 使用线程安全的 ConcurrentHashMap 模拟持久化存储，主要用于本地开发与演示。
 */
@Repository
public class InMemoryAuthUserMapper implements AuthUserMapper {

    /**
     * 以内存 HashMap 按手机号索引用户。
     */
    private final ConcurrentHashMap<String, AuthUserEntity> usersByPhone = new ConcurrentHashMap<>();

    /**
     * 判断手机号是否已存在。
     *
     * @param phone 待查询手机号
     * @return true 表示已存在，false 表示不存在
     */
    @Override
    public boolean existsByPhone(String phone) {
        return usersByPhone.containsKey(phone);
    }

    /**
     * 保存用户实体到内存存储。
     *
     * @param entity 待保存用户
     */
    @Override
    public void save(AuthUserEntity entity) {
        usersByPhone.put(entity.phone(), entity);
    }

    /**
     * 根据手机号查询用户实体。
     *
     * @param phone 手机号
     * @return 匹配到的用户实体；未匹配则返回空 Optional
     */
    @Override
    public Optional<AuthUserEntity> findByPhone(String phone) {
        return Optional.ofNullable(usersByPhone.get(phone));
    }

    /**
     * 更新用户实体。
     *
     * @param entity 包含新状态的用户实体
     */
    @Override
    public void update(AuthUserEntity entity) {
        usersByPhone.put(entity.phone(), entity);
    }
}
