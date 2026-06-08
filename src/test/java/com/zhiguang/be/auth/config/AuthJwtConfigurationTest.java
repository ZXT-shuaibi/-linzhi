package com.zhiguang.be.auth.config;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import com.zhiguang.be.auth.model.AuthTokens;
import com.zhiguang.be.auth.token.RefreshTokenClaims;
import com.zhiguang.be.auth.token.RsaJwtService;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AuthJwtConfigurationTest {

    @Test
    void configuredKeyShouldIssueAndVerifyCurrentKidTokens() throws Exception {
        KeyPair currentPair = generateRsaPair();
        AuthJwtProperties properties = propertiesFor("kid-current", currentPair);
        AuthJwtConfiguration configuration = new AuthJwtConfiguration();
        RsaKeyMaterial keyMaterial = configuration.rsaKeyMaterial(properties);
        JwtEncoder encoder = configuration.jwtEncoder(keyMaterial, properties);
        JwtDecoder accessDecoder = configuration.accessJwtDecoder(keyMaterial, properties);
        JwtDecoder tokenDecoder = configuration.tokenJwtDecoder(keyMaterial, properties);
        RsaJwtService jwtService = new RsaJwtService(encoder, tokenDecoder, properties);

        AuthTokens tokens = jwtService.issueTokens("10001", "USER");
        Jwt accessJwt = accessDecoder.decode(tokens.accessToken());
        RefreshTokenClaims refreshClaims = jwtService.verifyRefreshToken(tokens.refreshToken());

        assertEquals("kid-current", accessJwt.getHeaders().get("kid"));
        assertEquals("10001", accessJwt.getSubject());
        assertEquals("10001", refreshClaims.userId());
        assertNotNull(refreshClaims.jti());
    }

    @Test
    void decoderShouldAcceptHistoricalKidDuringKeyRotation() throws Exception {
        KeyPair currentPair = generateRsaPair();
        KeyPair previousPair = generateRsaPair();
        AuthJwtConfiguration configuration = new AuthJwtConfiguration();
        AuthJwtProperties issuerProperties = propertiesFor("kid-previous", previousPair);
        RsaKeyMaterial previousMaterial = configuration.rsaKeyMaterial(issuerProperties);
        RsaJwtService previousJwtService = new RsaJwtService(
                configuration.jwtEncoder(previousMaterial, issuerProperties),
                configuration.tokenJwtDecoder(previousMaterial, issuerProperties),
                issuerProperties
        );
        AuthTokens oldTokens = previousJwtService.issueTokens("10002", "USER");

        AuthJwtProperties rotatedProperties = propertiesFor("kid-current", currentPair);
        AuthJwtProperties.VerificationKey verificationKey = new AuthJwtProperties.VerificationKey();
        verificationKey.setKeyId("kid-previous");
        verificationKey.setPublicKey(toPublicPem((RSAPublicKey) previousPair.getPublic()));
        rotatedProperties.getVerificationKeys().add(verificationKey);

        RsaKeyMaterial rotatedMaterial = configuration.rsaKeyMaterial(rotatedProperties);
        JwtDecoder accessDecoder = configuration.accessJwtDecoder(rotatedMaterial, rotatedProperties);
        JwtDecoder tokenDecoder = configuration.tokenJwtDecoder(rotatedMaterial, rotatedProperties);
        RsaJwtService rotatedJwtService = new RsaJwtService(
                configuration.jwtEncoder(rotatedMaterial, rotatedProperties),
                tokenDecoder,
                rotatedProperties
        );

        Jwt oldAccessJwt = accessDecoder.decode(oldTokens.accessToken());
        RefreshTokenClaims oldRefreshClaims = rotatedJwtService.verifyRefreshToken(oldTokens.refreshToken());
        AuthTokens newTokens = rotatedJwtService.issueTokens("10003", "ADMIN");
        Jwt newAccessJwt = accessDecoder.decode(newTokens.accessToken());

        assertEquals("kid-previous", oldAccessJwt.getHeaders().get("kid"));
        assertEquals("10002", oldRefreshClaims.userId());
        assertEquals("kid-current", newAccessJwt.getHeaders().get("kid"));
        assertEquals("10003", newAccessJwt.getSubject());
    }

    @Test
    void decoderShouldRejectUnknownKid() throws Exception {
        KeyPair currentPair = generateRsaPair();
        KeyPair unknownPair = generateRsaPair();
        AuthJwtConfiguration configuration = new AuthJwtConfiguration();
        AuthJwtProperties unknownIssuerProperties = propertiesFor("kid-unknown", unknownPair);
        RsaKeyMaterial unknownMaterial = configuration.rsaKeyMaterial(unknownIssuerProperties);
        RsaJwtService unknownJwtService = new RsaJwtService(
                configuration.jwtEncoder(unknownMaterial, unknownIssuerProperties),
                configuration.tokenJwtDecoder(unknownMaterial, unknownIssuerProperties),
                unknownIssuerProperties
        );
        AuthTokens unknownTokens = unknownJwtService.issueTokens("10004", "USER");

        AuthJwtProperties currentProperties = propertiesFor("kid-current", currentPair);
        RsaKeyMaterial currentMaterial = configuration.rsaKeyMaterial(currentProperties);
        JwtDecoder accessDecoder = configuration.accessJwtDecoder(currentMaterial, currentProperties);

        assertThrows(JwtException.class, () -> accessDecoder.decode(unknownTokens.accessToken()));
    }

    @Test
    void decoderShouldRejectTokenWithoutKid() throws Exception {
        KeyPair currentPair = generateRsaPair();
        AuthJwtConfiguration configuration = new AuthJwtConfiguration();
        AuthJwtProperties properties = propertiesFor("kid-current", currentPair);
        RsaKeyMaterial keyMaterial = configuration.rsaKeyMaterial(properties);
        JwtDecoder accessDecoder = configuration.accessJwtDecoder(keyMaterial, properties);
        String tokenWithoutKid = accessTokenWithoutKid(jwtEncoderWithoutKid(keyMaterial), properties);

        assertThrows(JwtException.class, () -> accessDecoder.decode(tokenWithoutKid));
    }

    @Test
    void verificationKeysShouldRejectIncompleteEntry() throws Exception {
        KeyPair currentPair = generateRsaPair();
        AuthJwtProperties properties = propertiesFor("kid-current", currentPair);
        AuthJwtProperties.VerificationKey incompleteKey = new AuthJwtProperties.VerificationKey();
        incompleteKey.setKeyId("kid-previous");
        properties.getVerificationKeys().add(incompleteKey);
        AuthJwtConfiguration configuration = new AuthJwtConfiguration();

        assertThrows(IllegalStateException.class, () -> configuration.rsaKeyMaterial(properties));
    }

    @Test
    void verificationKeysShouldRejectCurrentKidCollision() throws Exception {
        KeyPair currentPair = generateRsaPair();
        AuthJwtProperties properties = propertiesFor("kid-current", currentPair);
        AuthJwtProperties.VerificationKey currentKey = new AuthJwtProperties.VerificationKey();
        currentKey.setKeyId("kid-current");
        currentKey.setPublicKey(toPublicPem((RSAPublicKey) currentPair.getPublic()));
        properties.getVerificationKeys().add(currentKey);
        AuthJwtConfiguration configuration = new AuthJwtConfiguration();

        assertThrows(IllegalStateException.class, () -> configuration.rsaKeyMaterial(properties));
    }

    @Test
    void verificationKeysShouldRejectDuplicateKid() throws Exception {
        KeyPair currentPair = generateRsaPair();
        KeyPair previousPair = generateRsaPair();
        AuthJwtProperties properties = propertiesFor("kid-current", currentPair);
        AuthJwtProperties.VerificationKey firstKey = verificationKey("kid-previous", previousPair);
        AuthJwtProperties.VerificationKey duplicateKey = verificationKey("kid-previous", previousPair);
        properties.getVerificationKeys().add(firstKey);
        properties.getVerificationKeys().add(duplicateKey);
        AuthJwtConfiguration configuration = new AuthJwtConfiguration();

        assertThrows(IllegalStateException.class, () -> configuration.rsaKeyMaterial(properties));
    }

    @Test
    void missingKeyShouldFailWhenEphemeralKeysAreDisabled() {
        AuthJwtProperties properties = new AuthJwtProperties();
        properties.setAllowEphemeralKeys(false);
        AuthJwtConfiguration configuration = new AuthJwtConfiguration();

        assertThrows(IllegalStateException.class, () -> configuration.rsaKeyMaterial(properties));
    }

    private static AuthJwtProperties propertiesFor(String keyId, KeyPair keyPair) {
        AuthJwtProperties properties = new AuthJwtProperties();
        properties.setKeyId(keyId);
        properties.setPublicKey(toPublicPem((RSAPublicKey) keyPair.getPublic()));
        properties.setPrivateKey(toPrivatePem((RSAPrivateKey) keyPair.getPrivate()));
        properties.setAllowEphemeralKeys(false);
        return properties;
    }

    private static KeyPair generateRsaPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return generator.generateKeyPair();
    }

    private static JwtEncoder jwtEncoderWithoutKid(RsaKeyMaterial keyMaterial) {
        RSAKey rsaKey = new RSAKey.Builder(keyMaterial.publicKey())
                .privateKey(keyMaterial.privateKey())
                .build();
        JWKSource<SecurityContext> jwkSource = (selector, context) -> selector.select(new JWKSet(rsaKey));
        return new NimbusJwtEncoder(jwkSource);
    }

    private static String accessTokenWithoutKid(JwtEncoder encoder, AuthJwtProperties properties) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(properties.getIssuer())
                .subject("10005")
                .issuedAt(now)
                .expiresAt(now.plus(15, ChronoUnit.MINUTES))
                .id("no-kid-token")
                .claim("token_type", "access")
                .claim("role", "USER")
                .build();
        JwsHeader header = JwsHeader.with(SignatureAlgorithm.RS256)
                .type("JWT")
                .build();
        return encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }

    private static AuthJwtProperties.VerificationKey verificationKey(String keyId, KeyPair keyPair) {
        AuthJwtProperties.VerificationKey verificationKey = new AuthJwtProperties.VerificationKey();
        verificationKey.setKeyId(keyId);
        verificationKey.setPublicKey(toPublicPem((RSAPublicKey) keyPair.getPublic()));
        return verificationKey;
    }

    private static String toPublicPem(RSAPublicKey publicKey) {
        return "-----BEGIN PUBLIC KEY-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(publicKey.getEncoded())
                + "\n-----END PUBLIC KEY-----";
    }

    private static String toPrivatePem(RSAPrivateKey privateKey) {
        return "-----BEGIN PRIVATE KEY-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(privateKey.getEncoded())
                + "\n-----END PRIVATE KEY-----";
    }
}
