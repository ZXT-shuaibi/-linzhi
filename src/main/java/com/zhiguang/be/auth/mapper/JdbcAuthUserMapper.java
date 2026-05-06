package com.zhiguang.be.auth.mapper;

import com.zhiguang.be.auth.model.AuthUserEntity;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
@ConditionalOnProperty(name = "security.user-store", havingValue = "jdbc", matchIfMissing = true)
public class JdbcAuthUserMapper implements AuthUserMapper {

    private static final RowMapper<AuthUserEntity> ROW_MAPPER = (rs, rowNum) -> new AuthUserEntity(
            rs.getString("id"),
            rs.getString("phone"),
            rs.getString("nickname"),
            rs.getString("password_hash")
    );

    private final JdbcTemplate jdbcTemplate;

    public JdbcAuthUserMapper(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public boolean existsByPhone(String phone) {
        Integer count = jdbcTemplate.queryForObject(
                "select count(1) from users where phone = ?", Integer.class, phone);
        return count != null && count > 0;
    }

    @Override
    public void save(AuthUserEntity entity) {
        jdbcTemplate.update(
                "insert into users(id, phone, nickname, password_hash) values (?, ?, ?, ?)",
                Long.parseLong(entity.userId()), entity.phone(), entity.nickname(), entity.passwordHash());
    }

    @Override
    public boolean saveIfAbsent(AuthUserEntity entity) {
        try {
            save(entity);
            return true;
        } catch (DuplicateKeyException ex) {
            return false;
        }
    }

    @Override
    public Optional<AuthUserEntity> findByPhone(String phone) {
        return jdbcTemplate.query(
                "select id, phone, nickname, password_hash from users where phone = ?",
                ROW_MAPPER, phone).stream().findFirst();
    }

    @Override
    public Optional<AuthUserEntity> findByUserId(String userId) {
        return jdbcTemplate.query(
                "select id, phone, nickname, password_hash from users where id = ?",
                ROW_MAPPER, Long.parseLong(userId)).stream().findFirst();
    }

    @Override
    public void update(AuthUserEntity entity) {
        jdbcTemplate.update(
                "update users set phone = ?, nickname = ?, password_hash = ?, updated_at = current_timestamp where id = ?",
                entity.phone(), entity.nickname(), entity.passwordHash(), Long.parseLong(entity.userId()));
    }
}
