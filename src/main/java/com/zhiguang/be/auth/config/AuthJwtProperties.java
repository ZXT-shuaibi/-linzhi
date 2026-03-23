package com.zhiguang.be.auth.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "security.jwt")
/**
 * JWT 配置属性。
 */
public class AuthJwtProperties {

    private String issuer = "zhiguang-be";
    private String keyId = "zhiguang-rsa";
    private long accessTokenTtlMinutes = 15;
    private long refreshTokenTtlDays = 7;
    private String publicKey = "";
    private String privateKey = "";
    private boolean allowEphemeralKeys = false;

    /**
     * 获取发行方。
     */
    public String getIssuer() {
        return issuer;
    }

    /**
     * 设置发行方。
     */
    public void setIssuer(String issuer) {
        this.issuer = issuer;
    }

    /**
     * 获取 keyId。
     */
    public String getKeyId() {
        return keyId;
    }

    /**
     * 设置 keyId。
     */
    public void setKeyId(String keyId) {
        this.keyId = keyId;
    }

    /**
     * 获取 Access Token TTL（分钟）。
     */
    public long getAccessTokenTtlMinutes() {
        return accessTokenTtlMinutes;
    }

    /**
     * 设置 Access Token TTL（分钟）。
     */
    public void setAccessTokenTtlMinutes(long accessTokenTtlMinutes) {
        this.accessTokenTtlMinutes = accessTokenTtlMinutes;
    }

    /**
     * 获取 Refresh Token TTL（天）。
     */
    public long getRefreshTokenTtlDays() {
        return refreshTokenTtlDays;
    }

    /**
     * 设置 Refresh Token TTL（天）。
     */
    public void setRefreshTokenTtlDays(long refreshTokenTtlDays) {
        this.refreshTokenTtlDays = refreshTokenTtlDays;
    }

    /**
     * 获取公钥内容。
     */
    public String getPublicKey() {
        return publicKey;
    }

    /**
     * 设置公钥内容。
     */
    public void setPublicKey(String publicKey) {
        this.publicKey = publicKey;
    }

    /**
     * 获取私钥内容。
     */
    public String getPrivateKey() {
        return privateKey;
    }

    /**
     * 设置私钥内容。
     */
    public void setPrivateKey(String privateKey) {
        this.privateKey = privateKey;
    }

    /**
     * 是否允许在密钥缺失时生成临时密钥。
     */
    public boolean isAllowEphemeralKeys() {
        return allowEphemeralKeys;
    }

    /**
     * 设置是否允许临时密钥。
     */
    public void setAllowEphemeralKeys(boolean allowEphemeralKeys) {
        this.allowEphemeralKeys = allowEphemeralKeys;
    }
}