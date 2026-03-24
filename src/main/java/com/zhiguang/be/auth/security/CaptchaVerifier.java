package com.zhiguang.be.auth.security;

/**
 * 验证码验证器接口。
 */
public interface CaptchaVerifier {

    /**
     * 验证验证码。
     *
     * @param token 验证码令牌
     * @return true 验证通过
     */
    boolean verify(String token);
}
