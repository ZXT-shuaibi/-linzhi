package com.zhiguang.be.auth.mapper;

import com.zhiguang.be.auth.model.AuthUserEntity;

import java.util.Optional;

/**
 * 用户认证数据访问接口。
 * 封装认证用户在持久层的查询与更新行为。
 */
public interface AuthUserMapper {

    /**
     * 判断手机号是否已注册。
     *
     * @param phone 待校验手机号
     * @return true 表示该手机号已存在，false 表示不存在
     */
    boolean existsByPhone(String phone);

    /**
     * 保存新用户记录。
     *
     * @param entity 待保存的用户实体
     */
    void save(AuthUserEntity entity);

    /**
     * 根据手机号查询用户。
     *
     * @param phone 手机号
     * @return 用户实体（存在时返回），不存在则返回空 Optional
     */
    Optional<AuthUserEntity> findByPhone(String phone);

    /**
     * 更新用户记录。
     *
     * @param entity 包含最新字段值的用户实体
     */
    void update(AuthUserEntity entity);
}
