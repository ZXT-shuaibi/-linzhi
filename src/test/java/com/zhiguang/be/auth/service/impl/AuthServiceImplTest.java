package com.zhiguang.be.auth.service.impl;

import com.zhiguang.be.auth.blacklist.LoginBlacklistStore;
import com.zhiguang.be.auth.mapper.AuthUserMapper;
import com.zhiguang.be.auth.mapper.InMemoryAuthUserMapper;
import com.zhiguang.be.auth.model.AuthSessionData;
import com.zhiguang.be.auth.model.AuthTokens;
import com.zhiguang.be.auth.model.AuthUserEntity;
import com.zhiguang.be.auth.model.LoginRequest;
import com.zhiguang.be.auth.model.RegisterRequest;
import com.zhiguang.be.auth.risk.LoginRateLimitStore;
import com.zhiguang.be.auth.token.InMemoryRefreshTokenStore;
import com.zhiguang.be.auth.token.JwtService;
import com.zhiguang.be.auth.token.RefreshTokenClaims;
import com.zhiguang.be.auth.token.RefreshTokenStore;
import com.zhiguang.be.common.exception.BusinessException;
import com.zhiguang.be.common.exception.ErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AuthServiceImpl 单元测试。
 */
class AuthServiceImplTest {

    /**
     * 注册后的密码应使用 BCrypt 加密，不应明文存储。
     */
    @Test
    void registerShouldPersistBcryptPassword() {
        TestFixture fixture = new TestFixture();
        RegisterRequest request = new RegisterRequest("13800138000", "Passw0rd!", "tester", "123456");

        fixture.service.register(request);

        String persistedHash = fixture.userMapper.findByPhone("13800138000")
                .orElseThrow()
                .passwordHash();
        assertNotEquals("Passw0rd!", persistedHash);
        assertTrue(persistedHash.startsWith("$2"));
    }

    /**
     * 同一个 refresh token 只能消费一次，重复使用应被拒绝。
     */
    @Test
    void refreshTokenShouldBeSingleUse() {
        TestFixture fixture = new TestFixture();
        AuthSessionData session = fixture.service.register(
                new RegisterRequest("13800138001", "Passw0rd!", "tester", "123456")
        );

        AuthTokens firstRefresh = fixture.service.refreshToken(session.tokens().refreshToken());
        assertTrue(firstRefresh.refreshToken() != null && !firstRefresh.refreshToken().isBlank());

        BusinessException ex = assertThrows(BusinessException.class,
                () -> fixture.service.refreshToken(session.tokens().refreshToken()));
        assertEquals(ErrorCode.INVALID_REFRESH_TOKEN, ex.errorCode());
        assertEquals(HttpStatus.UNAUTHORIZED, ex.httpStatus());
    }

    /**
     * 命中黑名单后 refresh 会先返回 403，并主动吊销该用户全部 refresh 会话。
     */
    @Test
    void refreshShouldRevokeAllSessionsWhenBlacklisted() {
        TestFixture fixture = new TestFixture();
        AuthSessionData registerSession = fixture.service.register(
                new RegisterRequest("13800138002", "Passw0rd!", "tester", "123456")
        );
        AuthSessionData loginSession = fixture.service.login(
                new LoginRequest("13800138002", "Passw0rd!", "h5", "access-token-123456")
        );

        fixture.loginBlacklistStore.block("13800138002");

        BusinessException blocked = assertThrows(BusinessException.class,
                () -> fixture.service.refreshToken(registerSession.tokens().refreshToken()));
        assertEquals(ErrorCode.LOGIN_BLOCKED, blocked.errorCode());
        assertEquals(HttpStatus.FORBIDDEN, blocked.httpStatus());

        // 解封后再次尝试旧 refresh token，验证此前已被 removeAll 吊销。
        fixture.loginBlacklistStore.unblock("13800138002");
        BusinessException revoked = assertThrows(BusinessException.class,
                () -> fixture.service.refreshToken(loginSession.tokens().refreshToken()));
        assertEquals(ErrorCode.INVALID_REFRESH_TOKEN, revoked.errorCode());
        assertEquals(HttpStatus.UNAUTHORIZED, revoked.httpStatus());
    }

    /**
     * 用户命中登录黑名单后，登录应被直接拒绝。
     */
    @Test
    void loginShouldBeBlockedByLoginBlacklist() {
        TestFixture fixture = new TestFixture();
        fixture.service.register(new RegisterRequest("13800138003", "Passw0rd!", "tester", "123456"));
        fixture.loginBlacklistStore.block("13800138003");

        BusinessException ex = assertThrows(BusinessException.class, () -> fixture.service.login(
                new LoginRequest("13800138003", "Passw0rd!", "h5", "access-token-123456")
        ));
        assertEquals(ErrorCode.LOGIN_BLOCKED, ex.errorCode());
        assertEquals(HttpStatus.FORBIDDEN, ex.httpStatus());
    }

