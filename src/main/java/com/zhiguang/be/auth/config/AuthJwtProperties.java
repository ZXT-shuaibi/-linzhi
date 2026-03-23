package com.zhiguang.be.auth.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "security.jwt")
/**
 * 类说明。
 */
public class AuthJwtProperties {

    private String issuer = "zhiguang-be";
    private String keyId = "zhiguang-rsa";
    private long accessTokenTtlMinutes = 15;
    private long refreshTokenTtlDays = 7;
    private String publicKey = "";
    private String privateKey = "";

    /**
     * 方法说明。
     */
    public String getIssuer() {
        return issuer;
    }

    /**
     * 方法说明。
     */
    public void setIssuer(String issuer) {
        this.issuer = issuer;
    }

    /**
     * 方法说明。
     */
    public String getKeyId() {
        return keyId;
    }

    /**
     * 方法说明。
     */
    public void setKeyId(String keyId) {
        this.keyId = keyId;
    }

    /**
     * 方法说明。
     */
    public long getAccessTokenTtlMinutes() {
        return accessTokenTtlMinutes;
    }

    /**
     * 方法说明。
     */
    public void setAccessTokenTtlMinutes(long accessTokenTtlMinutes) {
        this.accessTokenTtlMinutes = accessTokenTtlMinutes;
    }

    /**
     * 方法说明。
     */
    public long getRefreshTokenTtlDays() {
        return refreshTokenTtlDays;
    }

    /**
     * 方法说明。
     */
    public void setRefreshTokenTtlDays(long refreshTokenTtlDays) {
        this.refreshTokenTtlDays = refreshTokenTtlDays;
    }

    /**
     * 方法说明。
     */
    public String getPublicKey() {
        return publicKey;
    }

    /**
     * 方法说明。
     */
    public void setPublicKey(String publicKey) {
        this.publicKey = publicKey;
    }

    /**
     * 方法说明。
     */
    public String getPrivateKey() {
        return privateKey;
    }

    /**
     * 方法说明。
     */
    public void setPrivateKey(String privateKey) {
        this.privateKey = privateKey;
    }
}
