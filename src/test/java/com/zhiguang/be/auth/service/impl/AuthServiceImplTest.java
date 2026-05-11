package com.zhiguang.be.auth.service.impl;

import com.zhiguang.be.auth.audit.AuditEvent;
import com.zhiguang.be.auth.audit.AuditLogger;
import com.zhiguang.be.auth.audit.LoginLogService;
import com.zhiguang.be.auth.blacklist.AccessTokenBlocklistStore;
import com.zhiguang.be.auth.blacklist.AuthBlocklistService;
import com.zhiguang.be.auth.blacklist.LoginBlacklistStore;
import com.zhiguang.be.auth.config.AuthJwtProperties;
import com.zhiguang.be.auth.mapper.AuthUserMapper;
import com.zhiguang.be.auth.mapper.InMemoryAuthUserMapper;
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
import com.zhiguang.be.auth.security.LoginFailureTracker;
import com.zhiguang.be.auth.token.InMemoryRefreshTokenStore;
import com.zhiguang.be.auth.token.JwtService;
import com.zhiguang.be.auth.token.RefreshTokenClaims;
import com.zhiguang.be.auth.token.RefreshTokenStore;
import com.zhiguang.be.auth.verification.VerificationScene;
import com.zhiguang.be.auth.verification.VerificationService;
import com.zhiguang.be.common.exception.BusinessException;
import com.zhiguang.be.common.exception.ErrorCode;
import com.zhiguang.be.common.id.SnowflakeIdGenerator;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 认证服务单元测试。
 * 重点覆盖注册、登录、黑名单、验证码挑战、刷新令牌和登录日志语义。
 */
class AuthServiceImplTest {

    private static final ClientInfo CLIENT_INFO = new ClientInfo("127.0.0.1", "JUnit");

    /**
     * 注册成功后不自动登录，密码以哈希形式保存。
     */
    @Test
    void registerShouldPersistBcryptPasswordAndRequireLogin() {
        TestFixture fixture = new TestFixture();
        RegisterRequest request = new RegisterRequest("13800138000", "Passw0rd!", "Passw0rd!", "tester", "123456");

        RegisterResult result = fixture.service.register(request, CLIENT_INFO);

        assertEquals("login", result.nextAction());
        assertEquals("registered", result.status());
        assertEquals("13800138000", result.phone());
        AuthUserEntity persisted = fixture.userMapper.findByPhone("13800138000").orElseThrow();
        assertEquals("13800138000", persisted.phone());
        assertNotEquals("Passw0rd!", persisted.passwordHash());
        assertTrue(persisted.passwordHash().startsWith("$2"));
        verify(fixture.verificationService).verifyOrThrow(eq(VerificationScene.REGISTER), eq("13800138000"), eq("123456"));
        verify(fixture.loginLogService).record(eq(result.userId()), eq("13800138000"), eq("REGISTER"), any(), any(), eq("SUCCESS"), eq("注册成功"));
    }

    /**
     * send-code 会把请求委托给验证码服务。
     */
    @Test
    void sendCodeShouldDelegateToVerificationService() {
        TestFixture fixture = new TestFixture();
        SendCodeResponse expected = new SendCodeResponse("13800138008", VerificationScene.REGISTER, "654321", 600, 600);
        when(fixture.verificationService.sendCode(eq(VerificationScene.REGISTER), eq("13800138008"))).thenReturn(expected);

        SendCodeResponse actual = fixture.service.sendCode(new SendCodeRequest("13800138008", VerificationScene.REGISTER));

        assertEquals(expected, actual);
    }

    /**
     * send-code 会先统一校验标识格式。
     */
    @Test
    void sendCodeShouldRejectInvalidIdentifierFormat() {
        TestFixture fixture = new TestFixture();

        BusinessException ex = assertThrows(BusinessException.class,
                () -> fixture.service.sendCode(new SendCodeRequest("bad-id!", VerificationScene.LOGIN)));

        assertEquals(ErrorCode.BAD_REQUEST, ex.errorCode());
        assertEquals(HttpStatus.BAD_REQUEST, ex.httpStatus());
    }

