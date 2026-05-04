package com.zhiguang.be.auth.verification;

import com.zhiguang.be.auth.model.SendCodeResponse;
import com.zhiguang.be.auth.sms.AliyunSmsVerifyService;
import com.zhiguang.be.common.exception.BusinessException;
import com.zhiguang.be.common.exception.ErrorCode;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Service
public class VerificationService {

    private static final DateTimeFormatter DAY_FORMAT = DateTimeFormatter.BASIC_ISO_DATE;
    private static final DefaultRedisScript<Long> DAILY_LIMIT_SCRIPT = new DefaultRedisScript<>();
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    static {
        DAILY_LIMIT_SCRIPT.setResultType(Long.class);
        DAILY_LIMIT_SCRIPT.setScriptText(
                "local current = redis.call('INCR', KEYS[1]); " +
                        "if current == 1 then " +
                        "redis.call('PEXPIRE', KEYS[1], ARGV[1]); " +
                        "end; " +
                        "return current"
        );
    }

    private final VerificationCodeStore codeStore;
    private final AliyunSmsVerifyService smsVerifyService;
    private final StringRedisTemplate stringRedisTemplate;
    private final VerificationProperties properties;

    public VerificationService(
            VerificationCodeStore codeStore,
            AliyunSmsVerifyService smsVerifyService,
            StringRedisTemplate stringRedisTemplate,
            VerificationProperties properties
    ) {
        this.codeStore = codeStore;
        this.smsVerifyService = smsVerifyService;
        this.stringRedisTemplate = stringRedisTemplate;
        this.properties = properties;
    }

    public SendCodeResponse sendCode(VerificationScene scene, String phone) {
        if (scene == null || !StringUtils.hasText(phone)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, HttpStatus.BAD_REQUEST, "验证码发送参数不完整");
        }

        enforceSendInterval(scene, phone, properties.getSendInterval());
        enforceDailyLimit(scene, phone, properties.getDailyLimit());

        if (smsVerifyService.isEnabled()) {
            String exposedCode = smsVerifyService.send(
                    phone,
                    properties.getCodeLength(),
                    properties.getCodeTtl(),
                    properties.getSendInterval()
            );
            markSendInterval(scene, phone, properties.getSendInterval());
            incrementDailyCount(scene, phone, properties.getDailyLimit());

            return new SendCodeResponse(
                    phone,
                    scene,
                    smsVerifyService.shouldExposeCode() ? exposedCode : null,
                    toResponseSeconds(properties.getCodeTtl()),
                    toResponseSeconds(properties.getSendInterval())
            );
        }

        String code = generateCode(properties.getCodeLength());
        codeStore.saveCode(scene.value(), phone, code, properties.getCodeTtl(), properties.getMaxAttempts());
        markSendInterval(scene, phone, properties.getSendInterval());
        incrementDailyCount(scene, phone, properties.getDailyLimit());

        return new SendCodeResponse(
                phone,
                scene,
                smsVerifyService.shouldExposeCode() ? code : null,
                toResponseSeconds(properties.getCodeTtl()),
                toResponseSeconds(properties.getSendInterval())
        );
    }

    public void verifyOrThrow(VerificationScene scene, String phone, String code) {
        if (scene == null || !StringUtils.hasText(phone) || !StringUtils.hasText(code)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, HttpStatus.BAD_REQUEST, "验证码校验参数不完整");
        }

        if (smsVerifyService.isEnabled()) {
            if (smsVerifyService.verify(phone, code)) {
                return;
            }
            throw new BusinessException(ErrorCode.VERIFICATION_MISMATCH, HttpStatus.BAD_REQUEST, "验证码错误");
        }

        VerificationCheckResult result = codeStore.verify(scene.value(), phone, code);
        if (result.isSuccess()) {
            return;
        }

        if (result.status() == VerificationCodeStatus.NOT_FOUND) {
            throw new BusinessException(ErrorCode.VERIFICATION_NOT_FOUND, HttpStatus.BAD_REQUEST, "验证码不存在或已失效");
        }
        if (result.status() == VerificationCodeStatus.EXPIRED) {
            throw new BusinessException(ErrorCode.VERIFICATION_EXPIRED, HttpStatus.BAD_REQUEST, "验证码已过期");
        }
        if (result.status() == VerificationCodeStatus.TOO_MANY_ATTEMPTS) {
            throw new BusinessException(ErrorCode.VERIFICATION_TOO_MANY_ATTEMPTS, HttpStatus.TOO_MANY_REQUESTS, "验证码尝试次数过多");
        }
        throw new BusinessException(ErrorCode.VERIFICATION_MISMATCH, HttpStatus.BAD_REQUEST, "验证码错误");
    }

    private void enforceSendInterval(VerificationScene scene, String phone, Duration interval) {
        if (interval == null || interval.isZero() || interval.isNegative()) {
            return;
        }
        String key = sendIntervalKey(scene, phone);
        String existing = stringRedisTemplate.opsForValue().get(key);
        if (existing != null) {
            throw new BusinessException(ErrorCode.RATE_LIMITED, HttpStatus.TOO_MANY_REQUESTS, "验证码发送过于频繁");
        }
    }

    private void markSendInterval(VerificationScene scene, String phone, Duration interval) {
        if (interval == null || interval.isZero() || interval.isNegative()) {
            return;
        }
        stringRedisTemplate.opsForValue().set(sendIntervalKey(scene, phone), "1", interval);
    }

    private void enforceDailyLimit(VerificationScene scene, String phone, int limit) {
        if (limit <= 0) {
            return;
        }
        String existing = stringRedisTemplate.opsForValue().get(dailyLimitKey(scene, phone));
        if (!StringUtils.hasText(existing)) {
            return;
        }
        try {
            if (Long.parseLong(existing) >= limit) {
                throw new BusinessException(ErrorCode.RATE_LIMITED, HttpStatus.TOO_MANY_REQUESTS, "验证码发送次数已达上限");
            }
        } catch (NumberFormatException ex) {
            throw new BusinessException(ErrorCode.RATE_LIMITED, HttpStatus.TOO_MANY_REQUESTS, "验证码发送次数已达上限");
        }
    }

    private void incrementDailyCount(VerificationScene scene, String phone, int limit) {
        if (limit <= 0) {
            return;
        }
        stringRedisTemplate.execute(
                DAILY_LIMIT_SCRIPT,
                List.of(dailyLimitKey(scene, phone)),
                String.valueOf(Duration.ofDays(1).toMillis())
        );
    }

    private String sendIntervalKey(VerificationScene scene, String phone) {
        return "auth:code:last:%s:%s".formatted(scene.value(), phone);
    }

    private String dailyLimitKey(VerificationScene scene, String phone) {
        String date = DAY_FORMAT.format(LocalDate.now());
        return "auth:code:count:%s:%s:%s".formatted(scene.value(), phone, date);
    }

    private String generateCode(int length) {
        int safeLength = Math.min(Math.max(length, 4), 8);
        StringBuilder builder = new StringBuilder(safeLength);
        for (int i = 0; i < safeLength; i++) {
            builder.append(SECURE_RANDOM.nextInt(10));
        }
        return builder.toString();
    }

    private int toResponseSeconds(Duration duration) {
        if (duration == null || duration.isNegative()) {
            return 0;
        }
        long seconds = duration.toSeconds();
        return seconds > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) seconds;
    }
}
