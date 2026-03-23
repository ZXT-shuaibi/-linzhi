package com.zhiguang.be.auth.mapper;

import com.zhiguang.be.auth.model.AuthUserEntity;

import java.util.Optional;

/**
 * 接口说明。
 */
public interface AuthUserMapper {

    boolean existsByPhone(String phone);

    void save(AuthUserEntity entity);

    Optional<AuthUserEntity> findByPhone(String phone);

    void update(AuthUserEntity entity);
}