    /**
     * 登录成功后才会签发 JWT 双令牌，并支持账号登录。
     */
    @Test
    void loginShouldIssueTokensAfterSuccessfulCredentials() {
        TestFixture fixture = new TestFixture();
        RegisterResult result = fixture.service.register(new RegisterRequest("13800138001", "Passw0rd!", "Passw0rd!", "tester", "123456"), CLIENT_INFO);

        AuthSessionData session = fixture.service.login(new LoginRequest("13800138001", "Passw0rd!", "h5", null), CLIENT_INFO);

        assertEquals(result.userId(), session.userId());
        assertNotNull(session.tokens());
        assertTrue(session.tokens().accessToken() != null && !session.tokens().accessToken().isBlank());
        assertTrue(session.tokens().refreshToken() != null && !session.tokens().refreshToken().isBlank());
        verify(fixture.loginLogService).record(eq(result.userId()), eq("13800138001"), eq("PASSWORD"), any(), any(), eq("SUCCESS"), eq("登录成功"));
    }

    /**
     * 验证码登录不需要提交密码，会校验登录验证码后签发令牌。
     */
    @Test
    void loginShouldIssueTokensAfterSuccessfulLoginCode() {
        TestFixture fixture = new TestFixture();
        RegisterResult result = fixture.service.register(new RegisterRequest("13800138009", "Passw0rd!", "Passw0rd!", "tester", "123456"), CLIENT_INFO);

        AuthSessionData session = fixture.service.login(new LoginRequest("13800138009", null, "h5", "654321"), CLIENT_INFO);

        assertEquals(result.userId(), session.userId());
        assertNotNull(session.tokens());
        verify(fixture.verificationService).verifyOrThrow(eq(VerificationScene.LOGIN), eq("13800138009"), eq("654321"));
        verify(fixture.loginLogService).record(eq(result.userId()), eq("13800138009"), eq("CODE"), any(), any(), eq("SUCCESS"), eq("登录成功"));
    }

    /**
     * 登录请求必须至少提供密码或验证码。
     */
    @Test
    void loginShouldRejectMissingCredential() {
        TestFixture fixture = new TestFixture();

        BusinessException ex = assertThrows(BusinessException.class,
                () -> fixture.service.login(new LoginRequest("13800138999", null, "h5", null), CLIENT_INFO));

        assertEquals(ErrorCode.BAD_REQUEST, ex.errorCode());
        assertEquals(HttpStatus.BAD_REQUEST, ex.httpStatus());
    }

    /**
     * 未注册账号应返回独立状态码，且不污染失败计数。
     */
    @Test
    void loginShouldReturnNotFoundWhenAccountIsMissingAndNotPolluteFailureCount() {
        TestFixture fixture = new TestFixture();

        BusinessException ex = assertThrows(BusinessException.class,
                () -> fixture.service.login(new LoginRequest("13800138888", "Passw0rd!", "h5", null), CLIENT_INFO));

        assertEquals(ErrorCode.ACCOUNT_NOT_FOUND, ex.errorCode());
        assertEquals(HttpStatus.NOT_FOUND, ex.httpStatus());
        assertEquals(0, fixture.failureTracker.getFailureCount("13800138888"));
    }

    /**
     * 登录入口会先统一校验标识格式。
     */
    @Test
    void loginShouldRejectInvalidIdentifierFormat() {
        TestFixture fixture = new TestFixture();

        BusinessException ex = assertThrows(BusinessException.class,
                () -> fixture.service.login(new LoginRequest("bad-id!", "Passw0rd!", "h5", null), CLIENT_INFO));

        assertEquals(ErrorCode.BAD_REQUEST, ex.errorCode());
        assertEquals(HttpStatus.BAD_REQUEST, ex.httpStatus());
    }

    /**
     * register 在直接调用 service 时也会校验账号格式。
     */
    @Test
    void registerShouldRejectMismatchedConfirmPassword() {
        TestFixture fixture = new TestFixture();

        BusinessException ex = assertThrows(BusinessException.class,
                () -> fixture.service.register(new RegisterRequest("13800138011", "Passw0rd!", "Different1", "tester", "123456"), CLIENT_INFO));

        assertEquals(ErrorCode.BAD_REQUEST, ex.errorCode());
        assertEquals(HttpStatus.BAD_REQUEST, ex.httpStatus());
    }

