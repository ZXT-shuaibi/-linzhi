package com.zhiguang.be.auth.verification;

import com.zhiguang.be.auth.model.SendCodeResponse;
import com.zhiguang.be.auth.sms.AliyunSmsVerifyService;
import com.zhiguang.be.common.exception.BusinessException;
import com.zhiguang.be.common.exception.ErrorCode;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.HttpStatus;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 验证验证码领域服务会把底层校验状态映射成明确的业务错误码。
 */
class VerificationServiceTest {

    @Test
    void sendCodeShouldUseAliyunSmsAuthAndHideCodeWhenEnabled() {
        VerificationCodeStore codeStore = mock(VerificationCodeStore.class);
        AliyunSmsVerifyService smsVerifyService = mock(AliyunSmsVerifyService.class);
        when(smsVerifyService.isEnabled()).thenReturn(true);
        StringRedisTemplate redisTemplate = redisTemplateWithoutSendInterval();
        VerificationProperties properties = new VerificationProperties();
        properties.setSendInterval(Duration.ofSeconds(60));
        properties.setDailyLimit(0);
        VerificationService service = new VerificationService(codeStore, smsVerifyService, redisTemplate, properties);

        SendCodeResponse response = service.sendCode(VerificationScene.LOGIN, "13800138000");

        verify(codeStore, never()).saveCode(anyString(), anyString(), anyString(), eq(properties.getCodeTtl()), eq(properties.getMaxAttempts()));
        verify(smsVerifyService).send(eq("13800138000"), eq(6), eq(properties.getCodeTtl()), eq(properties.getSendInterval()));
        assertEquals(null, response.code());
        assertEquals(600, response.expireSeconds());
        assertEquals(60, response.resendAfterSeconds());
    }

    @Test
    void sendCodeShouldSaveAndExposeLocalCodeWhenAliyunSmsAuthDisabled() {
        VerificationCodeStore codeStore = mock(VerificationCodeStore.class);
        AliyunSmsVerifyService smsVerifyService = mock(AliyunSmsVerifyService.class);
        when(smsVerifyService.shouldExposeCode()).thenReturn(true);
        VerificationProperties properties = new VerificationProperties();
        properties.setSendInterval(Duration.ZERO);
        properties.setDailyLimit(0);
        VerificationService service = new VerificationService(codeStore, smsVerifyService, mock(StringRedisTemplate.class), properties);

        SendCodeResponse response = service.sendCode(VerificationScene.REGISTER, "13800138001");

        ArgumentCaptor<String> codeCaptor = ArgumentCaptor.forClass(String.class);
        verify(codeStore).saveCode(eq("register"), eq("13800138001"), codeCaptor.capture(), eq(properties.getCodeTtl()), eq(properties.getMaxAttempts()));
        assertEquals(codeCaptor.getValue(), response.code());
        assertEquals(0, response.resendAfterSeconds());
    }

    @Test
    void verifyOrThrowShouldUseAliyunSmsAuthWhenEnabled() {
        AliyunSmsVerifyService smsVerifyService = mock(AliyunSmsVerifyService.class);
        when(smsVerifyService.isEnabled()).thenReturn(true);
        when(smsVerifyService.verify("13800138002", "123456")).thenReturn(true);
        VerificationService service = new VerificationService(mock(VerificationCodeStore.class), smsVerifyService, mock(StringRedisTemplate.class), new VerificationProperties());

        service.verifyOrThrow(VerificationScene.LOGIN, "13800138002", "123456");

        verify(smsVerifyService).verify("13800138002", "123456");
    }

    /**
     * 验证验证码不存在时会返回独立的不存在错误码。
     */
    @Test
    void verifyOrThrowShouldReportNotFound() {
        VerificationCodeStore codeStore = mock(VerificationCodeStore.class);
        when(codeStore.verify("register", "13800138000", "123456"))
                .thenReturn(new VerificationCheckResult(VerificationCodeStatus.NOT_FOUND, 0, 0));
        VerificationService service = new VerificationService(codeStore, mock(AliyunSmsVerifyService.class), mock(StringRedisTemplate.class), new VerificationProperties());

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
        VerificationService service = new VerificationService(codeStore, mock(AliyunSmsVerifyService.class), mock(StringRedisTemplate.class), new VerificationProperties());

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
        VerificationService service = new VerificationService(codeStore, mock(AliyunSmsVerifyService.class), mock(StringRedisTemplate.class), new VerificationProperties());

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
        VerificationService service = new VerificationService(codeStore, mock(AliyunSmsVerifyService.class), mock(StringRedisTemplate.class), new VerificationProperties());

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.verifyOrThrow(VerificationScene.REGISTER, "13800138000", "123456"));

        assertEquals(ErrorCode.VERIFICATION_TOO_MANY_ATTEMPTS, ex.errorCode());
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, ex.httpStatus());
    }

    @SuppressWarnings("unchecked")
    private StringRedisTemplate redisTemplateWithoutSendInterval() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(anyString(), anyString(), any())).thenReturn(true);
        when(valueOperations.get(anyString())).thenReturn(null);
        return redisTemplate;
    }
}
