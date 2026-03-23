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

@Configuration
@EnableConfigurationProperties(AuthJwtProperties.class)
/**
 * 类说明。
 */
public class AuthJwtConfiguration {

    @Bean
    /**
     * 方法说明。
     */
    public RsaKeyMaterial rsaKeyMaterial(AuthJwtProperties properties) {
        try {
            String publicPem = properties.getPublicKey();
            String privatePem = properties.getPrivateKey();
            if (publicPem != null && !publicPem.isBlank() && privatePem != null && !privatePem.isBlank()) {
                RSAPublicKey publicKey = readPublicKey(publicPem);
                RSAPrivateKey privateKey = readPrivateKey(privatePem);
                return new RsaKeyMaterial(publicKey, privateKey);
            }

            // 当未配置密钥文本时，为本地开发生成临时密钥对。
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            KeyPair pair = generator.generateKeyPair();
            return new RsaKeyMaterial((RSAPublicKey) pair.getPublic(), (RSAPrivateKey) pair.getPrivate());
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to initialize RSA key pair", ex);
        }
    }

    @Bean
    /**
     * 方法说明。
     */
    public JwtEncoder jwtEncoder(RsaKeyMaterial keyMaterial, AuthJwtProperties properties) {
        RSAKey rsaKey = new RSAKey.Builder(keyMaterial.publicKey())
                .privateKey(keyMaterial.privateKey())
                .keyID(properties.getKeyId())
                .build();
        JWKSource<SecurityContext> jwkSource = (selector, context) -> selector.select(new JWKSet(rsaKey));
        return new NimbusJwtEncoder(jwkSource);
    }

    @Bean("accessJwtDecoder")
    /**
     * 方法说明。
     */
    public JwtDecoder accessJwtDecoder(RsaKeyMaterial keyMaterial, AuthJwtProperties properties) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey(keyMaterial.publicKey()).build();

        OAuth2TokenValidator<Jwt> baseValidator = JwtValidators.createDefaultWithIssuer(properties.getIssuer());
        // 访问令牌解码器必须拒绝刷新令牌。
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

    @Bean("tokenJwtDecoder")
    /**
     * 方法说明。
     */
    public JwtDecoder tokenJwtDecoder(RsaKeyMaterial keyMaterial, AuthJwtProperties properties) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey(keyMaterial.publicKey()).build();
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(properties.getIssuer()));
        return decoder;
    }

    private RSAPublicKey readPublicKey(String pem) throws Exception {
        String normalized = pem.replace("\\n", "\n")
                .replace("-----BEGIN PUBLIC KEY-----", "")
                .replace("-----END PUBLIC KEY-----", "")
                .replaceAll("\\s", "");
        byte[] decoded = Base64.getDecoder().decode(normalized);
        X509EncodedKeySpec spec = new X509EncodedKeySpec(decoded);
        return (RSAPublicKey) KeyFactory.getInstance("RSA").generatePublic(spec);
    }

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
