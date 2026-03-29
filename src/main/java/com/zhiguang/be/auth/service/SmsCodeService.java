package com.zhiguang.be.auth.service;

/**
 * 短信验证码服务。
 */
public interface SmsCodeService {

    /**
     * 生成并存储短信验证码。
     *
     * @param phone 手机号
     * @return 6位验证码
     */
    String generate(String phone);

    /**
     * 校验短信验证码。
     *
     * @param phone 手机号
     * @param code 验证码
     * @return 是否有效
     */
    boolean verify(String phone, String code);
}
