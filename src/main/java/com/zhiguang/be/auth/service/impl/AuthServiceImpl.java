package com.zhiguang.be.auth.service.impl;

import com.zhiguang.be.auth.audit.AuditEvent;
import com.zhiguang.be.auth.audit.AuditLogger;
import com.zhiguang.be.auth.blacklist.LoginBlacklistStore;
import com.zhiguang.be.auth.mapper.AuthUserMapper;
import com.zhiguang.be.auth.model.ActionResult;
import com.zhiguang.be.auth.model.AuthSessionData;
import com.zhiguang.be.auth.model.AuthTokens;
import com.zhiguang.be.auth.model.AuthUserEntity;
import com.zhiguang.be.auth.model.LoginRequest;
import com.zhiguang.be.auth.model.PasswordResetRequest;
import com.zhiguang.be.auth.model.RegisterRequest;
import com.zhiguang.be.auth.model.CodeScene;
import com.zhiguang.be.auth.security.CaptchaVerifier;
import com.zhiguang.be.auth.security.LoginFailureTracker;
import com.zhiguang.be.auth.service.AuthService;
import com.zhiguang.be.auth.service.VerificationCodeService;
import com.zhiguang.be.auth.token.JwtService;
import com.zhiguang.be.auth.token.RefreshTokenClaims;
import com.zhiguang.be.auth.token.RefreshTokenStore;
import com.zhiguang.be.common.exception.BusinessException;
import com.zhiguang.be.common.exception.ErrorCode;
import com.zhiguang.be.common.util.SnowflakeIdGenerator;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * 认证服务实现。
 * 负责串联用户注册、登录、令牌刷新、登出和密码重置等完整认证流程。
 */
@Service
public class AuthServiceImpl implements AuthService {

    private static final String ALL_DEVICES = "all_devices";
    private static final String CURRENT_DEVICE = "current_device";

    private final JwtService jwtService;
    private final RefreshTokenStore refreshTokenStore;
    private final AuthUserMapper authUserMapper;
    private final LoginBlacklistStore loginBlacklistStore;
    private final PasswordEncoder passwordEncoder;
    private final LoginFailureTracker failureTracker;
    private final CaptchaVerifier captchaVerifier;
    private final VerificationCodeService verificationCodeService;
    private final AuditLogger auditLogger;
    private final SnowflakeIdGenerator snowflakeIdGenerator;

    /**
     * 构造认证服务实现并注入所有流程依赖。
     *
     * @param jwtService JWT 服务
     * @param refreshTokenStore 刷新令牌存储
     * @param authUserMapper 用户数据访问组件
     * @param loginBlacklistStore 登录黑名单存储
     * @param passwordEncoder 密码编码器
     * @param failureTracker 登录失败跟踪器
     * @param captchaVerifier 验证码校验器
     * @param verificationCodeService 验证码服务
     * @param auditLogger 审计日志组件
     * @param snowflakeIdGenerator 雪花算法ID生成器
     */
    public AuthServiceImpl(
            JwtService jwtService,
            RefreshTokenStore refreshTokenStore,
            AuthUserMapper authUserMapper,
            LoginBlacklistStore loginBlacklistStore,
            PasswordEncoder passwordEncoder,
            LoginFailureTracker failureTracker,
            CaptchaVerifier captchaVerifier,
            VerificationCodeService verificationCodeService,
            AuditLogger auditLogger,
            SnowflakeIdGenerator snowflakeIdGenerator
    ) {
        this.jwtService = jwtService;
        this.refreshTokenStore = refreshTokenStore;
        this.authUserMapper = authUserMapper;
        this.loginBlacklistStore = loginBlacklistStore;
        this.passwordEncoder = passwordEncoder;
        this.failureTracker = failureTracker;
        this.captchaVerifier = captchaVerifier;
        this.verificationCodeService = verificationCodeService;
        this.auditLogger = auditLogger;
        this.snowflakeIdGenerator = snowflakeIdGenerator;
    }

    /**
     * 注册新用户并立即签发登录令牌。
     * 该流程会先校验短信验证码，再标准化手机号和用户名，最后执行原子保存，避免并发注册造成重复账号。
     *
     * @param request 注册请求
     * @return 注册后的会话信息
     */
    @Override
    public AuthSessionData register(RegisterRequest request) {
        String phone = normalizeIdentifier(request.phone());
        String username = normalizeIdentifier(request.username());

        // 校验短信验证码
        if (!verificationCodeService.verify(phone, CodeScene.REGISTER, request.smsCode())) {
            auditLogger.log(AuditEvent.of("REGISTER_FAILED", phone, false, "Invalid SMS code"));
            throw new BusinessException(ErrorCode.UNAUTHORIZED, HttpStatus.UNAUTHORIZED);
        }

        String userId = String.valueOf(snowflakeIdGenerator.nextId());
        String encodedPassword = passwordEncoder.encode(request.password());
        AuthUserEntity account = new AuthUserEntity(userId, phone, username, request.nickname(), encodedPassword);

        if (!authUserMapper.saveIfPhoneAbsent(account)) {
            auditLogger.log(AuditEvent.of("REGISTER_FAILED", phone, false, "Phone or username already exists"));
            throw new BusinessException(ErrorCode.PHONE_EXISTS, HttpStatus.CONFLICT);
        }

        AuthTokens tokens = issueAndStoreTokens(userId);
        auditLogger.log(AuditEvent.of("REGISTER_SUCCESS", phone, true, "User registered"));
        return new AuthSessionData(userId, tokens);
    }

