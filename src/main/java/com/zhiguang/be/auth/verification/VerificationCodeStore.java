package com.zhiguang.be.auth.verification;

import java.time.Duration;

/**
 * 验证码存储接口。
 * 负责保存一次性验证码、校验正确性并在成功后销毁记录。
 */
public interface VerificationCodeStore {

    /**
     * 保存验证码并设置有效期。
     *
     * @param scene 场景编码
     * @param phone 手机号
     * @param code 验证码
     * @param ttl 有效期
     * @param maxAttempts 最大尝试次数
     */
    void saveCode(String scene, String phone, String code, Duration ttl, int maxAttempts);

    /**
     * 校验验证码并返回状态信息。
     *
     * @param scene 场景编码
     * @param phone 手机号
     * @param code 用户输入的验证码
     * @return 校验结果
     */
    VerificationCheckResult verify(String scene, String phone, String code);

    /**
     * 使验证码失效。
     *
     * @param scene 场景编码
     * @param phone 手机号
     */
    void invalidate(String scene, String phone);
}
