package com.zhiguang.be.auth.mapper;

import com.zhiguang.be.auth.model.AuthUserEntity;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * 基于 JDBC 的认证用户持久化实现。
 * 默认使用本地文件数据库，后续也可以平滑切换到 MySQL。
 */
@Repository
@ConditionalOnProperty(name = "security.user-store", havingValue = "jdbc", matchIfMissing = true)
public class JdbcAuthUserMapper implements AuthUserMapper {

    private static final RowMapper<AuthUserEntity> ROW_MAPPER = (rs, rowNum) -> new AuthUserEntity(
            rs.getString("id"),
            rs.getString("phone"),
            rs.getString("account"),
            rs.getString("nickname"),
            rs.getString("password_hash")
    );

    private final JdbcTemplate jdbcTemplate;

    /**
     * 构造 JDBC 用户持久化实现。
     *
     * @param jdbcTemplate JDBC 模板
     */
    public JdbcAuthUserMapper(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 判断手机号是否已经注册。
     *
     * @param phone 手机号
     * @return 已存在返回 true，否则返回 false
     */
    @Override
    public boolean existsByPhone(String phone) {
        Integer count = jdbcTemplate.queryForObject(
                "select count(1) from users where phone = ?",
                Integer.class,
                phone
        );
        return count != null && count > 0;
    }

    /**
     * 判断账号是否已经注册。
     *
     * @param account 登录账号
     * @return 已存在返回 true，否则返回 false
     */
    @Override
    public boolean existsByAccount(String account) {
        Integer count = jdbcTemplate.queryForObject(
                "select count(1) from users where account = ?",
                Integer.class,
                account
        );
        return count != null && count > 0;
    }

    /**
     * 保存用户记录。
     *
     * @param entity 用户实体
     */
    @Override
    public void save(AuthUserEntity entity) {
        jdbcTemplate.update(
                """
                insert into users(id, phone, account, nickname, password_hash)
                values (?, ?, ?, ?, ?)
                """,
                Long.parseLong(entity.userId()),
                entity.phone(),
                entity.account(),
                entity.nickname(),
                entity.passwordHash()
        );
    }

    /**
     * 尝试保存用户记录，命中唯一键冲突时返回 false。
     *
     * @param entity 用户实体
     * @return 保存成功返回 true，否则返回 false
     */
    @Override
    public boolean saveIfAbsent(AuthUserEntity entity) {
        try {
            save(entity);
            return true;
        } catch (DuplicateKeyException ex) {
            return false;
        }
    }

    /**
     * 按手机号查询用户。
     *
     * @param phone 手机号
     * @return 命中时返回用户实体
     */
    @Override
    public Optional<AuthUserEntity> findByPhone(String phone) {
        return jdbcTemplate.query(
                "select id, phone, account, nickname, password_hash from users where phone = ?",
                ROW_MAPPER,
                phone
        ).stream().findFirst();
    }

    /**
     * 按账号查询用户。
     *
     * @param account 登录账号
     * @return 命中时返回用户实体
     */
    @Override
    public Optional<AuthUserEntity> findByAccount(String account) {
        return jdbcTemplate.query(
                "select id, phone, account, nickname, password_hash from users where account = ?",
                ROW_MAPPER,
                account
        ).stream().findFirst();
    }

    /**
     * 按用户 ID 查询用户。
     *
     * @param userId 用户 ID
     * @return 命中时返回用户实体
     */
    @Override
    public Optional<AuthUserEntity> findByUserId(String userId) {
        return jdbcTemplate.query(
                "select id, phone, account, nickname, password_hash from users where id = ?",
                ROW_MAPPER,
                Long.parseLong(userId)
        ).stream().findFirst();
    }

    /**
     * 更新用户资料和密码哈希。
     *
     * @param entity 最新用户实体
     */
    @Override
    public void update(AuthUserEntity entity) {
        jdbcTemplate.update(
                """
                update users
                   set phone = ?, account = ?, nickname = ?, password_hash = ?, updated_at = current_timestamp
                 where id = ?
                """,
                entity.phone(),
                entity.account(),
                entity.nickname(),
                entity.passwordHash(),
                Long.parseLong(entity.userId())
        );
    }
}
