package com.zhiguang.be.auth.audit;

/**
 * 审计日志服务接口。
 */
public interface AuditLogger {

    /**
     * 记录审计事件。
     *
     * @param event 审计事件
     */
    void log(AuditEvent event);
}
