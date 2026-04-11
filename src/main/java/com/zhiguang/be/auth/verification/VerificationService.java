package com.zhiguang.be.auth.verification;

import com.zhiguang.be.auth.model.SendCodeResponse;
import com.zhiguang.be.common.exception.BusinessException;
import com.zhiguang.be.common.exception.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * 验证码领域服务。
 * 负责发送开发态验证码、频控、日限额和验证码校验逻辑。
 */
@Service
public class VerificationService {

    private static final Logger log = LoggerFactory.getLogger(VerificationService.class);
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final DateTimeFormatter DAY_FORMAT = DateTimeFormatter.BASIC_ISO_DATE;
    private static final DefaultRedisScript<Long> DAILY_LIMIT_SCRIPT = new DefaultRedisScript<>();

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
    private final StringRedisTemplate stringRedisTemplate;
    private final VerificationProperties properties;

    /**
     * 构造验证码领域服务。
     *
     * @param codeStore 验证码存储
     * @param stringRedisTemplate Redis 字符串模板
     * @param properties 验证码配置
     */
    public VerificationService(
            VerificationCodeStore codeStore,
            StringRedisTemplate stringRedisTemplate,
            VerificationProperties properties
    ) {
        this.codeStore = codeStore;
        this.stringRedisTemplate = stringRedisTemplate;
        this.properties = properties;
    }

    /**
     * 发送开发态验证码。
     * 该方法会执行发送间隔校验、每日发送上限校验，并把验证码写入 Redis。
     *
     * @param scene 验证码场景
     * @param phone 手机号
     * @return 发送结果
     */
    public SendCodeResponse sendCode(VerificationScene scene, String phone) {
        if (scene == null || !StringUtils.hasText(phone)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, HttpStatus.BAD_REQUEST, "验证码发送参数不完整");
        }

        enforceSendInterval(scene, phone, properties.getSendInterval());
        enforceDailyLimit(scene, phone, properties.getDailyLimit());

        String code = generateNumericCode(properties.getCodeLength());
        codeStore.saveCode(scene.value(), phone, code, properties.getCodeTtl(), properties.getMaxAttempts());
        log.info("开发态验证码已生成，scene={}, phone={}, code={}, expireSeconds={}",
                scene.value(),
                phone,
                code,
                properties.getCodeTtl().toSeconds());

        return new SendCodeResponse(phone, scene, code, (int) properties.getCodeTtl().toSeconds());
    }

    /**
     * 校验验证码，不通过时抛出对应的业务异常。
     *
     * @param scene 验证码场景
     * @param phone 手机号
     * @param code 用户输入的验证码
     */
    public void verifyOrThrow(VerificationScene scene, String phone, String code) {
        if (scene == null || !StringUtils.hasText(phone) || !StringUtils.hasText(code)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, HttpStatus.BAD_REQUEST, "验证码校验参数不完整");
        }

        VerificationCheckResult result = codeStore.verify(scene.value(), phone, code);
        if (result.isSuccess()) {
            return;
        }

        if (result.status() == VerificationCodeStatus.NOT_FOUND) {
            throw new BusinessException(ErrorCode.VERIFICATION_NOT_FOUND, HttpStatus.BAD_REQUEST, "验证码不存在，请先获取验证码");
        }
        if (result.status() == VerificationCodeStatus.EXPIRED) {
            throw new BusinessException(ErrorCode.VERIFICATION_EXPIRED, HttpStatus.BAD_REQUEST, "验证码已过期，请重新获取");
        }
        if (result.status() == VerificationCodeStatus.MISMATCH) {
            throw new BusinessException(ErrorCode.VERIFICATION_MISMATCH, HttpStatus.BAD_REQUEST, "验证码错误");
        }
        if (result.status() == VerificationCodeStatus.TOO_MANY_ATTEMPTS) {
            throw new BusinessException(ErrorCode.VERIFICATION_TOO_MANY_ATTEMPTS, HttpStatus.TOO_MANY_REQUESTS, "验证码尝试次数过多，请稍后再试");
        }
        throw new BusinessException(ErrorCode.INVALID_SMS_CODE, HttpStatus.BAD_REQUEST);
    }

    /**
     * 控制同一手机号的发送间隔。
     *
     * @param scene 验证码场景
     * @param phone 手机号
     * @param interval 发送间隔
     */
    private void enforceSendInterval(VerificationScene scene, String phone, Duration interval) {
        if (interval.isZero() || interval.isNegative()) {
            return;
        }

        String key = "auth:code:last:%s:%s".formatted(scene.value(), phone);
        String existing = stringRedisTemplate.opsForValue().get(key);
        if (existing != null) {
            throw new BusinessException(ErrorCode.RATE_LIMITED, HttpStatus.TOO_MANY_REQUESTS, "验证码发送过于频繁");
        }
        stringRedisTemplate.opsForValue().set(key, "1", interval);
    }

    /**
     * 控制同一手机号在单日内的发送总次数。
     *
     * @param scene 验证码场景
     * @param phone 手机号
     * @param limit 每日发送上限
     */
    private void enforceDailyLimit(VerificationScene scene, String phone, int limit) {
        if (limit <= 0) {
            return;
        }

        String date = DAY_FORMAT.format(LocalDate.now());
        String key = "auth:code:count:%s:%s:%s".formatted(scene.value(), phone, date);
        Long count = stringRedisTemplate.execute(
                DAILY_LIMIT_SCRIPT,
                java.util.List.of(key),
                String.valueOf(Duration.ofDays(1).toMillis())
        );
        if (count != null && count > limit) {
            throw new BusinessException(ErrorCode.RATE_LIMITED, HttpStatus.TOO_MANY_REQUESTS, "验证码发送次数已达上限");
        }
    }

    /**
     * 生成指定长度的纯数字验证码。
     *
     * @param length 验证码长度
     * @return 数字验证码
     */
    private String generateNumericCode(int length) {
        StringBuilder builder = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            builder.append(RANDOM.nextInt(10));
        }
        return builder.toString();
    }
}
