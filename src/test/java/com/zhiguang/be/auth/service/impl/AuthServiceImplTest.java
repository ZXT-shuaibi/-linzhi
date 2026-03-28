package com.zhiguang.be.auth.service.impl;

import com.zhiguang.be.auth.audit.AuditEvent;
import com.zhiguang.be.auth.audit.AuditLogger;
import com.zhiguang.be.auth.blacklist.LoginBlacklistStore;
import com.zhiguang.be.auth.mapper.AuthUserMapper;
import com.zhiguang.be.auth.mapper.InMemoryAuthUserMapper;
import com.zhiguang.be.auth.model.AuthSessionData;
import com.zhiguang.be.auth.model.AuthTokens;
import com.zhiguang.be.auth.model.AuthUserEntity;
import com.zhiguang.be.auth.model.LoginRequest;
import com.zhiguang.be.auth.model.RegisterRequest;
import com.zhiguang.be.auth.security.CaptchaVerifier;
import com.zhiguang.be.auth.security.LoginFailureTracker;
import com.zhiguang.be.auth.security.SmsCodeVerifier;
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
 * 认证服务单元测试。
 * 重点验证密码加密、刷新令牌单次消费、黑名单拦截和原子写入等核心行为。
 */
class AuthServiceImplTest {

    /**
     * 验证注册后持久化的密码是 BCrypt 哈希，而不是明文。
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
     * 验证同一个刷新令牌只能成功消费一次，重复使用会被拒绝。
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
     * 验证命中黑名单时刷新流程会撤销全部会话，并返回封禁错误。
     */
    @Test
    void refreshShouldRevokeAllSessionsWhenBlacklisted() {
        TestFixture fixture = new TestFixture();
        AuthSessionData registerSession = fixture.service.register(
                new RegisterRequest("13800138002", "Passw0rd!", "tester", "123456")
        );
        AuthSessionData loginSession = fixture.service.login(
                new LoginRequest("13800138002", "Passw0rd!", "h5", "captcha-token-123456")
        );

        fixture.loginBlacklistStore.block("13800138002");

        BusinessException blocked = assertThrows(BusinessException.class,
                () -> fixture.service.refreshToken(registerSession.tokens().refreshToken()));
        assertEquals(ErrorCode.LOGIN_BLOCKED, blocked.errorCode());
        assertEquals(HttpStatus.FORBIDDEN, blocked.httpStatus());

        fixture.loginBlacklistStore.unblock("13800138002");
        BusinessException revoked = assertThrows(BusinessException.class,
                () -> fixture.service.refreshToken(loginSession.tokens().refreshToken()));
        assertEquals(ErrorCode.INVALID_REFRESH_TOKEN, revoked.errorCode());
        assertEquals(HttpStatus.UNAUTHORIZED, revoked.httpStatus());
    }

    /**
     * 验证登录标识进入黑名单后，登录请求会被直接拒绝。
     */
    @Test
    void loginShouldBeBlockedByLoginBlacklist() {
        TestFixture fixture = new TestFixture();
        fixture.service.register(new RegisterRequest("13800138003", "Passw0rd!", "tester", "123456"));
        fixture.loginBlacklistStore.block("13800138003");

        BusinessException ex = assertThrows(BusinessException.class, () -> fixture.service.login(
                new LoginRequest("13800138003", "Passw0rd!", "h5", "captcha-token-123456")
        ));
        assertEquals(ErrorCode.LOGIN_BLOCKED, ex.errorCode());
        assertEquals(HttpStatus.FORBIDDEN, ex.httpStatus());
    }

    /**
     * 验证手机号原子写入逻辑会拒绝重复注册。
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
     * 测试夹具。
     * 负责组装认证服务需要的依赖桩和待测对象。
     */
    private static final class TestFixture {
        private final AuthUserMapper userMapper = new InMemoryAuthUserMapper();
        private final RefreshTokenStore refreshTokenStore = new InMemoryRefreshTokenStore();
        private final SetBasedLoginBlacklistStore loginBlacklistStore = new SetBasedLoginBlacklistStore();
        private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder(4);
        private final JwtService jwtService = new StubJwtService();
        private final LoginFailureTracker failureTracker = new NoOpLoginFailureTracker();
        private final CaptchaVerifier captchaVerifier = new AlwaysPassCaptchaVerifier();
        private final SmsCodeVerifier smsCodeVerifier = new AlwaysPassSmsCodeVerifier();
        private final AuditLogger auditLogger = new NoOpAuditLogger();
        private final AuthServiceImpl service = new AuthServiceImpl(
                jwtService,
                refreshTokenStore,
                userMapper,
                loginBlacklistStore,
                passwordEncoder,
                failureTracker,
                captchaVerifier,
                smsCodeVerifier,
                auditLogger
        );
    }

