package com.zhiguang.be.auth.security;

/**
 * 短信验证码校验接口。
 * 用于密码重置等流程中校验用户提交的短信验证码。
 */
public interface SmsCodeVerifier {

    /**
     * 校验指定手机号和验证码是否匹配。
     *
     * @param phone 手机号
     * @param code 验证码
     * @return 校验通过返回 true，否则返回 false
     */
    boolean verify(String phone, String code);
}