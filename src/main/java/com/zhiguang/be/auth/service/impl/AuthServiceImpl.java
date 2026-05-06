package com.zhiguang.be.auth.service.impl;

import com.zhiguang.be.auth.audit.AuditEvent;
import com.zhiguang.be.auth.audit.AuditLogger;
import com.zhiguang.be.auth.audit.LoginLogService;
import com.zhiguang.be.auth.blacklist.AuthBlocklistService;
import com.zhiguang.be.auth.blacklist.LoginBlacklistStore;
import com.zhiguang.be.auth.config.AuthJwtProperties;
import com.zhiguang.be.auth.mapper.AuthUserMapper;
import com.zhiguang.be.auth.model.ActionResult;
import com.zhiguang.be.auth.model.AuthSessionData;
import com.zhiguang.be.auth.model.AuthTokens;
import com.zhiguang.be.auth.model.AuthUserEntity;
import com.zhiguang.be.auth.model.AuthUserResponse;
import com.zhiguang.be.auth.model.ClientInfo;
import com.zhiguang.be.auth.model.LoginRequest;
import com.zhiguang.be.auth.model.PasswordResetRequest;
import com.zhiguang.be.auth.model.RegisterRequest;
import com.zhiguang.be.auth.model.RegisterResult;
import com.zhiguang.be.auth.model.SendCodeRequest;
import com.zhiguang.be.auth.model.SendCodeResponse;
import com.zhiguang.be.auth.security.IdentifierValidator;
import com.zhiguang.be.auth.security.LoginFailureTracker;
import com.zhiguang.be.auth.service.AuthService;
import com.zhiguang.be.auth.token.JwtService;
import com.zhiguang.be.auth.token.RefreshTokenClaims;
import com.zhiguang.be.auth.token.RefreshTokenStore;
import com.zhiguang.be.auth.verification.VerificationScene;
import com.zhiguang.be.auth.verification.VerificationService;
import com.zhiguang.be.common.exception.BusinessException;
import com.zhiguang.be.common.exception.ErrorCode;
import com.zhiguang.be.common.id.SnowflakeIdGenerator;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 认证服务实现。
 * 负责串联验证码、注册、登录、刷新令牌、登出、重置密码和 /me 查询。
 */
@Service
public class AuthServiceImpl implements AuthService {

    private static final String ALL_DEVICES = "all_devices";
    private static final String CURRENT_DEVICE = "current_device";

    private final JwtService jwtService;
    private final RefreshTokenStore refreshTokenStore;
    private final AuthUserMapper authUserMapper;
    private final LoginBlacklistStore loginBlacklistStore;
    private final AuthBlocklistService blocklistService;
    private final PasswordEncoder passwordEncoder;
    private final LoginFailureTracker failureTracker;
    private final VerificationService verificationService;
    private final AuditLogger auditLogger;
    private final LoginLogService loginLogService;
    private final AuthJwtProperties authJwtProperties;
    private final SnowflakeIdGenerator snowflakeIdGenerator;

    /**
     * 构造认证服务实现。
     *
     * @param jwtService JWT 服务
     * @param refreshTokenStore 刷新令牌存储
     * @param authUserMapper 认证用户持久层
     * @param loginBlacklistStore 登录黑名单存储
     * @param blocklistService 认证黑名单写入服务
     * @param passwordEncoder 密码编码器
     * @param failureTracker 登录失败跟踪器
     * @param verificationService 验证码服务
     * @param auditLogger 审计日志组件
     * @param loginLogService 登录日志服务
     * @param authJwtProperties JWT 配置
     * @param snowflakeIdGenerator 雪花算法 ID 生成器
     */
    public AuthServiceImpl(
            JwtService jwtService,
            RefreshTokenStore refreshTokenStore,
            AuthUserMapper authUserMapper,
            LoginBlacklistStore loginBlacklistStore,
            AuthBlocklistService blocklistService,
            PasswordEncoder passwordEncoder,
            LoginFailureTracker failureTracker,
            VerificationService verificationService,
            AuditLogger auditLogger,
            LoginLogService loginLogService,
            AuthJwtProperties authJwtProperties,
            SnowflakeIdGenerator snowflakeIdGenerator
    ) {
        this.jwtService = jwtService;
        this.refreshTokenStore = refreshTokenStore;
        this.authUserMapper = authUserMapper;
        this.loginBlacklistStore = loginBlacklistStore;
        this.blocklistService = blocklistService;
        this.passwordEncoder = passwordEncoder;
        this.failureTracker = failureTracker;
        this.verificationService = verificationService;
        this.auditLogger = auditLogger;
        this.loginLogService = loginLogService;
        this.authJwtProperties = authJwtProperties;
        this.snowflakeIdGenerator = snowflakeIdGenerator;
    }

