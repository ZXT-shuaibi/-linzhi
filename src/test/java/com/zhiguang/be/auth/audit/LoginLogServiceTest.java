package com.zhiguang.be.auth.audit;

import com.zhiguang.be.common.id.SnowflakeIdGenerator;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class LoginLogServiceTest {

    @Test
    void recordShouldMaskSensitiveIdentifierAndIpBeforePersisting() {
        LoginLogMapper mapper = mock(LoginLogMapper.class);
        LoginLogService service = new LoginLogService(mapper, new SnowflakeIdGenerator(1, 1));

        service.record("10001", "13800138000", "PASSWORD", "203.0.113.9", "JUnit", "SUCCESS", "ok");

        ArgumentCaptor<LoginLogEntry> captor = ArgumentCaptor.forClass(LoginLogEntry.class);
        verify(mapper).insert(captor.capture());
        LoginLogEntry entry = captor.getValue();

        assertEquals("138****8000", entry.identifier());
        assertEquals("203.0.113.*", entry.ip());
        assertEquals("10001", entry.userId());
        assertEquals("PASSWORD", entry.channel());
        assertNotNull(entry.createdAt());
    }
}
