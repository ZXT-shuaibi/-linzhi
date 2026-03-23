package com.zhiguang.be.auth.mapper;

import com.zhiguang.be.auth.model.AuthUserEntity;

import java.util.Optional;

/**
 * 用户认证数据访问接口。
 */
public interface AuthUserMapper {

    /**
     * 判断手机号是否已注册。
     *
     * @param phone 待校验手机号
     * @return true 表示手机号已存在
     */
    boolean existsByPhone(String phone);

    /**
     * 保存新用户。
     *
     * @param entity 待保存用户实体
     */
    void save(AuthUserEntity entity);

    /**
     * 按手机号原子写入新用户。
     * 若手机号已存在则不写入并返回 false。
     *
     * @param entity 待保存用户实体
     * @return true 表示写入成功，false 表示手机号已存在
     */
    boolean saveIfPhoneAbsent(AuthUserEntity entity);

    /**
     * 按手机号查询用户。
     *
     * @param phone 手机号
     * @return 用户存在时返回实体
     */
    Optional<AuthUserEntity> findByPhone(String phone);

    /**
     * 按用户 ID 查询用户。
     *
     * @param userId 用户 ID
     * @return 用户存在时返回实体
     */
    Optional<AuthUserEntity> findByUserId(String userId);

    /**
     * 更新用户记录。
     *
     * @param entity 含最新字段值的用户实体
     */
    void update(AuthUserEntity entity);
}