    /**
     * 命中风控阈值后，登录会要求并校验真实验证码。
     */
    @Test
    void loginShouldVerifyCaptchaCodeWhenRiskTriggered() {
        TestFixture fixture = new TestFixture();
        fixture.service.register(new RegisterRequest("13800138006", "Passw0rd!", "Passw0rd!", "tester", "123456"), CLIENT_INFO);
        fixture.failureTracker.setFailureCount("13800138006", 3);

        fixture.service.login(new LoginRequest("13800138006", "Passw0rd!", "h5", "654321"), CLIENT_INFO);

        verify(fixture.verificationService).verifyOrThrow(eq(VerificationScene.LOGIN), eq("13800138006"), eq("654321"));
    }

    /**
     * refresh token 只能成功消费一次。
     */
    @Test
    void refreshTokenShouldBeSingleUse() {
        TestFixture fixture = new TestFixture();
        fixture.service.register(new RegisterRequest("13800138002", "Passw0rd!", "Passw0rd!", "tester", "123456"), CLIENT_INFO);
        AuthSessionData session = fixture.service.login(new LoginRequest("13800138002", "Passw0rd!", "h5", null), CLIENT_INFO);

        AuthTokens firstRefresh = fixture.service.refreshToken(session.tokens().refreshToken());
        assertTrue(firstRefresh.refreshToken() != null && !firstRefresh.refreshToken().isBlank());

        BusinessException ex = assertThrows(BusinessException.class,
                () -> fixture.service.refreshToken(session.tokens().refreshToken()));
        assertEquals(ErrorCode.INVALID_REFRESH_TOKEN, ex.errorCode());
        assertEquals(HttpStatus.UNAUTHORIZED, ex.httpStatus());
    }

    /**
     * 管理员降权时刷新令牌应被拒绝，强制重新登录以获取最新角色。
     */
    @Test
    void refreshTokenShouldRejectWhenAdminIsDowngraded() {
        TestFixture fixture = new TestFixture();
        RegisterResult result = fixture.service.register(new RegisterRequest("13800138108", "Passw0rd!", "Passw0rd!", "tester", "123456"), CLIENT_INFO);
        fixture.updateRole(result.userId(), "ADMIN");
        AuthSessionData adminSession = fixture.service.login(new LoginRequest("13800138108", "Passw0rd!", "h5", null), CLIENT_INFO);

        fixture.updateRole(result.userId(), "USER");
        BusinessException ex = assertThrows(BusinessException.class,
                () -> fixture.service.refreshToken(adminSession.tokens().refreshToken()));
        assertEquals(ErrorCode.LOGIN_BLOCKED, ex.errorCode());
    }

    /**
     * refresh 命中黑名单后会撤销全部 refresh token，并拉起 access token 失效标记。
     */
    @Test
    void refreshShouldRevokeAllSessionsWhenUserIdIsBlacklisted() {
        TestFixture fixture = new TestFixture();
        RegisterResult result = fixture.service.register(new RegisterRequest("13800138003", "Passw0rd!", "Passw0rd!", "tester", "123456"), CLIENT_INFO);
        AuthSessionData loginSession = fixture.service.login(new LoginRequest("13800138003", "Passw0rd!", "h5", null), CLIENT_INFO);
        fixture.loginBlacklistStore.block(result.userId());

        BusinessException blocked = assertThrows(BusinessException.class,
                () -> fixture.service.refreshToken(loginSession.tokens().refreshToken()));
        assertEquals(ErrorCode.LOGIN_BLOCKED, blocked.errorCode());
        assertEquals(HttpStatus.FORBIDDEN, blocked.httpStatus());
        verify(fixture.accessTokenBlocklistStore).block(eq(result.userId()), any(Instant.class), any(Duration.class));

        fixture.loginBlacklistStore.unblock(result.userId());
        BusinessException revoked = assertThrows(BusinessException.class,
                () -> fixture.service.refreshToken(loginSession.tokens().refreshToken()));
        assertEquals(ErrorCode.INVALID_REFRESH_TOKEN, revoked.errorCode());
        assertEquals(HttpStatus.UNAUTHORIZED, revoked.httpStatus());
    }

