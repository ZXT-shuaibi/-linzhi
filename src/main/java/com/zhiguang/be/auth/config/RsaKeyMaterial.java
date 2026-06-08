package com.zhiguang.be.auth.config;

import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * RSA 密钥材料对象。
 * 用于同时封装 JWT 签发和校验所需的公钥与私钥。
 */
public class RsaKeyMaterial {

    private final RSAPublicKey publicKey;
    private final RSAPrivateKey privateKey;
    private final Map<String, RSAPublicKey> verificationKeys;

    /**
     * 构造 RSA 密钥材料对象。
     *
     * @param publicKey RSA 公钥
     * @param privateKey RSA 私钥
     */
    public RsaKeyMaterial(RSAPublicKey publicKey, RSAPrivateKey privateKey) {
        this(publicKey, privateKey, Collections.emptyMap());
    }

    /**
     * 构造 RSA 密钥材料对象。
     * publicKey/privateKey 代表当前签发密钥，verificationKeys 代表历史 kid 对应的验签公钥集合。
     *
     * @param publicKey 当前签发密钥的 RSA 公钥
     * @param privateKey 当前签发密钥的 RSA 私钥
     * @param verificationKeys 历史 kid 到 RSA 公钥的映射
     */
    public RsaKeyMaterial(RSAPublicKey publicKey, RSAPrivateKey privateKey, Map<String, RSAPublicKey> verificationKeys) {
        this.publicKey = publicKey;
        this.privateKey = privateKey;
        this.verificationKeys = Collections.unmodifiableMap(new LinkedHashMap<>(verificationKeys == null
                ? Collections.emptyMap()
                : verificationKeys));
    }

    /**
     * 获取 RSA 公钥。
     *
     * @return RSA 公钥对象
     */
    public RSAPublicKey publicKey() {
        return publicKey;
    }

    /**
     * 获取 RSA 私钥。
     *
     * @return RSA 私钥对象
     */
    public RSAPrivateKey privateKey() {
        return privateKey;
    }

    /**
     * 返回全部历史验签公钥。
     * 当前签发公钥不在这里重复保存，调用方需要把当前 keyId 与 publicKey 共同加入 JWKSet。
     *
     * @return kid 到 RSA 公钥的不可变映射
     */
    public Map<String, RSAPublicKey> verificationKeys() {
        return verificationKeys;
    }
}
