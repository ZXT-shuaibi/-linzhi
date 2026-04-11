package com.zhiguang.be.auth.mapper;

import com.zhiguang.be.auth.model.AuthUserEntity;

import java.util.Optional;

/**
 * 认证用户数据访问接口。
 * 对外定义认证模块需要的用户查询、注册和更新能力。
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
     * 判断账号是否已经存在。
     *
     * @param account 登录账号
     * @return 已存在返回 true，否则返回 false
     */
    boolean existsByAccount(String account);

    /**
     * 直接保存用户实体。
     *
     * @param entity 用户实体
     */
    void save(AuthUserEntity entity);

    /**
     * 在手机号和账号都不存在时保存用户。
     *
     * @param entity 用户实体
     * @return 保存成功返回 true，否则返回 false
     */
    boolean saveIfAbsent(AuthUserEntity entity);

    /**
     * 按手机号查询用户。
     *
     * @param phone 手机号
     * @return 命中时返回用户
     */
    Optional<AuthUserEntity> findByPhone(String phone);

    /**
     * 按账号查询用户。
     *
     * @param account 登录账号
     * @return 命中时返回用户
     */
    Optional<AuthUserEntity> findByAccount(String account);

    /**
     * 按登录标识查询用户。
     * 优先按手机号查询，未命中时再按账号查询。
     *
     * @param identifier 登录标识
     * @return 命中时返回用户
     */
    default Optional<AuthUserEntity> findByIdentifier(String identifier) {
        return findByPhone(identifier).or(() -> findByAccount(identifier));
    }

    /**
     * 按用户 ID 查询用户。
     *
     * @param userId 用户 ID
     * @return 命中时返回用户
     */
    Optional<AuthUserEntity> findByUserId(String userId);

    /**
     * 更新用户实体。
     *
     * @param entity 含最新信息的用户实体
     */
    void update(AuthUserEntity entity);
}
