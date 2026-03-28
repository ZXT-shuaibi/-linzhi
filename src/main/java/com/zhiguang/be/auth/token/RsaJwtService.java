package com.zhiguang.be.auth.token;

import com.zhiguang.be.auth.config.AuthJwtProperties;
import com.zhiguang.be.auth.model.AuthTokens;
import com.zhiguang.be.common.exception.BusinessException;
import com.zhiguang.be.common.exception.ErrorCode;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * 基于 RSA 的 JWT 服务实现。
 * 负责签发访问令牌和刷新令牌，并校验刷新令牌的合法性。
 */
@Service
public class RsaJwtService implements JwtService {

    private static final String ACCESS_TOKEN_TYPE = "access";
    private static final String REFRESH_TOKEN_TYPE = "refresh";
    private static final String TOKEN_TYPE_CLAIM = "token_type";

    private final JwtEncoder jwtEncoder;
    private final JwtDecoder tokenJwtDecoder;
    private final AuthJwtProperties properties;

    /**
     * 构造 JWT 服务实现。
     *
     * @param jwtEncoder JWT 编码器
     * @param tokenJwtDecoder 通用令牌解码器
     * @param properties JWT 配置属性
     */
    public RsaJwtService(
            JwtEncoder jwtEncoder,
            @Qualifier("tokenJwtDecoder") JwtDecoder tokenJwtDecoder,
            AuthJwtProperties properties
    ) {
        this.jwtEncoder = jwtEncoder;
        this.tokenJwtDecoder = tokenJwtDecoder;
        this.properties = properties;
    }

    /**
     * 为指定用户签发访问令牌和刷新令牌。
     * 两类令牌会分别生成不同的 jti 和过期时间。
     *
     * @param userId 用户 ID
     * @return 包含双令牌的认证结果
     */
    @Override
    public AuthTokens issueTokens(String userId) {
        Instant now = Instant.now();

        String accessJti = UUID.randomUUID().toString();
        Instant accessExpiresAt = now.plus(properties.getAccessTokenTtlMinutes(), ChronoUnit.MINUTES);
        String accessToken = encodeToken(userId, accessJti, ACCESS_TOKEN_TYPE, now, accessExpiresAt);

        String refreshJti = UUID.randomUUID().toString();
        Instant refreshExpiresAt = now.plus(properties.getRefreshTokenTtlDays(), ChronoUnit.DAYS);
        String refreshToken = encodeToken(userId, refreshJti, REFRESH_TOKEN_TYPE, now, refreshExpiresAt);

        return new AuthTokens(accessToken, accessExpiresAt, refreshToken, refreshExpiresAt, "Bearer");
    }

    /**
     * 校验刷新令牌并解析出核心声明。
     * 仅允许令牌类型为 refresh，缺失关键字段或校验失败都会抛出未授权异常。
     *
     * @param refreshToken 刷新令牌字符串
     * @return 刷新令牌声明
     */
    @Override
    public RefreshTokenClaims verifyRefreshToken(String refreshToken) {
        Jwt jwt;
        try {
            jwt = tokenJwtDecoder.decode(refreshToken);
        } catch (JwtException ex) {
            throw unauthorizedException();
        }

        String userId = jwt.getSubject();
        String jti = jwt.getId();
        Instant expiresAt = jwt.getExpiresAt();
        if (userId == null || jti == null || expiresAt == null) {
            throw unauthorizedException();
        }

        String tokenType = jwt.getClaimAsString(TOKEN_TYPE_CLAIM);
        if (!REFRESH_TOKEN_TYPE.equals(tokenType)) {
            throw unauthorizedException();
        }

        return new RefreshTokenClaims(userId, jti, expiresAt);
    }

    /**
     * 组装并签发单个 JWT。
     * 根据 tokenType 区分访问令牌和刷新令牌，统一写入标准声明和自定义声明。
     *
     * @param userId 用户 ID
     * @param jti 令牌唯一标识
     * @param tokenType 令牌类型
     * @param issuedAt 签发时间
     * @param expiresAt 过期时间
     * @return 已签名的 JWT 字符串
     */
    private String encodeToken(String userId, String jti, String tokenType, Instant issuedAt, Instant expiresAt) {
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(properties.getIssuer())
                .subject(userId)
                .issuedAt(issuedAt)
                .expiresAt(expiresAt)
                .id(jti)
                .claim(TOKEN_TYPE_CLAIM, tokenType)
                .build();

        JwsHeader header = JwsHeader.with(SignatureAlgorithm.RS256)
                .type("JWT")
                .keyId(properties.getKeyId())
                .build();

        return jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }

    /**
     * 构造统一的未授权业务异常。
     *
     * @return 未授权异常
     */
    private BusinessException unauthorizedException() {
        return new BusinessException(ErrorCode.UNAUTHORIZED, HttpStatus.UNAUTHORIZED);
    }
}