    /**
     * 同手机号在最小间隔内重复登录应触发限流。
     */
    @Test
    void loginShouldBeRateLimitedWhenRequestedTooFrequently() {
        TestFixture fixture = new TestFixture();
        fixture.service.register(new RegisterRequest("13800138005", "Passw0rd!", "tester", "123456"));
        fixture.loginRateLimitStore.setAllowAcquire(false);

        BusinessException ex = assertThrows(BusinessException.class, () -> fixture.service.login(
                new LoginRequest("13800138005", "Passw0rd!", "h5", "access-token-123456")
        ));
        assertEquals(ErrorCode.RATE_LIMITED, ex.errorCode());
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, ex.httpStatus());
    }

    /**
     * 登录连续失败 5 次后应被拉黑。
     */
    @Test
    void loginShouldBlockAfterTooManyFailures() {
        TestFixture fixture = new TestFixture();
        fixture.service.register(new RegisterRequest("13800138006", "Passw0rd!", "tester", "123456"));

        for (int i = 0; i < 4; i++) {
            BusinessException wrong = assertThrows(BusinessException.class, () -> fixture.service.login(
                    new LoginRequest("13800138006", "wrongPass!", "h5", "access-token-123456")
            ));
            assertEquals(ErrorCode.UNAUTHORIZED, wrong.errorCode());
        }

        BusinessException blocked = assertThrows(BusinessException.class, () -> fixture.service.login(
                new LoginRequest("13800138006", "wrongPass!", "h5", "access-token-123456")
        ));
        assertEquals(ErrorCode.LOGIN_BLOCKED, blocked.errorCode());
        assertEquals(HttpStatus.FORBIDDEN, blocked.httpStatus());
    }

    /**
     * 刷新 token 验签失败应统一映射为 INVALID_REFRESH_TOKEN。
     */
    @Test
    void refreshShouldMapUnauthorizedToInvalidRefreshToken() {
        TestFixture fixture = new TestFixture();
        BusinessException ex = assertThrows(BusinessException.class,
                () -> fixture.service.refreshToken("unknown-refresh-token"));
        assertEquals(ErrorCode.INVALID_REFRESH_TOKEN, ex.errorCode());
        assertEquals(HttpStatus.UNAUTHORIZED, ex.httpStatus());
    }

    /**
     * 原子写入：同手机号重复写入必须被拒绝。
     */
    @Test
    void saveIfPhoneAbsentShouldRejectDuplicatePhone() {
        InMemoryAuthUserMapper mapper = new InMemoryAuthUserMapper();
        AuthUserEntity first = new AuthUserEntity("u1", "13800138004", "n1", "hash1");
        AuthUserEntity second = new AuthUserEntity("u2", "13800138004", "n2", "hash2");

        assertTrue(mapper.saveIfPhoneAbsent(first));
        assertFalse(mapper.saveIfPhoneAbsent(second));
    }

    /**
     * 测试夹具：组装 AuthServiceImpl 依赖。
     */
    private static final class TestFixture {
        private final AuthUserMapper userMapper = new InMemoryAuthUserMapper();
        private final RefreshTokenStore refreshTokenStore = new InMemoryRefreshTokenStore();
        private final SetBasedLoginBlacklistStore loginBlacklistStore = new SetBasedLoginBlacklistStore();
        private final StubLoginRateLimitStore loginRateLimitStore = new StubLoginRateLimitStore();
        private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder(4);
        private final JwtService jwtService = new StubJwtService();
        private final AuthServiceImpl service = new AuthServiceImpl(
                jwtService,
                refreshTokenStore,
                userMapper,
                loginBlacklistStore,
                loginRateLimitStore,
                passwordEncoder
        );
    }

    /**
     * 简单黑名单存储测试桩。
     */
    private static final class SetBasedLoginBlacklistStore implements LoginBlacklistStore {
        private final Set<String> blocked = ConcurrentHashMap.newKeySet();

        @Override
        public boolean isBlocked(String identifier) {
            return blocked.contains(identifier);
        }

        @Override
        public void block(String identifier, Duration ttl) {
            blocked.add(identifier);
        }

        private void block(String identifier) {
            blocked.add(identifier);
        }

        private void unblock(String identifier) {
            blocked.remove(identifier);
        }
    }

    /**
     * 登录限流存储测试桩。
     */
    private static final class StubLoginRateLimitStore implements LoginRateLimitStore {
        private volatile boolean allowAcquire = true;
        private final ConcurrentHashMap<String, Integer> failures = new ConcurrentHashMap<>();

        @Override
        public boolean tryAcquire(String phone, String accessToken, Duration minInterval) {
            return allowAcquire;
        }

        @Override
        public int incrementFailure(String phone, Duration ttl) {
            return failures.merge(phone, 1, Integer::sum);
        }

        @Override
        public void resetFailures(String phone) {
            failures.remove(phone);
        }

        private void setAllowAcquire(boolean allowAcquire) {
            this.allowAcquire = allowAcquire;
        }
    }

    /**
     * JWT 服务测试桩。
     */
    private static final class StubJwtService implements JwtService {
        private final Map<String, RefreshTokenClaims> refreshClaimsByToken = new ConcurrentHashMap<>();

        @Override
        public AuthTokens issueTokens(String userId) {
            Instant now = Instant.now();
            Instant accessExpiresAt = now.plusSeconds(900);
            Instant refreshExpiresAt = now.plusSeconds(7 * 24 * 3600L);
            String accessToken = "access-" + UUID.randomUUID();
            String jti = UUID.randomUUID().toString();
            String refreshToken = "refresh-" + jti;
            refreshClaimsByToken.put(refreshToken, new RefreshTokenClaims(userId, jti, refreshExpiresAt));
            return new AuthTokens(accessToken, accessExpiresAt, refreshToken, refreshExpiresAt, "Bearer");
        }

        @Override
        public RefreshTokenClaims verifyRefreshToken(String refreshToken) {
            RefreshTokenClaims claims = refreshClaimsByToken.get(refreshToken);
            if (claims == null || Instant.now().isAfter(claims.expiresAt())) {
                throw new BusinessException(ErrorCode.UNAUTHORIZED, HttpStatus.UNAUTHORIZED);
            }
            return claims;
        }
    }
}
