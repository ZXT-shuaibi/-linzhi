package com.zhiguang.be.auth.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

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
    private List<VerificationKey> verificationKeys = new ArrayList<>();

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

    /**
     * 返回仅用于验签的历史公钥配置。
     * 这些公钥不会参与新令牌签发，只用于支持 kid 滚动换钥期间的旧令牌验签。
     */
    public List<VerificationKey> getVerificationKeys() {
        return verificationKeys;
    }

    /**
     * 设置仅用于验签的历史公钥配置。
     */
    public void setVerificationKeys(List<VerificationKey> verificationKeys) {
        this.verificationKeys = verificationKeys == null ? new ArrayList<>() : verificationKeys;
    }

    /**
     * JWT 验签公钥配置项。
     */
    public static class VerificationKey {

        private String keyId;
        private String publicKey = "";

        public String getKeyId() {
            return keyId;
        }

        public void setKeyId(String keyId) {
            this.keyId = keyId;
        }

        public String getPublicKey() {
            return publicKey;
        }

        public void setPublicKey(String publicKey) {
            this.publicKey = publicKey;
        }

        /**
         * 判断该条历史公钥配置是否完全为空。
         * 空条目通常来自占位配置，可以安全忽略。
         */
        public boolean isBlank() {
            return (keyId == null || keyId.isBlank()) && (publicKey == null || publicKey.isBlank());
        }

        /**
         * 判断历史公钥配置是否具备完整的 kid 与公钥材料。
         * 只配置其中一项属于高风险换钥配置错误，调用方应 fail-fast。
         */
        public boolean hasCompleteKeyMaterial() {
            return keyId != null && !keyId.isBlank() && publicKey != null && !publicKey.isBlank();
        }
    }
}
