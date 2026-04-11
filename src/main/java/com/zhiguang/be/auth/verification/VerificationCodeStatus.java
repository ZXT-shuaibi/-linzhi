package com.zhiguang.be.auth.verification;

/**
 * 验证码校验结果状态。
 * 用于区分成功、未命中、错误以及尝试超限等分支。
 */
public enum VerificationCodeStatus {
    SUCCESS,
    NOT_FOUND,
    EXPIRED,
    MISMATCH,
    TOO_MANY_ATTEMPTS
}