    /**
     * 手机号进入黑名单后，即使改用账号登录也会被拒绝。
     */
    @Test
    void loginShouldBeBlockedByPhoneBlacklistEvenWhenUsingAccount() {
        TestFixture fixture = new TestFixture();
        fixture.service.register(new RegisterRequest("13800138004", "Passw0rd!", "Passw0rd!", "tester", "123456"), CLIENT_INFO);
        fixture.loginBlacklistStore.block("13800138004");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> fixture.service.login(new LoginRequest("13800138004", "Passw0rd!", "h5", null), CLIENT_INFO));
        assertEquals(ErrorCode.LOGIN_BLOCKED, ex.errorCode());
        assertEquals(HttpStatus.FORBIDDEN, ex.httpStatus());
    }

    /**
     * 用户 ID 进入黑名单后，也会拒绝再次登录。
     */
    @Test
    void loginShouldBeBlockedWhenUserIdIsBlacklisted() {
        TestFixture fixture = new TestFixture();
        RegisterResult result = fixture.service.register(new RegisterRequest("13800138104", "Passw0rd!", "Passw0rd!", "tester", "123456"), CLIENT_INFO);
        fixture.loginBlacklistStore.block(result.userId());

        BusinessException ex = assertThrows(BusinessException.class,
                () -> fixture.service.login(new LoginRequest("13800138104", "Passw0rd!", "h5", null), CLIENT_INFO));
        assertEquals(ErrorCode.LOGIN_BLOCKED, ex.errorCode());
        assertEquals(HttpStatus.FORBIDDEN, ex.httpStatus());
    }

    /**
     * 重置密码后会清理失败计数，并让旧 access token 立即失效。
     */
    @Test
    void resetPasswordShouldClearFailureCountsAndBlockOldAccessTokens() {
        TestFixture fixture = new TestFixture();
        RegisterResult result = fixture.service.register(new RegisterRequest("13800138106", "Passw0rd!", "Passw0rd!", "tester", "123456"), CLIENT_INFO);
        fixture.failureTracker.setFailureCount("13800138106", 4);

        ActionResult resultAfterReset = fixture.service.resetPassword(new PasswordResetRequest("13800138106", "123456", "NewPass123"));

        assertEquals(result.userId(), resultAfterReset.resourceId());
        assertEquals(0, fixture.failureTracker.getFailureCount("13800138106"));
        verify(fixture.accessTokenBlocklistStore).block(eq(result.userId()), any(Instant.class), any(Duration.class));
    }

    /**
     * 登出后会拉起 access token 失效标记。
     */
    @Test
    void logoutShouldBlockOldAccessTokens() {
        TestFixture fixture = new TestFixture();
        RegisterResult result = fixture.service.register(new RegisterRequest("13800138107", "Passw0rd!", "Passw0rd!", "tester", "123456"), CLIENT_INFO);
        AuthSessionData session = fixture.service.login(new LoginRequest("13800138107", "Passw0rd!", "h5", null), CLIENT_INFO);

        ActionResult logoutResult = fixture.service.logout(session.tokens().refreshToken(), "current_device");

        assertEquals(result.userId(), logoutResult.resourceId());
        verify(fixture.accessTokenBlocklistStore).block(eq(result.userId()), any(Instant.class), any(Duration.class));
    }

    /**
     * /me 会返回当前用户的最小信息集合。
     */
    @Test
    void meShouldReturnCurrentUserProfile() {
        TestFixture fixture = new TestFixture();
        RegisterResult result = fixture.service.register(new RegisterRequest("13800138105", "Passw0rd!", "Passw0rd!", "tester", "123456"), CLIENT_INFO);

        AuthUserResponse response = fixture.service.me(result.userId());

        assertEquals(result.userId(), response.userId());
        assertEquals("13800138105", response.phone());
        assertEquals("tester", response.nickname());
        assertEquals("USER", response.role());
    }

    /**
     * 内存用户映射会同时拦截重复手机号和重复账号。
     */
    @Test
    void saveIfAbsentShouldRejectDuplicatePhone() {
        InMemoryAuthUserMapper mapper = new InMemoryAuthUserMapper();
        AuthUserEntity first = new AuthUserEntity("u1", "13800138005", "n1", "hash1", "USER");
        AuthUserEntity duplicatePhone = new AuthUserEntity("u2", "13800138005", "n2", "hash2", "USER");

        assertTrue(mapper.saveIfAbsent(first));
        assertFalse(mapper.saveIfAbsent(duplicatePhone));
    }

    /**
     * 测试夹具。
     * 负责组装认证服务所需依赖。
     */
    private static final class TestFixture {
        private final AuthUserMapper userMapper = new InMemoryAuthUserMapper();
        private final RefreshTokenStore refreshTokenStore = new InMemoryRefreshTokenStore();
        private final SetBasedLoginBlacklistStore loginBlacklistStore = new SetBasedLoginBlacklistStore();
        private final AccessTokenBlocklistStore accessTokenBlocklistStore = mock(AccessTokenBlocklistStore.class);
        private final AuthBlocklistService blocklistService = new AuthBlocklistService(
                loginBlacklistStore,
                accessTokenBlocklistStore,
                refreshTokenStore
        );
        private final AuthJwtProperties authJwtProperties = new AuthJwtProperties();
        private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder(4);
        private final StubJwtService jwtService = new StubJwtService();
        private final ConfigurableLoginFailureTracker failureTracker = new ConfigurableLoginFailureTracker();
        private final VerificationService verificationService = mock(VerificationService.class);
        private final AuditLogger auditLogger = new NoOpAuditLogger();
        private final LoginLogService loginLogService = mock(LoginLogService.class);
        private final AuthServiceImpl service = new AuthServiceImpl(
                jwtService,
                refreshTokenStore,
                userMapper,
                loginBlacklistStore,
                blocklistService,
                passwordEncoder,
                failureTracker,
                verificationService,
                auditLogger,
                loginLogService,
                authJwtProperties,
                new SnowflakeIdGenerator(1, 1)
        );

        /**
         * 预设验证码校验默认通过。
         */
        private TestFixture() {
            doNothing().when(verificationService).verifyOrThrow(any(), anyString(), anyString());
            when(verificationService.sendCode(eq(VerificationScene.REGISTER), eq("13800138008")))
                    .thenReturn(new SendCodeResponse("13800138008", VerificationScene.REGISTER, "654321", 600, 600));
        }

        private void updateRole(String userId, String role) {
            AuthUserEntity account = userMapper.findByUserId(userId).orElseThrow();
            userMapper.update(new AuthUserEntity(
                    account.userId(),
                    account.phone(),
                    account.nickname(),
                    account.passwordHash(),
                    role
            ));
        }
    }

    /**
     * 基于集合的登录黑名单测试桩。
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

        @Override
        public void unblock(String identifier) {
            blocked.remove(identifier);
        }

        private void block(String identifier) {
            block(identifier, Duration.ofMinutes(30));
        }
    }

    /**
     * 可配置失败次数的风控测试桩。
     */
    private static final class ConfigurableLoginFailureTracker implements LoginFailureTracker {
        private static final int CAPTCHA_THRESHOLD = 3;
        private static final int BLOCK_THRESHOLD = 10;

        private final Map<String, Integer> failureCounts = new ConcurrentHashMap<>();

        @Override
        public void recordFailure(String identifier) {
            failureCounts.merge(identifier, 1, Integer::sum);
        }

        @Override
        public int getFailureCount(String identifier) {
            return failureCounts.getOrDefault(identifier, 0);
        }

        @Override
        public boolean requiresCaptcha(String identifier) {
            return getFailureCount(identifier) >= CAPTCHA_THRESHOLD;
        }

        @Override
        public boolean shouldBlock(String identifier) {
            return getFailureCount(identifier) >= BLOCK_THRESHOLD;
        }

        @Override
        public void reset(String identifier) {
            failureCounts.remove(identifier);
        }

        private void setFailureCount(String identifier, int count) {
            failureCounts.put(identifier, count);
        }
    }

    /**
     * JWT 服务测试桩。
     */
    private static final class StubJwtService implements JwtService {
        private final Map<String, RefreshTokenClaims> refreshClaimsByToken = new ConcurrentHashMap<>();

        @Override
        public AuthTokens issueTokens(String userId, String role) {
            Instant now = Instant.now();
            Instant accessExpiresAt = now.plusSeconds(900);
            Instant refreshExpiresAt = now.plusSeconds(7 * 24 * 3600L);
            String accessToken = "access-" + UUID.randomUUID();
            String jti = UUID.randomUUID().toString();
            String refreshToken = "refresh-" + jti;
            refreshClaimsByToken.put(refreshToken, new RefreshTokenClaims(userId, role, jti, refreshExpiresAt));
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

    /**
     * 空实现审计日志测试桩。
     */
    private static final class NoOpAuditLogger implements AuditLogger {
        @Override
        public void log(AuditEvent event) {
        }
    }
}
