package com.zhiguang.be.auth.security;

/**
 * 验证码校验接口。
 * 用于登录风险场景下校验图形验证码或第三方行为验证码。
 */
public interface CaptchaVerifier {

    /**
     * 校验验证码令牌是否有效。
     *
     * @param token 验证码令牌
     * @return 校验通过返回 true，否则返回 false
     */
    boolean verify(String token);
}