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
                maskIdentifier(identifier),
                channel,
                maskIp(ip),
                userAgent,
                status,
                message,
                Instant.now()
        );
        loginLogMapper.insert(entry);
    }

    private String maskIdentifier(String identifier) {
        if (identifier == null || identifier.isBlank()) {
            return identifier;
        }
        String trimmed = identifier.trim();
        if (trimmed.matches("^1\\d{10}$")) {
            return trimmed.substring(0, 3) + "****" + trimmed.substring(7);
        }
        if (trimmed.length() <= 4) {
            return "****";
        }
        return trimmed.charAt(0) + "****" + trimmed.substring(trimmed.length() - 2);
    }

    private String maskIp(String ip) {
        if (ip == null || ip.isBlank()) {
            return ip;
        }
        String trimmed = ip.trim();
        int lastDot = trimmed.lastIndexOf('.');
        if (lastDot > 0 && trimmed.indexOf(':') < 0) {
            return trimmed.substring(0, lastDot + 1) + "*";
        }
        int secondColon = nthIndexOf(trimmed, ':', 2);
        if (secondColon > 0) {
            return trimmed.substring(0, secondColon) + ":****";
        }
        return "****";
    }

    private int nthIndexOf(String value, char target, int count) {
        int found = 0;
        for (int i = 0; i < value.length(); i++) {
            if (value.charAt(i) == target && ++found == count) {
                return i;
            }
        }
        return -1;
    }
}
