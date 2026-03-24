package com.zhiguang.be.auth.service.impl;

import com.zhiguang.be.auth.blacklist.LoginBlacklistStore;
import com.zhiguang.be.auth.mapper.AuthUserMapper;
import com.zhiguang.be.auth.model.ActionResult;
import com.zhiguang.be.auth.model.AuthSessionData;
import com.zhiguang.be.auth.model.AuthTokens;
import com.zhiguang.be.auth.model.AuthUserEntity;
import com.zhiguang.be.auth.model.LoginRequest;
import com.zhiguang.be.auth.model.PasswordResetRequest;
import com.zhiguang.be.auth.model.RegisterRequest;
import com.zhiguang.be.auth.risk.LoginRateLimitStore;
import com.zhiguang.be.auth.service.AuthService;
import com.zhiguang.be.auth.token.JwtService;
import com.zhiguang.be.auth.token.RefreshTokenClaims;
import com.zhiguang.be.auth.token.RefreshTokenStore;
import com.zhiguang.be.common.exception.BusinessException;
import com.zhiguang.be.common.exception.ErrorCode;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.UUID;

/**
 * 认证服务实现。
 */
@Service
public class AuthServiceImpl implements AuthService {

    private static final String ALL_DEVICES = "all_devices";
    private static final String CURRENT_DEVICE = "current_device";
    private static final int MAX_LOGIN_FAILURES = 5;
    private static final Duration LOGIN_MIN_INTERVAL = Duration.ofSeconds(20);
    private static final Duration LOGIN_FAILURE_TTL = Duration.ofMinutes(15);
    private static final Duration LOGIN_BLOCK_TTL = Duration.ofHours(24);

    private final JwtService jwtService;
    private final RefreshTokenStore refreshTokenStore;
    private final AuthUserMapper authUserMapper;
    private final LoginBlacklistStore loginBlacklistStore;
    private final LoginRateLimitStore loginRateLimitStore;
    private final PasswordEncoder passwordEncoder;

    /**
     * 构造函数：注入认证流程依赖。
     *
     * @param jwtService JWT 服务
     * @param refreshTokenStore refresh 白名单存储
     * @param authUserMapper 用户数据访问
     * @param loginBlacklistStore 登录黑名单存储
     * @param loginRateLimitStore 登录限流存储
     * @param passwordEncoder 密码加密器
     */
    public AuthServiceImpl(
            JwtService jwtService,
            RefreshTokenStore refreshTokenStore,
            AuthUserMapper authUserMapper,
            LoginBlacklistStore loginBlacklistStore,
            LoginRateLimitStore loginRateLimitStore,
            PasswordEncoder passwordEncoder
    ) {
        this.jwtService = jwtService;
        this.refreshTokenStore = refreshTokenStore;
        this.authUserMapper = authUserMapper;
        this.loginBlacklistStore = loginBlacklistStore;
        this.loginRateLimitStore = loginRateLimitStore;
        this.passwordEncoder = passwordEncoder;
    }

    /**
     * 注册用户并创建会话。
     */
    @Override
    public AuthSessionData register(RegisterRequest request) {
        String phone = normalizeIdentifier(request.phone());

        String userId = UUID.randomUUID().toString();
        String encodedPassword = passwordEncoder.encode(request.password());
        AuthUserEntity account = new AuthUserEntity(userId, phone, request.nickname(), encodedPassword);

        // 原子写入，避免并发注册同手机号时的检查-写入竞争窗口。
        if (!authUserMapper.saveIfPhoneAbsent(account)) {
            throw new BusinessException(ErrorCode.PHONE_EXISTS, HttpStatus.CONFLICT);
        }

        AuthTokens tokens = issueAndStoreTokens(userId);
        return new AuthSessionData(userId, tokens);
    }

    /**
     * 登录并创建会话。
     */
    @Override
    public AuthSessionData login(LoginRequest request) {
        String identifier = normalizeIdentifier(request.identifier());
        String accessToken = normalizeIdentifier(request.accessToken());

        // 账号密码校验前执行黑名单拦截。
        if (loginBlacklistStore.isBlocked(identifier)) {
            throw new BusinessException(ErrorCode.LOGIN_BLOCKED, HttpStatus.FORBIDDEN);
        }

        // 同手机号请求最小间隔 20s。
        if (!loginRateLimitStore.tryAcquire(identifier, accessToken, LOGIN_MIN_INTERVAL)) {
            throw new BusinessException(ErrorCode.RATE_LIMITED, HttpStatus.TOO_MANY_REQUESTS,
                    "Login request is too frequent, please retry after 20 seconds");
        }

        AuthUserEntity account = authUserMapper.findByPhone(identifier)
                .orElseThrow(() -> raiseLoginFailure(identifier));

        if (!passwordEncoder.matches(request.password(), account.passwordHash())) {
            throw raiseLoginFailure(identifier);
        }

        loginRateLimitStore.resetFailures(identifier);
        AuthTokens tokens = issueAndStoreTokens(account.userId());
        return new AuthSessionData(account.userId(), tokens);
    }

