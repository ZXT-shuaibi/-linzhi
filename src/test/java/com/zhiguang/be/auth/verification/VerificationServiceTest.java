package com.zhiguang.be.auth.verification;

import com.zhiguang.be.common.exception.BusinessException;
import com.zhiguang.be.common.exception.ErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 验证验证码领域服务会把底层校验状态映射成明确的业务错误码。
 */
class VerificationServiceTest {

    /**
     * 验证验证码不存在时会返回独立的不存在错误码。
     */
    @Test
    void verifyOrThrowShouldReportNotFound() {
        VerificationCodeStore codeStore = mock(VerificationCodeStore.class);
        when(codeStore.verify("register", "13800138000", "123456"))
                .thenReturn(new VerificationCheckResult(VerificationCodeStatus.NOT_FOUND, 0, 0));
        VerificationService service = new VerificationService(codeStore, mock(StringRedisTemplate.class), new VerificationProperties());

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.verifyOrThrow(VerificationScene.REGISTER, "13800138000", "123456"));

        assertEquals(ErrorCode.VERIFICATION_NOT_FOUND, ex.errorCode());
        assertEquals(HttpStatus.BAD_REQUEST, ex.httpStatus());
    }

    /**
     * 验证验证码过期时会返回独立的过期错误码。
     */
    @Test
    void verifyOrThrowShouldReportExpired() {
        VerificationCodeStore codeStore = mock(VerificationCodeStore.class);
        when(codeStore.verify("register", "13800138000", "123456"))
                .thenReturn(new VerificationCheckResult(VerificationCodeStatus.EXPIRED, 0, 0));
        VerificationService service = new VerificationService(codeStore, mock(StringRedisTemplate.class), new VerificationProperties());

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.verifyOrThrow(VerificationScene.REGISTER, "13800138000", "123456"));

        assertEquals(ErrorCode.VERIFICATION_EXPIRED, ex.errorCode());
        assertEquals(HttpStatus.BAD_REQUEST, ex.httpStatus());
    }

    /**
     * 验证验证码错误时会返回独立的错误状态码。
     */
    @Test
    void verifyOrThrowShouldReportMismatch() {
        VerificationCodeStore codeStore = mock(VerificationCodeStore.class);
        when(codeStore.verify("register", "13800138000", "123456"))
                .thenReturn(new VerificationCheckResult(VerificationCodeStatus.MISMATCH, 1, 5));
        VerificationService service = new VerificationService(codeStore, mock(StringRedisTemplate.class), new VerificationProperties());

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.verifyOrThrow(VerificationScene.REGISTER, "13800138000", "123456"));

        assertEquals(ErrorCode.VERIFICATION_MISMATCH, ex.errorCode());
        assertEquals(HttpStatus.BAD_REQUEST, ex.httpStatus());
    }

    /**
     * 验证验证码尝试次数过多时会返回限频类错误码。
     */
    @Test
    void verifyOrThrowShouldReportTooManyAttempts() {
        VerificationCodeStore codeStore = mock(VerificationCodeStore.class);
        when(codeStore.verify("register", "13800138000", "123456"))
                .thenReturn(new VerificationCheckResult(VerificationCodeStatus.TOO_MANY_ATTEMPTS, 5, 5));
        VerificationService service = new VerificationService(codeStore, mock(StringRedisTemplate.class), new VerificationProperties());

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.verifyOrThrow(VerificationScene.REGISTER, "13800138000", "123456"));

        assertEquals(ErrorCode.VERIFICATION_TOO_MANY_ATTEMPTS, ex.errorCode());
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, ex.httpStatus());
    }
}
