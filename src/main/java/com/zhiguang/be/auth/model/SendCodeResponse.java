package com.zhiguang.be.auth.model;

import com.zhiguang.be.auth.verification.VerificationScene;

/**
 * 发送验证码响应。
 * 返回已规范化的手机号、验证码场景以及有效期秒数。
 *
 * @param phone 目标手机号
 * @param scene 验证码场景
 * @param expireSeconds 有效期秒数
 */
public record SendCodeResponse(
        String phone,
        VerificationScene scene,
        String code,
        int expireSeconds,
        int resendAfterSeconds
) {
    public SendCodeResponse(String phone, VerificationScene scene, String code, int expireSeconds) {
        this(phone, scene, code, expireSeconds, expireSeconds);
    }
}
