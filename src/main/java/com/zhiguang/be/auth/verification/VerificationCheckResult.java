package com.zhiguang.be.auth.verification;

/**
 * 验证码校验结果。
 *
 * @param status 校验状态
 * @param attempts 已尝试次数
 * @param maxAttempts 最大允许尝试次数
 */
public record VerificationCheckResult(
        VerificationCodeStatus status,
        int attempts,
        int maxAttempts
) {
    /**
     * 判断本次验证码校验是否成功。
     *
     * @return 成功返回 true，否则返回 false
     */
    public boolean isSuccess() {
        return status == VerificationCodeStatus.SUCCESS;
    }
}
