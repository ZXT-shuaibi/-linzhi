package com.zhiguang.be.auth.audit;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.util.StringUtils;

/**
 * 基于 JDBC 的登录日志持久化实现。
 * 将登录日志写入 login_logs 表，便于后续查询、排障和风控回溯。
 */
@Repository
public class JdbcLoginLogMapper implements LoginLogMapper {

    private final JdbcTemplate jdbcTemplate;

    /**
     * 构造 JDBC 登录日志持久化实现。
     *
     * @param jdbcTemplate JDBC 模板
     */
    public JdbcLoginLogMapper(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 将登录日志写入数据库。
     *
     * @param entry 登录日志实体
     */
    @Override
    public void insert(LoginLogEntry entry) {
        jdbcTemplate.update(
                """
                insert into login_logs(id, user_id, identifier, channel, ip, user_agent, status, message, created_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                entry.id(),
                toLong(entry.userId()),
                entry.identifier(),
                entry.channel(),
                entry.ip(),
                entry.userAgent(),
                entry.status(),
                entry.message(),
                entry.createdAt()
        );
    }

    /**
     * 将字符串形式的用户 ID 转换为数据库可写入的 Long。
     *
     * @param userId 用户 ID 字符串
     * @return 可写入数据库的 Long，空值时返回 null
     */
    private Long toLong(String userId) {
        if (!StringUtils.hasText(userId)) {
            return null;
        }
        return Long.parseLong(userId);
    }
}
