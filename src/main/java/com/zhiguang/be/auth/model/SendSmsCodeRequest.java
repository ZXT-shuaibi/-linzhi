package com.zhiguang.be.auth.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * 发送短信验证码请求。
 * 用于用户注册或密码重置时请求发送验证码。
 *
 * @param phone 手机号，必须是有效的11位中国大陆手机号
 */
public record SendSmsCodeRequest(
        @NotBlank @Pattern(regexp = "^1\\d{10}$", message = "phone must be a valid mobile number") String phone
) {
}
