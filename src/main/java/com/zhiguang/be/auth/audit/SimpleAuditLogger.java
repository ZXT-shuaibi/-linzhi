package com.zhiguang.be.auth.audit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 简单审计日志实现。
 * 直接将审计事件输出到应用日志，便于开发和轻量场景使用。
 */
@Component
public class SimpleAuditLogger implements AuditLogger {

    private static final Logger log = LoggerFactory.getLogger("AUDIT");

    /**
     * 按统一格式输出审计日志。
     * 成功事件记为 info，失败事件记为 warn，便于后续检索和告警。
     *
     * @param event 审计事件对象
     */
    @Override
    public void log(AuditEvent event) {
        String logMessage = String.format("[%s] type=%s, identifier=%s, success=%s, message=%s",
                event.timestamp(),
                event.eventType(),
                event.identifier(),
                event.success(),
                event.message()
        );

        if (event.success()) {
            log.info(logMessage);
        } else {
            log.warn(logMessage);
        }
    }
}