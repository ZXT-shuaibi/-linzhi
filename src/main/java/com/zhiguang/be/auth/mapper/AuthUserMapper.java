package com.zhiguang.be.auth.mapper;

import com.zhiguang.be.auth.model.AuthUserEntity;

import java.util.Optional;

/**
 * 认证用户数据访问接口。
 * 对外定义用户注册、查询和更新所需的持久化能力。
 */
public interface AuthUserMapper {

    /**
     * 判断手机号是否已经存在。
     *
     * @param phone 手机号
     * @return 已存在返回 true，否则返回 false
     */
    boolean existsByPhone(String phone);

    /**
     * 保存用户实体。
     *
     * @param entity 用户实体
     */
    void save(AuthUserEntity entity);

    /**
     * 以手机号为唯一键尝试保存用户。
     *
     * @param entity 用户实体
     * @return 保存成功返回 true，手机号已存在返回 false
     */
    boolean saveIfPhoneAbsent(AuthUserEntity entity);

    /**
     * 按手机号查询用户。
     *
     * @param phone 手机号
     * @return 命中时返回用户实体
     */
    Optional<AuthUserEntity> findByPhone(String phone);

    /**
     * 按用户名查询用户。
     *
     * @param username 用户名
     * @return 命中时返回用户实体
     */
    Optional<AuthUserEntity> findByUsername(String username);

    /**
     * 按手机号或用户名查询用户。
     * 先尝试按手机号查找，如果找不到再按用户名查找。
     *
     * @param identifier 手机号或用户名
     * @return 命中时返回用户实体
     */
    Optional<AuthUserEntity> findByPhoneOrUsername(String identifier);

    /**
     * 按用户 ID 查询用户。
     *
     * @param userId 用户 ID
     * @return 命中时返回用户实体
     */
    Optional<AuthUserEntity> findByUserId(String userId);

    /**
     * 更新用户实体。
     *
     * @param entity 含最新信息的用户实体
     */
    void update(AuthUserEntity entity);
}