    /**
     * 发送开发态验证码。
     * 注册场景要求手机号未注册，登录和重置密码场景要求目标账号已存在。
     *
     * @param request 发送验证码请求
     * @return 发送结果
     */
    @Override
    public SendCodeResponse sendCode(SendCodeRequest request) {
        String identifier = normalizeIdentifier(request.identifier());
        VerificationScene scene = request.scene();
        validateSendCodeIdentifier(scene, identifier);
        String phone = resolveSendCodePhone(scene, identifier);

        SendCodeResponse response = verificationService.sendCode(scene, phone);
        auditLogger.log(AuditEvent.of("SEND_CODE_SUCCESS", identifier, true, "验证码发送成功，scene=" + scene.value()));
        return response;
    }

    /**
     * 注册新用户。
     * 仅完成验证码校验、账号创建和密码加密落库，不自动登录。
     *
     * @param request 注册请求
     * @param clientInfo 客户端信息
     * @return 注册结果
     */
    @Override
    public RegisterResult register(RegisterRequest request, ClientInfo clientInfo) {
        String phone = normalizeIdentifier(request.phone());
        String nickname = normalizeIdentifier(request.nickname());
        validateRegisterInput(phone, nickname, request.password(), request.confirmPassword());
        verificationService.verifyOrThrow(VerificationScene.REGISTER, phone, request.smsCode());

        ensurePhoneAvailable(phone);

        String userId = String.valueOf(snowflakeIdGenerator.nextId());
        String encodedPassword = passwordEncoder.encode(request.password());
        AuthUserEntity account = new AuthUserEntity(userId, phone, request.nickname(), encodedPassword);

        if (!authUserMapper.saveIfAbsent(account)) {
            throw new BusinessException(ErrorCode.PHONE_EXISTS, HttpStatus.CONFLICT);
        }

        auditLogger.log(AuditEvent.of("REGISTER_SUCCESS", phone, true, "用户注册成功，等待登录"));
        loginLogService.record(userId, phone, "REGISTER", safeIp(clientInfo), safeUserAgent(clientInfo), "SUCCESS", "注册成功");
        return new RegisterResult(userId, phone, "login", "registered");
    }

