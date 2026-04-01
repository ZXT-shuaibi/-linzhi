package com.zhiguang.be.auth.model;

/**
 * 发送验证码结果。
 */
public record SendCodeResult(
        boolean success,
        String message,
        String code
) {
}
