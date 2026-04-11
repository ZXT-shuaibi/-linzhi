package com.zhiguang.be.auth.audit;

import com.zhiguang.be.common.id.SnowflakeIdGenerator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * 登录日志服务。
 * 参考 zhiguang，把注册和登录相关审计日志统一沉淀到数据库。
 */
@Service
public class LoginLogService {

    private final LoginLogMapper loginLogMapper;
    private final SnowflakeIdGenerator snowflakeIdGenerator;

    /**
     * 构造登录日志服务。
     *
     * @param loginLogMapper 登录日志持久化组件
     * @param snowflakeIdGenerator 雪花算法 ID 生成器
     */
    public LoginLogService(LoginLogMapper loginLogMapper, SnowflakeIdGenerator snowflakeIdGenerator) {
        this.loginLogMapper = loginLogMapper;
        this.snowflakeIdGenerator = snowflakeIdGenerator;
    }

    /**
     * 记录一条注册或登录日志。
     *
     * @param userId 用户 ID，未命中用户时可为空
     * @param identifier 登录或注册标识
     * @param channel 认证方式，如 REGISTER、PASSWORD、CODE
     * @param ip 客户端 IP
     * @param userAgent 客户端 User-Agent
     * @param status 结果状态
     * @param message 补充说明
     */
    @Transactional
    public void record(String userId, String identifier, String channel, String ip, String userAgent, String status, String message) {
        LoginLogEntry entry = new LoginLogEntry(
                snowflakeIdGenerator.nextId(),
                userId,
                identifier,
                channel,
                ip,
                userAgent,
                status,
                message,
                Instant.now()
        );
        loginLogMapper.insert(entry);
    }
}
