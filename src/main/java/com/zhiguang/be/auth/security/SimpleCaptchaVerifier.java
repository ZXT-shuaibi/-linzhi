package com.zhiguang.be.auth.security;

import org.springframework.stereotype.Component;

/**
 * 简化验证码校验实现。
 * 当前仅做非空判断，便于在尚未接入第三方验证码服务时完成联调。
 */
@Component
public class SimpleCaptchaVerifier implements CaptchaVerifier {

    /**
     * 校验验证码令牌是否非空。
     *
     * @param token 验证码令牌
     * @return 非空返回 true，否则返回 false
     */
    @Override
    public boolean verify(String token) {
        return token != null && !token.isBlank();
    }
}