    /**
     * 刷新令牌并执行轮换。
     */
    @Override
    public AuthTokens refreshToken(String refreshToken) {
        RefreshTokenClaims claims = verifyRefreshTokenOrThrowInvalid(refreshToken);

        AuthUserEntity account = authUserMapper.findByUserId(claims.userId())
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_REFRESH_TOKEN, HttpStatus.UNAUTHORIZED));

        // 命中黑名单时，主动吊销用户全部 refresh 会话，再拒绝本次换发。
        if (loginBlacklistStore.isBlocked(account.phone())) {
            refreshTokenStore.removeAll(claims.userId());
            throw new BusinessException(ErrorCode.LOGIN_BLOCKED, HttpStatus.FORBIDDEN);
        }

        // 原子消费 refresh token，防并发重放。
        if (!refreshTokenStore.consumeIfValid(claims.userId(), claims.jti())) {
            throw new BusinessException(ErrorCode.INVALID_REFRESH_TOKEN, HttpStatus.UNAUTHORIZED);
        }

        return issueAndStoreTokens(claims.userId());
    }

    /**
     * 执行登出并按范围撤销 refresh token。
     */
    @Override
    public ActionResult logout(String refreshToken, String logoutScope) {
        RefreshTokenClaims claims = verifyRefreshTokenOrThrowInvalid(refreshToken);
        String scope = normalizeScope(logoutScope);

        if (ALL_DEVICES.equals(scope)) {
            refreshTokenStore.removeAll(claims.userId());
        } else {
            refreshTokenStore.remove(claims.userId(), claims.jti());
        }
        return new ActionResult(true, "logout", claims.userId(), "done");
    }

    /**
     * 重置密码并使历史会话失效。
     */
    @Override
    public ActionResult resetPassword(PasswordResetRequest request) {
        String phone = normalizeIdentifier(request.phone());
        AuthUserEntity account = authUserMapper.findByPhone(phone)
                .orElseThrow(() -> new BusinessException(ErrorCode.BAD_REQUEST, HttpStatus.BAD_REQUEST, "Phone not registered"));

        String encodedPassword = passwordEncoder.encode(request.newPassword());
        AuthUserEntity updated = account.withPasswordHash(encodedPassword);
        authUserMapper.update(updated);

        // 重置密码后清理该用户全部 refresh 会话。
        refreshTokenStore.removeAll(account.userId());
        return new ActionResult(true, "password_reset", account.userId(), "done");
    }

    /**
     * 签发并保存 refresh 令牌。
     */
    private AuthTokens issueAndStoreTokens(String userId) {
        AuthTokens tokens = jwtService.issueTokens(userId);
        RefreshTokenClaims claims = verifyRefreshTokenOrThrowInvalid(tokens.refreshToken());
        refreshTokenStore.save(claims.userId(), claims.jti(), claims.expiresAt());
        return tokens;
    }

    /**
     * 校验 refresh token 并统一错误码语义。
     */
    private RefreshTokenClaims verifyRefreshTokenOrThrowInvalid(String refreshToken) {
        try {
            return jwtService.verifyRefreshToken(refreshToken);
        } catch (BusinessException ex) {
            if (ex.errorCode() == ErrorCode.UNAUTHORIZED || ex.httpStatus() == HttpStatus.UNAUTHORIZED) {
                throw new BusinessException(ErrorCode.INVALID_REFRESH_TOKEN, HttpStatus.UNAUTHORIZED);
            }
            throw ex;
        }
    }

    /**
     * 处理登录失败并执行失败计数、拉黑。
     */
    private BusinessException raiseLoginFailure(String identifier) {
        int failures = loginRateLimitStore.incrementFailure(identifier, LOGIN_FAILURE_TTL);
        if (failures >= MAX_LOGIN_FAILURES) {
            loginBlacklistStore.block(identifier, LOGIN_BLOCK_TTL);
            return new BusinessException(ErrorCode.LOGIN_BLOCKED, HttpStatus.FORBIDDEN,
                    "Login blocked after too many failed attempts");
        }

        return new BusinessException(ErrorCode.UNAUTHORIZED, HttpStatus.UNAUTHORIZED);
    }

    /**
     * 规范化登出范围参数。
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
     * 规范化登录标识。
     */
    private String normalizeIdentifier(String identifier) {
        if (identifier == null) {
            return "";
        }
        return identifier.trim();
    }

}
