package com.zhiguang.be.auth.audit;

/**
 * 审计日志接口。
 * 负责输出注册、登录、刷新令牌等认证动作的审计记录。
 */
public interface AuditLogger {

    /**
     * 记录一条审计事件。
     *
     * @param event 审计事件对象
     */
    void log(AuditEvent event);
}