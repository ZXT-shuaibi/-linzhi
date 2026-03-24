package com.zhiguang.be.auth.security;

import org.springframework.stereotype.Component;

/**
 * 简单验证码验证器实现。
 * TODO: 集成实际的验证码服务（如 Google reCAPTCHA、阿里云验证码等）
 */
@Component
public class SimpleCaptchaVerifier implements CaptchaVerifier {

    @Override
    public boolean verify(String token) {
        // TODO: 实现实际的验证码验证逻辑
        return token != null && !token.isBlank();
    }
}