    /**
     * 执行登录流程。
     * 依次处理风控、黑名单、验证码挑战、密码校验和双令牌签发。
     *
     * @param request 登录请求
     * @param clientInfo 客户端信息
     * @return 登录成功后的会话信息
     */
    @Override
    public AuthSessionData login(LoginRequest request, ClientInfo clientInfo) {
        String identifier = normalizeIdentifier(request.identifier());
        boolean passwordLogin = StringUtils.hasText(request.password());
        boolean codeLogin = StringUtils.hasText(request.captchaCode());
        String channel = resolveLoginLogChannel(request);
        validateLoginIdentifier(identifier);
        if (!passwordLogin && !codeLogin) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, HttpStatus.BAD_REQUEST, "请提供密码或验证码");
        }

        try {
            ensureIdentifierNotBlocked(identifier);
        } catch (BusinessException ex) {
            if (ex.errorCode() == ErrorCode.LOGIN_BLOCKED) {
                loginLogService.record(null, identifier, channel, safeIp(clientInfo), safeUserAgent(clientInfo), "BLOCKED", ex.getMessage());
            }
            throw ex;
        }

        AuthUserEntity account = authUserMapper.findByIdentifier(identifier).orElse(null);
        if (account == null) {
            auditLogger.log(AuditEvent.of("LOGIN_FAILED", identifier, false, "账号未注册"));
            loginLogService.record(null, identifier, channel, safeIp(clientInfo), safeUserAgent(clientInfo), "FAILED", "账号未注册");
            throw new BusinessException(ErrorCode.ACCOUNT_NOT_FOUND, HttpStatus.NOT_FOUND);
        }

        try {
            ensureAccountNotBlocked(account, identifier);
            if (passwordLogin) {
                validateCaptchaWhenRequired(identifier, account, request.captchaCode());
            }
        } catch (BusinessException ex) {
            if (ex.errorCode() == ErrorCode.LOGIN_BLOCKED) {
                loginLogService.record(account.userId(), identifier, channel, safeIp(clientInfo), safeUserAgent(clientInfo), "BLOCKED", ex.getMessage());
            } else if (ex.errorCode() == ErrorCode.CAPTCHA_REQUIRED || ex.errorCode() == ErrorCode.INVALID_CAPTCHA) {
                loginLogService.record(account.userId(), identifier, channel, safeIp(clientInfo), safeUserAgent(clientInfo), "FAILED", ex.getMessage());
            }
            throw ex;
        }

        if (passwordLogin && !passwordEncoder.matches(request.password(), account.passwordHash())) {
            recordFailure(identifier, account);
            auditLogger.log(AuditEvent.of("LOGIN_FAILED", identifier, false, "密码错误"));
            loginLogService.record(account.userId(), identifier, channel, safeIp(clientInfo), safeUserAgent(clientInfo), "FAILED", "密码错误");
            throw new BusinessException(ErrorCode.UNAUTHORIZED, HttpStatus.UNAUTHORIZED, "密码错误");
        }
        if (!passwordLogin) {
            verifyLoginCodeCredential(identifier, account, request.captchaCode(), clientInfo);
        }

        resetFailures(identifier, account);
        AuthTokens tokens = issueAndStoreTokens(account.userId());
        auditLogger.log(AuditEvent.of("LOGIN_SUCCESS", identifier, true, "用户登录成功"));
        loginLogService.record(account.userId(), identifier, channel, safeIp(clientInfo), safeUserAgent(clientInfo), "SUCCESS", "登录成功");
        return new AuthSessionData(account.userId(), tokens);
    }

    /**
     * 使用刷新令牌换发新令牌。
     * 删除旧 refresh 和写入新 refresh 由存储层原子完成。
     *
     * @param refreshToken 刷新令牌
     * @return 新的令牌对
     */
    @Override
    public AuthTokens refreshToken(String refreshToken) {
        RefreshTokenClaims claims = jwtService.verifyRefreshToken(refreshToken);
        AuthUserEntity account = authUserMapper.findByUserId(claims.userId())
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_REFRESH_TOKEN, HttpStatus.UNAUTHORIZED));

        if (isRefreshBlocked(account)) {
            revokeAllSessionsAndBlockAccessTokens(claims.userId());
            auditLogger.log(AuditEvent.of("REFRESH_FAILED", account.phone(), false, "命中登录黑名单"));
            throw new BusinessException(ErrorCode.LOGIN_BLOCKED, HttpStatus.FORBIDDEN);
        }

        AuthTokens tokens = jwtService.issueTokens(claims.userId());
        RefreshTokenClaims newClaims = jwtService.verifyRefreshToken(tokens.refreshToken());
        if (!refreshTokenStore.rotate(claims.userId(), claims.jti(), newClaims.jti(), newClaims.expiresAt())) {
            auditLogger.log(AuditEvent.of("REFRESH_FAILED", account.phone(), false, "刷新令牌已失效"));
            throw new BusinessException(ErrorCode.INVALID_REFRESH_TOKEN, HttpStatus.UNAUTHORIZED);
        }

        auditLogger.log(AuditEvent.of("REFRESH_SUCCESS", account.phone(), true, "刷新令牌成功"));
        return tokens;
    }

    /**
     * 执行登出。
     * 会撤销 refresh token，并记录一个按时间点生效的 access token 失效标记。
     *
     * @param refreshToken 当前 refresh token
     * @param logoutScope 登出范围
     * @return 操作结果
     */
    @Override
    public ActionResult logout(String refreshToken, String logoutScope) {
        RefreshTokenClaims claims = jwtService.verifyRefreshToken(refreshToken);
        String scope = normalizeScope(logoutScope);

        if (ALL_DEVICES.equals(scope)) {
            revokeAllSessionsAndBlockAccessTokens(claims.userId());
        } else {
            refreshTokenStore.remove(claims.userId(), claims.jti());
            blockAccessTokens(claims.userId());
        }

        auditLogger.log(AuditEvent.of("LOGOUT_SUCCESS", claims.userId(), true, "用户登出成功"));
        return new ActionResult(true, "logout", claims.userId(), "done");
    }

    /**
     * 重置密码。
     * 校验验证码后更新密码哈希，并使旧会话和旧 access token 立即失效。
     *
     * @param request 重置密码请求
     * @return 操作结果
     */
    @Override
    public ActionResult resetPassword(PasswordResetRequest request) {
        String phone = normalizeIdentifier(request.phone());
        validatePhone(phone, "重置密码");
        verificationService.verifyOrThrow(VerificationScene.PASSWORD_RESET, phone, request.smsCode());

        AuthUserEntity account = authUserMapper.findByPhone(phone)
                .orElseThrow(() -> new BusinessException(ErrorCode.BAD_REQUEST, HttpStatus.BAD_REQUEST, "手机号未注册"));

        String encodedPassword = passwordEncoder.encode(request.newPassword());
        authUserMapper.update(account.withPasswordHash(encodedPassword));
        resetFailures(phone, account);
        revokeAllSessionsAndBlockAccessTokens(account.userId());

        auditLogger.log(AuditEvent.of("PASSWORD_RESET_SUCCESS", phone, true, "密码重置成功"));
        return new ActionResult(true, "password_reset", account.userId(), "done");
    }

    /**
     * 查询当前用户信息。
     *
     * @param userId 当前登录用户 ID
     * @return 认证域可见的最小用户信息
     */
    @Override
    public AuthUserResponse me(String userId) {
        AuthUserEntity account = authUserMapper.findByUserId(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, HttpStatus.NOT_FOUND, "用户不存在"));
        return new AuthUserResponse(account.userId(), account.phone(), account.nickname());
    }

    /**
     * 解析 send-code 的目标手机号。
     *
     * @param scene 验证码场景
     * @param identifier 发送目标
     * @return 实际保存验证码的手机号
     */
    private String resolveSendCodePhone(VerificationScene scene, String identifier) {
        if (scene == VerificationScene.REGISTER) {
            if (authUserMapper.existsByPhone(identifier)) {
                auditLogger.log(AuditEvent.of("SEND_CODE_FAILED", identifier, false, "注册场景手机号已存在"));
                throw new BusinessException(ErrorCode.PHONE_EXISTS, HttpStatus.CONFLICT);
            }
            return identifier;
        }

        AuthUserEntity account = authUserMapper.findByIdentifier(identifier).orElse(null);
        if (account == null) {
            auditLogger.log(AuditEvent.of("SEND_CODE_FAILED", identifier, false, "发送目标未注册"));
            throw new BusinessException(ErrorCode.ACCOUNT_NOT_FOUND, HttpStatus.NOT_FOUND);
        }
        return account.phone();
    }

    /**
     * 校验 send-code 入口标识格式。
     *
     * @param scene 验证码场景
     * @param identifier 发送目标
     */
    private void validateSendCodeIdentifier(VerificationScene scene, String identifier) {
        if (scene == VerificationScene.REGISTER) {
            if (!IdentifierValidator.isValidPhone(identifier)) {
                auditLogger.log(AuditEvent.of("SEND_CODE_FAILED", identifier, false, "注册场景手机号格式不正确"));
                throw new BusinessException(ErrorCode.BAD_REQUEST, HttpStatus.BAD_REQUEST, "注册场景必须传合法手机号");
            }
            return;
        }

        if (!IdentifierValidator.isValidPhoneOrAccount(identifier)) {
            auditLogger.log(AuditEvent.of("SEND_CODE_FAILED", identifier, false, "发送目标格式不正确"));
            throw new BusinessException(ErrorCode.BAD_REQUEST, HttpStatus.BAD_REQUEST, "发送目标必须为手机号或账号");
        }
    }

    /**
     * 校验注册输入格式。
     *
     * @param phone 注册手机号
     * @param account 注册账号
     */
    private void validateRegisterInput(String phone, String nickname, String password, String confirmPassword) {
        validatePhone(phone, "注册");
        if (!StringUtils.hasText(nickname)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, HttpStatus.BAD_REQUEST, "昵称不能为空");
        }
        if (!StringUtils.hasText(password) || !StringUtils.hasText(confirmPassword) || !password.equals(confirmPassword)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, HttpStatus.BAD_REQUEST, "两次输入的密码不一致");
        }
    }

    /**
     * 校验登录标识格式。
     *
     * @param identifier 登录标识
     */
    private void validateLoginIdentifier(String identifier) {
        if (!IdentifierValidator.isValidPhoneOrAccount(identifier)) {
            auditLogger.log(AuditEvent.of("LOGIN_FAILED", identifier, false, "登录标识格式不正确"));
            throw new BusinessException(ErrorCode.BAD_REQUEST, HttpStatus.BAD_REQUEST, "登录标识必须为手机号或账号");
        }
    }

    /**
     * 校验手机号格式。
     *
     * @param phone 手机号
     * @param scene 场景说明
     */
    private void validatePhone(String phone, String scene) {
        if (!IdentifierValidator.isValidPhone(phone)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, HttpStatus.BAD_REQUEST, scene + "手机号格式不正确");
        }
    }

    /**
     * 校验账号格式。
     *
     * @param account 账号
     * @param scene 场景说明
     */
    /**
     * 检查手机号是否可用。
     *
     * @param phone 手机号
     * @param account 账号
     */
    private void ensurePhoneAvailable(String phone) {
        if (authUserMapper.existsByPhone(phone)) {
            auditLogger.log(AuditEvent.of("REGISTER_FAILED", phone, false, "手机号已注册"));
            throw new BusinessException(ErrorCode.PHONE_EXISTS, HttpStatus.CONFLICT);
        }
    }

    /**
     * 在查出账号前，先按原始标识检查失败次数和黑名单。
     *
     * @param identifier 登录标识
     */
    private void ensureIdentifierNotBlocked(String identifier) {
        if (failureTracker.shouldBlock(identifier)) {
            auditLogger.log(AuditEvent.of("LOGIN_BLOCKED", identifier, false, "登录失败次数过多"));
            throw new BusinessException(ErrorCode.LOGIN_BLOCKED, HttpStatus.FORBIDDEN);
        }

        if (loginBlacklistStore.isBlocked(identifier)) {
            auditLogger.log(AuditEvent.of("LOGIN_BLOCKED", identifier, false, "命中登录黑名单"));
            throw new BusinessException(ErrorCode.LOGIN_BLOCKED, HttpStatus.FORBIDDEN);
        }
    }

    /**
     * 在查出账号后，检查用户 ID、手机号和账号名是否命中黑名单或失败阈值。
     *
     * @param account 用户实体
     * @param identifier 原始登录标识
     */
    private void ensureAccountNotBlocked(AuthUserEntity account, String identifier) {
        ensureUserIdNotBlocked(account.userId());

        Set<String> identifiers = relatedIdentifiers(identifier, account);
        identifiers.remove(identifier);
        for (String relatedIdentifier : identifiers) {
            if (failureTracker.shouldBlock(relatedIdentifier)) {
                auditLogger.log(AuditEvent.of("LOGIN_BLOCKED", relatedIdentifier, false, "登录失败次数过多"));
                throw new BusinessException(ErrorCode.LOGIN_BLOCKED, HttpStatus.FORBIDDEN);
            }
            if (loginBlacklistStore.isBlocked(relatedIdentifier)) {
                auditLogger.log(AuditEvent.of("LOGIN_BLOCKED", relatedIdentifier, false, "命中登录黑名单"));
                throw new BusinessException(ErrorCode.LOGIN_BLOCKED, HttpStatus.FORBIDDEN);
            }
        }
    }

    /**
     * 检查用户 ID 是否命中登录黑名单。
     *
     * @param userId 用户 ID
     */
    private void ensureUserIdNotBlocked(String userId) {
        if (loginBlacklistStore.isBlocked(userId)) {
            auditLogger.log(AuditEvent.of("LOGIN_BLOCKED", userId, false, "命中用户黑名单"));
            throw new BusinessException(ErrorCode.LOGIN_BLOCKED, HttpStatus.FORBIDDEN);
        }
    }

    /**
     * 校验验证码登录凭证。
     *
     * @param identifier 原始登录标识
     * @param account 用户实体
     * @param captchaCode 登录验证码
     * @param clientInfo 客户端信息
     */
    private void verifyLoginCodeCredential(String identifier, AuthUserEntity account, String captchaCode, ClientInfo clientInfo) {
        try {
            verificationService.verifyOrThrow(VerificationScene.LOGIN, account.phone(), captchaCode);
        } catch (BusinessException ex) {
            recordFailure(identifier, account);
            auditLogger.log(AuditEvent.of("LOGIN_FAILED", identifier, false, "验证码登录校验失败"));
            loginLogService.record(account.userId(), identifier, "CODE", safeIp(clientInfo), safeUserAgent(clientInfo), "FAILED", ex.getMessage());
            throw mapCaptchaVerificationException(ex);
        }
    }

    /**
     * 命中风控阈值后，要求并校验登录验证码。
     *
     * @param identifier 原始登录标识
     * @param account 用户实体
     * @param captchaCode 登录验证码
     */
    private void validateCaptchaWhenRequired(String identifier, AuthUserEntity account, String captchaCode) {
        boolean captchaRequired = relatedIdentifiers(identifier, account)
                .stream()
                .anyMatch(failureTracker::requiresCaptcha);
        if (!captchaRequired) {
            return;
        }

        if (!StringUtils.hasText(captchaCode)) {
            auditLogger.log(AuditEvent.of("LOGIN_FAILED", identifier, false, "缺少登录验证码"));
            throw new BusinessException(ErrorCode.CAPTCHA_REQUIRED, HttpStatus.BAD_REQUEST);
        }

        try {
            verificationService.verifyOrThrow(VerificationScene.LOGIN, account.phone(), captchaCode);
        } catch (BusinessException ex) {
            if (ex.errorCode() == ErrorCode.INVALID_SMS_CODE
                    || ex.errorCode() == ErrorCode.VERIFICATION_MISMATCH
                    || ex.errorCode() == ErrorCode.VERIFICATION_NOT_FOUND
                    || ex.errorCode() == ErrorCode.VERIFICATION_EXPIRED
                    || ex.errorCode() == ErrorCode.VERIFICATION_TOO_MANY_ATTEMPTS) {
                recordFailure(identifier, account);
                auditLogger.log(AuditEvent.of("LOGIN_FAILED", identifier, false, "登录验证码校验失败"));
                throw mapCaptchaVerificationException(ex);
            }
            throw ex;
        }
    }

    /**
     * 把验证码异常映射成登录接口语义。
     *
     * @param ex 验证码异常
     * @return 映射后的登录异常
     */
    private BusinessException mapCaptchaVerificationException(BusinessException ex) {
        if (ex.errorCode() == ErrorCode.VERIFICATION_MISMATCH) {
            return new BusinessException(ErrorCode.INVALID_CAPTCHA, HttpStatus.BAD_REQUEST, "登录验证码错误");
        }
        if (ex.errorCode() == ErrorCode.VERIFICATION_EXPIRED) {
            return new BusinessException(ErrorCode.INVALID_CAPTCHA, HttpStatus.BAD_REQUEST, "登录验证码已过期");
        }
        if (ex.errorCode() == ErrorCode.VERIFICATION_NOT_FOUND) {
            return new BusinessException(ErrorCode.INVALID_CAPTCHA, HttpStatus.BAD_REQUEST, "登录验证码不存在或已失效");
        }
        if (ex.errorCode() == ErrorCode.VERIFICATION_TOO_MANY_ATTEMPTS) {
            return new BusinessException(ErrorCode.VERIFICATION_TOO_MANY_ATTEMPTS, HttpStatus.TOO_MANY_REQUESTS, ex.getMessage());
        }
        return new BusinessException(ErrorCode.INVALID_CAPTCHA, HttpStatus.BAD_REQUEST, "登录验证码错误或已过期");
    }

    /**
     * 同步累计同一账号相关标识的失败次数。
     *
     * @param identifier 原始登录标识
     * @param account 用户实体
     */
    private void recordFailure(String identifier, AuthUserEntity account) {
        for (String relatedIdentifier : relatedIdentifiers(identifier, account)) {
            failureTracker.recordFailure(relatedIdentifier);
        }
    }

    /**
     * 登录成功或重置密码后，清理账号相关标识上的失败次数。
     *
     * @param identifier 原始登录标识
     * @param account 用户实体
     */
    private void resetFailures(String identifier, AuthUserEntity account) {
        for (String relatedIdentifier : relatedIdentifiers(identifier, account)) {
            failureTracker.reset(relatedIdentifier);
        }
    }

    /**
     * 汇总同一账号相关的登录标识。
     *
     * @param identifier 原始登录标识
     * @param account 用户实体
     * @return 关联标识集合
     */
    private Set<String> relatedIdentifiers(String identifier, AuthUserEntity account) {
        LinkedHashSet<String> identifiers = new LinkedHashSet<>();
        if (StringUtils.hasText(identifier)) {
            identifiers.add(identifier);
        }
        if (account != null && StringUtils.hasText(account.phone())) {
            identifiers.add(account.phone());
        }
        return identifiers;
    }

    /**
     * 判断任意标识是否命中登录黑名单。
     *
     * @param identifiers 标识集合
     * @return 命中返回 true，否则返回 false
     */
    private boolean isAnyBlocked(Set<String> identifiers) {
        for (String identifier : identifiers) {
            if (loginBlacklistStore.isBlocked(identifier)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 判断 refresh 流程是否命中黑名单。
     *
     * @param account 用户实体
     * @return 命中返回 true，否则返回 false
     */
    private boolean isRefreshBlocked(AuthUserEntity account) {
        return loginBlacklistStore.isBlocked(account.userId()) || isAnyBlocked(relatedIdentifiers(null, account));
    }

    /**
     * 签发并落库 refresh token 白名单。
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
     * 规范化登出范围。
     *
     * @param logoutScope 原始范围
     * @return 规范化后的范围值
     */
    private String normalizeScope(String logoutScope) {
        if (!StringUtils.hasText(logoutScope)) {
            return CURRENT_DEVICE;
        }
        if (ALL_DEVICES.equals(logoutScope) || CURRENT_DEVICE.equals(logoutScope)) {
            return logoutScope;
        }
        return CURRENT_DEVICE;
    }

    /**
     * 规范化标识文本。
     *
     * @param identifier 原始标识
     * @return 去除前后空白后的结果
     */
    private String normalizeIdentifier(String identifier) {
        return identifier == null ? "" : identifier.trim();
    }

    /**
     * 解析登录日志中的认证方式。
     * 参考 zhiguang，记录 PASSWORD/CODE，而不是前端平台来源。
     *
     * @param request 登录请求
     * @return 认证方式
     */
    private String resolveLoginLogChannel(LoginRequest request) {
        if (StringUtils.hasText(request.password())) {
            return "PASSWORD";
        }
        if (StringUtils.hasText(request.captchaCode())) {
            return "CODE";
        }
        return "UNKNOWN";
    }

    /**
     * 计算 access token 失效黑名单的保留时长。
     *
     * @return 黑名单 TTL
     */
    private Duration accessTokenBlocklistTtl() {
        long minutes = Math.max(authJwtProperties.getAccessTokenTtlMinutes(), 1L);
        return Duration.ofMinutes(minutes);
    }

    /**
     * 记录 access token 失效时间点。
     * 这样旧 token 会立刻失效，但之后重新登录签发的新 token 不会被连带阻塞。
     *
     * @param userId 用户 ID
     */
    private void blockAccessTokens(String userId) {
        blocklistService.blockAccessTokens(userId, accessTokenBlocklistTtl());
    }

    /**
     * 撤销全部 refresh token，并同步记录 access token 失效时间点。
     *
     * @param userId 用户 ID
     */
    private void revokeAllSessionsAndBlockAccessTokens(String userId) {
        blocklistService.revokeAllSessionsAndBlockAccessTokens(userId, accessTokenBlocklistTtl());
    }

    /**
     * 安全读取客户端 IP。
     *
     * @param clientInfo 客户端信息
     * @return 客户端 IP
     */
    private String safeIp(ClientInfo clientInfo) {
        return clientInfo == null ? null : clientInfo.ip();
    }

    /**
     * 安全读取客户端 User-Agent。
     *
     * @param clientInfo 客户端信息
     * @return 客户端 User-Agent
     */
    private String safeUserAgent(ClientInfo clientInfo) {
        return clientInfo == null ? null : clientInfo.userAgent();
    }
}
