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
import com.zhiguang.be.auth.service.AuthService;
import com.zhiguang.be.auth.token.JwtService;
import com.zhiguang.be.auth.token.RefreshTokenClaims;
import com.zhiguang.be.auth.token.RefreshTokenStore;
import com.zhiguang.be.common.exception.BusinessException;
import com.zhiguang.be.common.exception.ErrorCode;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * 认证服务实现。
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

    /**
     * 构造函数：注入认证流程依赖。
     *
     * @param jwtService JWT 服务
     * @param refreshTokenStore refresh 白名单存储
     * @param authUserMapper 用户数据访问
     * @param loginBlacklistStore 登录黑名单存储
     * @param passwordEncoder 密码加密器
     */
    public AuthServiceImpl(
            JwtService jwtService,
            RefreshTokenStore refreshTokenStore,
            AuthUserMapper authUserMapper,
            LoginBlacklistStore loginBlacklistStore,
            PasswordEncoder passwordEncoder
    ) {
        this.jwtService = jwtService;
        this.refreshTokenStore = refreshTokenStore;
        this.authUserMapper = authUserMapper;
        this.loginBlacklistStore = loginBlacklistStore;
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

        // 账号密码校验前执行黑名单拦截。
        if (loginBlacklistStore.isBlocked(identifier)) {
            throw new BusinessException(ErrorCode.LOGIN_BLOCKED, HttpStatus.FORBIDDEN);
        }

        AuthUserEntity account = authUserMapper.findByPhone(identifier)
                .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED, HttpStatus.UNAUTHORIZED));

        if (!passwordEncoder.matches(request.password(), account.passwordHash())) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, HttpStatus.UNAUTHORIZED);
        }

        AuthTokens tokens = issueAndStoreTokens(account.userId());
        return new AuthSessionData(account.userId(), tokens);
    }

    /**
     * 刷新令牌并执行轮换。
     */
    @Override
    public AuthTokens refreshToken(String refreshToken) {
        RefreshTokenClaims claims = jwtService.verifyRefreshToken(refreshToken);

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
        RefreshTokenClaims claims = jwtService.verifyRefreshToken(tokens.refreshToken());
        refreshTokenStore.save(claims.userId(), claims.jti(), claims.expiresAt());
        return tokens;
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