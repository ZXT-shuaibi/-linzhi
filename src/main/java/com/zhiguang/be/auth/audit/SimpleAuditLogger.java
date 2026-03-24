package com.zhiguang.be.auth.audit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 简单审计日志实现，记录到应用日志。
 */
@Component
public class SimpleAuditLogger implements AuditLogger {

    private static final Logger log = LoggerFactory.getLogger("AUDIT");

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
