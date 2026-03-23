package com.zhiguang.be.auth.service.impl;

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
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

@Service
/**
 * 类说明。
 */
public class AuthServiceImpl implements AuthService {

    private static final String ALL_DEVICES = "all_devices";
    private static final String CURRENT_DEVICE = "current_device";

    private final JwtService jwtService;
    private final RefreshTokenStore refreshTokenStore;
    private final AuthUserMapper authUserMapper;

    /**
     * 方法说明。
     */
    public AuthServiceImpl(JwtService jwtService, RefreshTokenStore refreshTokenStore, AuthUserMapper authUserMapper) {
        this.jwtService = jwtService;
        this.refreshTokenStore = refreshTokenStore;
        this.authUserMapper = authUserMapper;
    }

    @Override
    /**
     * 方法说明。
     */
    public AuthSessionData register(RegisterRequest request) {
        if (authUserMapper.existsByPhone(request.phone())) {
            throw new BusinessException(ErrorCode.PHONE_EXISTS, HttpStatus.CONFLICT);
        }

        String userId = UUID.randomUUID().toString();
        AuthUserEntity account = new AuthUserEntity(userId, request.phone(), request.nickname(), hash(request.password()));
        authUserMapper.save(account);

        AuthTokens tokens = issueAndStoreTokens(userId);
        return new AuthSessionData(userId, tokens);
    }

    @Override
    /**
     * 方法说明。
     */
    public AuthSessionData login(LoginRequest request) {
        AuthUserEntity account = authUserMapper.findByPhone(request.identifier())
                .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED, HttpStatus.UNAUTHORIZED));

        if (!account.passwordHash().equals(hash(request.password()))) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, HttpStatus.UNAUTHORIZED);
        }

        AuthTokens tokens = issueAndStoreTokens(account.userId());
        return new AuthSessionData(account.userId(), tokens);
    }

    @Override
    /**
     * 方法说明。
     */
    public AuthTokens refreshToken(String refreshToken) {
        RefreshTokenClaims claims = jwtService.verifyRefreshToken(refreshToken);
        if (!refreshTokenStore.isValid(claims.userId(), claims.jti())) {
            throw new BusinessException(ErrorCode.INVALID_REFRESH_TOKEN, HttpStatus.UNAUTHORIZED);
        }

        // 刷新令牌轮换：先吊销旧令牌标识，再签发并保存新刷新令牌。
        refreshTokenStore.remove(claims.userId(), claims.jti());
        return issueAndStoreTokens(claims.userId());
    }

    @Override
    /**
     * 方法说明。
     */
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

    @Override
    /**
     * 方法说明。
     */
    public ActionResult resetPassword(PasswordResetRequest request) {
        AuthUserEntity account = authUserMapper.findByPhone(request.phone())
                .orElseThrow(() -> new BusinessException(ErrorCode.BAD_REQUEST, HttpStatus.BAD_REQUEST, "Phone not registered"));

        AuthUserEntity updated = account.withPasswordHash(hash(request.newPassword()));
        authUserMapper.update(updated);

        // 重置密码后需要使该用户所有刷新会话失效。
        refreshTokenStore.removeAll(account.userId());
        return new ActionResult(true, "password_reset", account.userId(), "done");
    }

    /**
     * 方法说明。
     */
    private AuthTokens issueAndStoreTokens(String userId) {
        AuthTokens tokens = jwtService.issueTokens(userId);
        RefreshTokenClaims claims = jwtService.verifyRefreshToken(tokens.refreshToken());
        refreshTokenStore.save(claims.userId(), claims.jti(), claims.expiresAt());
        return tokens;
    }

    /**
     * 方法说明。
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
     * 方法说明。
     */
    private String hash(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }
}
