package com.zhiguang.be.auth.model;

import com.zhiguang.be.auth.verification.VerificationScene;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/**
 * 发送验证码请求。
 * 开发态下使用手机号作为发送目标。
 *
 * @param identifier 发送目标手机号
 * @param scene 验证码场景
 */
public record SendCodeRequest(
        @NotBlank(message = "发送目标不能为空")
        @Pattern(regexp = "^1\\d{10}$", message = "发送目标必须为合法手机号")
        String identifier,
        @NotNull(message = "验证码场景不能为空")
        VerificationScene scene
) {
}
