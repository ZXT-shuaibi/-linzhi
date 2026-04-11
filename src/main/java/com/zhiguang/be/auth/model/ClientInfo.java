package com.zhiguang.be.auth.model;

/**
 * 客户端信息。
 * 用于在认证流程中传递请求来源 IP 与 User-Agent，便于记录登录日志。
 *
 * @param ip 客户端 IP
 * @param userAgent 客户端 User-Agent
 */
public record ClientInfo(
        String ip,
        String userAgent
) {
}
