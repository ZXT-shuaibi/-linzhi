package com.zhiguang.be.auth.mapper;

import com.zhiguang.be.auth.model.AuthUserEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.springframework.dao.DuplicateKeyException;

import java.util.Optional;

/**
 * 认证用户数据访问接口。
 */
@Mapper
public interface AuthUserMapper {

    boolean existsByPhone(@Param("phone") String phone);

    void save(AuthUserEntity entity);

    default boolean saveIfAbsent(AuthUserEntity entity) {
        try {
            save(entity);
            return true;
        } catch (DuplicateKeyException ex) {
            return false;
        }
    }

    Optional<AuthUserEntity> findByPhone(@Param("phone") String phone);

    Optional<AuthUserEntity> findByUserId(@Param("userId") String userId);

    void update(AuthUserEntity entity);
}
