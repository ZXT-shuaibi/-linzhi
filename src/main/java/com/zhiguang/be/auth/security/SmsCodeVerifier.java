package com.zhiguang.be.auth.security;

/**
 * 短信验证码验证器接口。
 */
public interface SmsCodeVerifier {

    /**
     * 验证短信验证码。
     *
     * @param phone 手机号
     * @param code 验证码
     * @return true 验证通过
     */
    boolean verify(String phone, String code);
}
