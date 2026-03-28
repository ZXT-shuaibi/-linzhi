package com.zhiguang.be.auth.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * JWT 配置属性。
 * 用于承接配置文件中的签发方、密钥标识和令牌有效期等参数。
 */
@ConfigurationProperties(prefix = "security.jwt")
public class AuthJwtProperties {

    private String issuer = "zhiguang-be";
    private String keyId = "zhiguang-rsa";
    private long accessTokenTtlMinutes = 15;
    private long refreshTokenTtlDays = 7;
    private String publicKey = "";
    private String privateKey = "";
    private boolean allowEphemeralKeys = false;

    /**
     * 获取 JWT 签发方。
     *
     * @return 签发方标识
     */
    public String getIssuer() {
        return issuer;
    }

    /**
     * 设置 JWT 签发方。
     *
     * @param issuer 签发方标识
     */
    public void setIssuer(String issuer) {
        this.issuer = issuer;
    }

    /**
     * 获取密钥标识。
     *
     * @return JWT header 中使用的 keyId
     */
    public String getKeyId() {
        return keyId;
    }

    /**
     * 设置密钥标识。
     *
     * @param keyId JWT header 中使用的 keyId
     */
    public void setKeyId(String keyId) {
        this.keyId = keyId;
    }

    /**
     * 获取访问令牌有效期。
     *
     * @return Access Token 有效期，单位为分钟
     */
    public long getAccessTokenTtlMinutes() {
        return accessTokenTtlMinutes;
    }

    /**
     * 设置访问令牌有效期。
     *
     * @param accessTokenTtlMinutes Access Token 有效期，单位为分钟
     */
    public void setAccessTokenTtlMinutes(long accessTokenTtlMinutes) {
        this.accessTokenTtlMinutes = accessTokenTtlMinutes;
    }

    /**
     * 获取刷新令牌有效期。
     *
     * @return Refresh Token 有效期，单位为天
     */
    public long getRefreshTokenTtlDays() {
        return refreshTokenTtlDays;
    }

    /**
     * 设置刷新令牌有效期。
     *
     * @param refreshTokenTtlDays Refresh Token 有效期，单位为天
     */
    public void setRefreshTokenTtlDays(long refreshTokenTtlDays) {
        this.refreshTokenTtlDays = refreshTokenTtlDays;
    }

    /**
     * 获取公钥内容。
     *
     * @return PEM 格式公钥文本
     */
    public String getPublicKey() {
        return publicKey;
    }

    /**
     * 设置公钥内容。
     *
     * @param publicKey PEM 格式公钥文本
     */
    public void setPublicKey(String publicKey) {
        this.publicKey = publicKey;
    }

    /**
     * 获取私钥内容。
     *
     * @return PEM 格式私钥文本
     */
    public String getPrivateKey() {
        return privateKey;
    }

    /**
     * 设置私钥内容。
     *
     * @param privateKey PEM 格式私钥文本
     */
    public void setPrivateKey(String privateKey) {
        this.privateKey = privateKey;
    }

    /**
     * 判断是否允许在缺少正式密钥时生成临时密钥。
     *
     * @return 允许生成临时密钥返回 true
     */
    public boolean isAllowEphemeralKeys() {
        return allowEphemeralKeys;
    }

    /**
     * 设置是否允许使用临时密钥。
     *
     * @param allowEphemeralKeys 是否允许生成临时密钥
     */
    public void setAllowEphemeralKeys(boolean allowEphemeralKeys) {
        this.allowEphemeralKeys = allowEphemeralKeys;
    }
}