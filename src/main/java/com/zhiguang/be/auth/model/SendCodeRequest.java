package com.zhiguang.be.auth.model;

import com.zhiguang.be.auth.verification.VerificationScene;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 发送验证码请求。
 * 开发态下可使用手机号或账号作为发送目标，由后端解析出真实手机号并完成验证码下发。
 *
 * @param identifier 发送目标，注册场景为手机号，登录和重置密码场景支持手机号或账号
 * @param scene 验证码场景
 */
public record SendCodeRequest(
        @NotBlank(message = "发送目标不能为空")
        @Size(min = 4, max = 32, message = "发送目标长度必须在 4 到 32 位之间")
        @Pattern(regexp = "^(1\\d{10}|[A-Za-z0-9_]{4,32})$", message = "发送目标必须为手机号或账号")
        String identifier,
        @NotNull(message = "验证码场景不能为空")
        VerificationScene scene
) {
}
