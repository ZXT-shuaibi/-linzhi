package com.zhiguang.be.auth.config;

import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;

/**
 * RSA 密钥材料对象。
 * 用于同时封装 JWT 签发和校验所需的公钥与私钥。
 */
public class RsaKeyMaterial {

    private final RSAPublicKey publicKey;
    private final RSAPrivateKey privateKey;

    /**
     * 构造 RSA 密钥材料对象。
     *
     * @param publicKey RSA 公钥
     * @param privateKey RSA 私钥
     */
    public RsaKeyMaterial(RSAPublicKey publicKey, RSAPrivateKey privateKey) {
        this.publicKey = publicKey;
        this.privateKey = privateKey;
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
}