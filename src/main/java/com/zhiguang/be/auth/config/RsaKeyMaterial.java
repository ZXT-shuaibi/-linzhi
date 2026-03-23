package com.zhiguang.be.auth.config;

import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;

/**
 * 类说明。
 */
public class RsaKeyMaterial {

    private final RSAPublicKey publicKey;
    private final RSAPrivateKey privateKey;

    /**
     * 方法说明。
     */
    public RsaKeyMaterial(RSAPublicKey publicKey, RSAPrivateKey privateKey) {
        this.publicKey = publicKey;
        this.privateKey = privateKey;
    }

    /**
     * 方法说明。
     */
    public RSAPublicKey publicKey() {
        return publicKey;
    }

    /**
     * 方法说明。
     */
    public RSAPrivateKey privateKey() {
        return privateKey;
    }
}
