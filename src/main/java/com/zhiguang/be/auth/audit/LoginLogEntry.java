package com.zhiguang.be.auth.audit;

import java.time.Instant;

/**
 * 登录日志实体。
 * 对应数据库中的 login_logs 表，用于持久化注册和登录相关审计信息。
 *
 * @param id 日志唯一 ID
 * @param userId 用户 ID，未命中用户时可为空
 * @param identifier 登录或注册使用的标识
 * @param channel 认证方式，如 REGISTER、PASSWORD、CODE
 * @param ip 客户端 IP
 * @param userAgent 客户端 User-Agent
 * @param status 结果状态，如 SUCCESS、FAILED、BLOCKED
 * @param message 补充说明
 * @param createdAt 创建时间
 */
public record LoginLogEntry(
        long id,
        String userId,
        String identifier,
        String channel,
        String ip,
        String userAgent,
        String status,
        String message,
        Instant createdAt
) {
}