    /**
     * 基于集合的黑名单测试桩。
     * 用于手动控制某个标识是否处于封禁状态。
     */
    private static final class SetBasedLoginBlacklistStore implements LoginBlacklistStore {
        private final Set<String> blocked = ConcurrentHashMap.newKeySet();

        /**
         * 判断指定标识是否已被加入测试黑名单。
         *
         * @param identifier 登录标识
         * @return 在集合中返回 true，否则返回 false
         */
        @Override
        public boolean isBlocked(String identifier) {
            return blocked.contains(identifier);
        }

        /**
         * 将指定标识加入测试黑名单。
         *
         * @param identifier 登录标识
         */
        private void block(String identifier) {
            blocked.add(identifier);
        }

        /**
         * 将指定标识从测试黑名单移除。
         *
         * @param identifier 登录标识
         */
        private void unblock(String identifier) {
            blocked.remove(identifier);
        }
    }

    /**
     * JWT 服务测试桩。
     * 使用内存映射模拟刷新令牌签发和校验逻辑。
     */
    private static final class StubJwtService implements JwtService {
        private final Map<String, RefreshTokenClaims> refreshClaimsByToken = new ConcurrentHashMap<>();

        /**
         * 为指定用户生成一组测试用令牌。
         *
         * @param userId 用户 ID
         * @return 测试令牌对
         */
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

        /**
         * 校验测试刷新令牌是否存在且未过期。
         *
         * @param refreshToken 刷新令牌字符串
         * @return 刷新令牌声明
         */
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
     * 不做任何限制的登录失败跟踪器测试桩。
     */
    private static final class NoOpLoginFailureTracker implements LoginFailureTracker {

        /**
         * 忽略失败记录。
         *
         * @param identifier 登录标识
         */
        @Override
        public void recordFailure(String identifier) {
        }

        /**
         * 固定返回 0 次失败。
         *
         * @param identifier 登录标识
         * @return 固定为 0
         */
        @Override
        public int getFailureCount(String identifier) {
            return 0;
        }

        /**
         * 固定返回不需要验证码。
         *
         * @param identifier 登录标识
         * @return 固定为 false
         */
        @Override
        public boolean requiresCaptcha(String identifier) {
            return false;
        }

        /**
         * 固定返回不需要封禁。
         *
         * @param identifier 登录标识
         * @return 固定为 false
         */
        @Override
        public boolean shouldBlock(String identifier) {
            return false;
        }

        /**
         * 忽略重置操作。
         *
         * @param identifier 登录标识
         */
        @Override
        public void reset(String identifier) {
        }
    }

    /**
     * 总是通过的验证码校验器测试桩。
     */
    private static final class AlwaysPassCaptchaVerifier implements CaptchaVerifier {

        /**
         * 始终返回验证码校验成功。
         *
         * @param token 验证码令牌
         * @return 固定为 true
         */
        @Override
        public boolean verify(String token) {
            return true;
        }
    }

    /**
     * 总是通过的短信验证码校验器测试桩。
     */
    private static final class AlwaysPassSmsCodeVerifier implements SmsCodeVerifier {

        /**
         * 始终返回短信验证码校验成功。
         *
         * @param phone 手机号
         * @param code 验证码
         * @return 固定为 true
         */
        @Override
        public boolean verify(String phone, String code) {
            return true;
        }
    }

    /**
     * 不输出任何内容的审计日志测试桩。
     */
    private static final class NoOpAuditLogger implements AuditLogger {

        /**
         * 忽略审计日志记录。
         *
         * @param event 审计事件
         */
        @Override
        public void log(AuditEvent event) {
        }
    }
}