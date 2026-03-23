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
 * RSA JWT 服务。
 * 负责签发访问令牌与刷新令牌，并对刷新令牌进行校验解析。
 */
@Service
public class RsaJwtService implements JwtService {

    /**
     * 访问令牌类型。
     */
    private static final String ACCESS_TOKEN_TYPE = "access";

    /**
     * 刷新令牌类型。
     */
    private static final String REFRESH_TOKEN_TYPE = "refresh";

    /**
     * 令牌类型声明字段。
     */
    private static final String TOKEN_TYPE_CLAIM = "token_type";

    private final JwtEncoder jwtEncoder;
    private final JwtDecoder tokenJwtDecoder;
    private final AuthJwtProperties properties;

    /**
     * 构造函数。
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
     * 签发双令牌。
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
     * 校验并解析刷新令牌。
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
     * 组装并签名 JWT。
     */
    private String encodeToken(String userId, String jti, String tokenType, Instant issuedAt, Instant expiresAt) {
        // 通过令牌类型字段区分访问令牌与刷新令牌的校验路径。
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
     * 统一构造未授权异常。
     */
    private BusinessException unauthorizedException() {
        return new BusinessException(ErrorCode.UNAUTHORIZED, HttpStatus.UNAUTHORIZED);
    }
}