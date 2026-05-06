package com.zhiguang.be.auth.config;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/**
 * JWT 编解码配置类。
 * 负责初始化 RSA 密钥材料、JWT 编码器以及不同用途的解码器。
 */
@Configuration
@EnableConfigurationProperties(AuthJwtProperties.class)
public class AuthJwtConfiguration {

    /**
     * 初始化 RSA 密钥材料。
     * 优先使用配置文件中的 PEM 公私钥；若缺失且明确允许，才会生成临时密钥对。
     *
     * @param properties JWT 配置属性
     * @return RSA 密钥材料对象
     */
    @Bean
    public RsaKeyMaterial rsaKeyMaterial(AuthJwtProperties properties) {
        try {
            String publicPem = properties.getPublicKey();
            String privatePem = properties.getPrivateKey();
            if (publicPem != null && !publicPem.isBlank() && privatePem != null && !privatePem.isBlank()) {
                RSAPublicKey publicKey = readPublicKey(publicPem);
                RSAPrivateKey privateKey = readPrivateKey(privatePem);
                return new RsaKeyMaterial(publicKey, privateKey);
            }

            if (!properties.isAllowEphemeralKeys()) {
                throw new IllegalStateException("JWT key material is missing and security.jwt.allow-ephemeral-keys=false");
            }
            // 仅在显式允许时生成临时密钥，避免生产环境因重启导致令牌整体失效。
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            KeyPair pair = generator.generateKeyPair();
            return new RsaKeyMaterial((RSAPublicKey) pair.getPublic(), (RSAPrivateKey) pair.getPrivate());
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to initialize RSA key pair", ex);
        }
    }

    /**
     * 创建 JWT 编码器。
     * 编码器会使用 RSA 公私钥和 keyId 来签发令牌。
     *
     * @param keyMaterial RSA 密钥材料
     * @param properties JWT 配置属性
     * @return JWT 编码器
     */
    @Bean
    public JwtEncoder jwtEncoder(RsaKeyMaterial keyMaterial, AuthJwtProperties properties) {
        RSAKey rsaKey = new RSAKey.Builder(keyMaterial.publicKey())
                .privateKey(keyMaterial.privateKey())
                .keyID(properties.getKeyId())
                .build();
        JWKSource<SecurityContext> jwkSource = (selector, context) -> selector.select(new JWKSet(rsaKey));
        return new NimbusJwtEncoder(jwkSource);
    }

    /**
     * 创建访问令牌专用解码器。
     * 除基础 issuer 校验外，还会强制要求 {@code token_type=access}。
     *
     * @param keyMaterial RSA 密钥材料
     * @param properties JWT 配置属性
     * @return 访问令牌解码器
     */
    @Bean("accessJwtDecoder")
    public JwtDecoder accessJwtDecoder(RsaKeyMaterial keyMaterial, AuthJwtProperties properties) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey(keyMaterial.publicKey()).build();

        OAuth2TokenValidator<Jwt> baseValidator = JwtValidators.createDefaultWithIssuer(properties.getIssuer());
        OAuth2TokenValidator<Jwt> accessTypeValidator = jwt -> {
            String tokenType = jwt.getClaimAsString("token_type");
            if ("access".equals(tokenType)) {
                return OAuth2TokenValidatorResult.success();
            }
            return OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "Not an access token", null));
        };

        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(baseValidator, accessTypeValidator));
        return decoder;
    }

    /**
     * 创建通用令牌解码器。
     * 该解码器主要用于刷新令牌解析，只做签发方等基础校验。
     *
     * @param keyMaterial RSA 密钥材料
     * @param properties JWT 配置属性
     * @return 通用令牌解码器
     */
    @Bean("tokenJwtDecoder")
    public JwtDecoder tokenJwtDecoder(RsaKeyMaterial keyMaterial, AuthJwtProperties properties) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey(keyMaterial.publicKey()).build();
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(properties.getIssuer()));
        return decoder;
    }

    /**
     * 将 PEM 公钥文本解析为 RSA 公钥对象。
     *
     * @param pem PEM 格式公钥文本
     * @return RSA 公钥
     * @throws Exception 密钥解析异常
     */
    private RSAPublicKey readPublicKey(String pem) throws Exception {
        String normalized = pem.replace("\\n", "\n")
                .replace("-----BEGIN PUBLIC KEY-----", "")
                .replace("-----END PUBLIC KEY-----", "")
                .replaceAll("\\s", "");
        byte[] decoded = Base64.getDecoder().decode(normalized);
        X509EncodedKeySpec spec = new X509EncodedKeySpec(decoded);
        return (RSAPublicKey) KeyFactory.getInstance("RSA").generatePublic(spec);
    }

    /**
     * 将 PEM 私钥文本解析为 RSA 私钥对象。
     *
     * @param pem PEM 格式私钥文本
     * @return RSA 私钥
     * @throws Exception 密钥解析异常
     */
    private RSAPrivateKey readPrivateKey(String pem) throws Exception {
        String normalized = pem.replace("\\n", "\n")
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s", "");
        byte[] decoded = Base64.getDecoder().decode(normalized);
        PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(decoded);
        return (RSAPrivateKey) KeyFactory.getInstance("RSA").generatePrivate(spec);
    }
}