    /**
     * 执行登录流程。
     * 支持密码登录和验证码登录两种方式。
     *
     * @param request 登录请求
     * @return 登录成功后的会话信息
     */
    @Override
    public AuthSessionData login(LoginRequest request) {
        String identifier = normalizeIdentifier(request.identifier());

        // 判断登录方式
        boolean isSmsLogin = request.smsCode() != null && !request.smsCode().isBlank();
        boolean isPasswordLogin = request.password() != null && !request.password().isBlank();

        if (!isSmsLogin && !isPasswordLogin) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, HttpStatus.BAD_REQUEST, "密码或验证码必须提供其一");
        }

        // 验证码登录
        if (isSmsLogin) {
            return loginWithSmsCode(identifier, request.smsCode());
        }

        // 密码登录
        return loginWithPassword(identifier, request);
    }

    /**
     * 验证码登录。
     */
    private AuthSessionData loginWithSmsCode(String identifier, String smsCode) {
        // 验证验证码
        if (!verificationCodeService.verify(identifier, CodeScene.LOGIN, smsCode)) {
            auditLogger.log(AuditEvent.of("LOGIN_FAILED", identifier, false, "Invalid SMS code"));
            throw new BusinessException(ErrorCode.UNAUTHORIZED, HttpStatus.UNAUTHORIZED);
        }

        // 查找用户
        AuthUserEntity account = authUserMapper.findByPhone(identifier)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_REGISTERED, HttpStatus.UNAUTHORIZED));

        // 检查黑名单
        if (loginBlacklistStore.isBlocked(account.userId())) {
            auditLogger.log(AuditEvent.of("LOGIN_BLOCKED", identifier, false, "Account blacklisted"));
            throw new BusinessException(ErrorCode.LOGIN_BLOCKED, HttpStatus.FORBIDDEN);
        }

        // 登录成功
        AuthTokens tokens = issueAndStoreTokens(account.userId());
        auditLogger.log(AuditEvent.of("LOGIN_SUCCESS", identifier, true, "User logged in with SMS"));
        return new AuthSessionData(account.userId(), tokens);
    }

    /**
     * 密码登录。
     */
    private AuthSessionData loginWithPassword(String identifier, LoginRequest request) {
        // 检查是否因失败次数过多而被临时封禁
        if (failureTracker.shouldBlock(identifier)) {
            auditLogger.log(AuditEvent.of("LOGIN_BLOCKED", identifier, false, "Too many failed attempts"));
            throw new BusinessException(ErrorCode.LOGIN_BLOCKED, HttpStatus.FORBIDDEN);
        }

        // 图形验证码校验（如果需要）
        if (failureTracker.requiresCaptcha(identifier)) {
            if (request.captchaToken() == null || request.captchaToken().isBlank()) {
                auditLogger.log(AuditEvent.of("LOGIN_FAILED", identifier, false, "Captcha required"));
                throw new BusinessException(ErrorCode.CAPTCHA_REQUIRED, HttpStatus.BAD_REQUEST);
            }
            if (!captchaVerifier.verify(request.captchaToken())) {
                failureTracker.recordFailure(identifier);
                auditLogger.log(AuditEvent.of("LOGIN_FAILED", identifier, false, "Invalid captcha"));
                throw new BusinessException(ErrorCode.INVALID_CAPTCHA, HttpStatus.BAD_REQUEST);
            }
        }

        // 查找用户
        AuthUserEntity account = authUserMapper.findByPhoneOrUsername(identifier).orElse(null);

        if (account == null) {
            passwordEncoder.matches(request.password(), "$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy");
            failureTracker.recordFailure(identifier);
            auditLogger.log(AuditEvent.of("LOGIN_FAILED", identifier, false, "User not registered"));
            throw new BusinessException(ErrorCode.USER_NOT_REGISTERED, HttpStatus.UNAUTHORIZED);
        }

        // 检查黑名单
        if (loginBlacklistStore.isBlocked(account.userId())) {
            auditLogger.log(AuditEvent.of("LOGIN_BLOCKED", identifier, false, "Account blacklisted"));
            throw new BusinessException(ErrorCode.LOGIN_BLOCKED, HttpStatus.FORBIDDEN);
        }

        // 验证密码
        if (!passwordEncoder.matches(request.password(), account.passwordHash())) {
            failureTracker.recordFailure(identifier);
            auditLogger.log(AuditEvent.of("LOGIN_FAILED", identifier, false, "Invalid password"));
            throw new BusinessException(ErrorCode.UNAUTHORIZED, HttpStatus.UNAUTHORIZED);
        }

        // 登录成功
        failureTracker.reset(identifier);
        AuthTokens tokens = issueAndStoreTokens(account.userId());
        auditLogger.log(AuditEvent.of("LOGIN_SUCCESS", identifier, true, "User logged in with password"));
        return new AuthSessionData(account.userId(), tokens);
    }

    /**
     * 使用刷新令牌换发新令牌。
     * 刷新前会检查黑名单状态，并通过白名单存储保证刷新令牌只能消费一次。
     *
     * @param refreshToken 刷新令牌字符串
     * @return 新签发的令牌对
     */
    @Override
    public AuthTokens refreshToken(String refreshToken) {
        RefreshTokenClaims claims = jwtService.verifyRefreshToken(refreshToken);

        AuthUserEntity account = authUserMapper.findByUserId(claims.userId())
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_REFRESH_TOKEN, HttpStatus.UNAUTHORIZED));

        if (loginBlacklistStore.isBlocked(account.userId())) {
            refreshTokenStore.removeAll(claims.userId());
            throw new BusinessException(ErrorCode.LOGIN_BLOCKED, HttpStatus.FORBIDDEN);
        }

        if (!refreshTokenStore.consumeIfValid(claims.userId(), claims.jti())) {
            throw new BusinessException(ErrorCode.INVALID_REFRESH_TOKEN, HttpStatus.UNAUTHORIZED);
        }

        return issueAndStoreTokens(claims.userId());
    }

    /**
     * 执行登出操作。
     * 根据登出范围选择只撤销当前刷新令牌，或撤销用户全部刷新令牌。
     *
     * @param refreshToken 当前刷新令牌
     * @param logoutScope 登出范围
     * @return 操作结果
     */
    @Override
    public ActionResult logout(String refreshToken, String logoutScope) {
        RefreshTokenClaims claims = jwtService.verifyRefreshToken(refreshToken);
        String scope = normalizeScope(logoutScope);

        if (ALL_DEVICES.equals(scope)) {
            refreshTokenStore.removeAll(claims.userId());
        } else {
            refreshTokenStore.remove(claims.userId(), claims.jti());
        }
        return new ActionResult(true, "logout", claims.userId(), "done");
    }

    /**
     * 重置用户密码并清理已有刷新令牌。
     * 该流程会先校验短信验证码，再更新密码哈希并使历史会话失效。
     *
     * @param request 重置密码请求
     * @return 操作结果
     */
    @Override
    public ActionResult resetPassword(PasswordResetRequest request) {
        String phone = normalizeIdentifier(request.phone());

        if (!verificationCodeService.verify(phone, CodeScene.RESET_PASSWORD, request.smsCode())) {
            auditLogger.log(AuditEvent.of("PASSWORD_RESET_FAILED", phone, false, "Invalid SMS code"));
            throw new BusinessException(ErrorCode.UNAUTHORIZED, HttpStatus.UNAUTHORIZED);
        }

        AuthUserEntity account = authUserMapper.findByPhone(phone)
                .orElseThrow(() -> new BusinessException(ErrorCode.BAD_REQUEST, HttpStatus.BAD_REQUEST, "Phone not registered"));

        String encodedPassword = passwordEncoder.encode(request.newPassword());
        AuthUserEntity updated = account.withPasswordHash(encodedPassword);
        authUserMapper.update(updated);

        refreshTokenStore.removeAll(account.userId());
        auditLogger.log(AuditEvent.of("PASSWORD_RESET_SUCCESS", phone, true, "Password reset completed"));
        return new ActionResult(true, "password_reset", account.userId(), "done");
    }

    /**
     * 签发并持久化刷新令牌白名单。
     *
     * @param userId 用户 ID
     * @return 双令牌结果
     */
    private AuthTokens issueAndStoreTokens(String userId) {
        AuthTokens tokens = jwtService.issueTokens(userId);
        RefreshTokenClaims claims = jwtService.verifyRefreshToken(tokens.refreshToken());
        refreshTokenStore.save(claims.userId(), claims.jti(), claims.expiresAt());
        return tokens;
    }

    /**
     * 规范化登出范围参数。
     * 非法值会回退为只退出当前设备。
     *
     * @param logoutScope 原始登出范围
     * @return 规范化后的范围值
     */
    private String normalizeScope(String logoutScope) {
        if (logoutScope == null || logoutScope.isBlank()) {
            return CURRENT_DEVICE;
        }
        if (ALL_DEVICES.equals(logoutScope) || CURRENT_DEVICE.equals(logoutScope)) {
            return logoutScope;
        }
        return CURRENT_DEVICE;
    }

    /**
     * 对登录标识做最基础的标准化处理。
     *
     * @param identifier 原始标识
     * @return 去除首尾空白后的标识，空值时返回空字符串
     */
    private String normalizeIdentifier(String identifier) {
        if (identifier == null) {
            return "";
        }
        return identifier.trim();
    }
}