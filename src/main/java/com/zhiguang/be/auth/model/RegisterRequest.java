package com.zhiguang.be.auth.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 注册请求。
 * 当前流程要求手机号、短信验证码、密码和二次确认密码。
 * 账号和昵称可选；不传时服务端会基于手机号生成默认值。
 *
 * @param phone 手机号
 * @param account 登录账号，可选
 * @param password 密码
 * @param confirmPassword 确认密码
 * @param nickname 昵称，可选
 * @param smsCode 短信验证码
 */
public record RegisterRequest(
        @NotBlank(message = "手机号不能为空")
        @Pattern(regexp = "^1\\d{10}$", message = "手机号格式不正确")
        String phone,
        @Pattern(regexp = "^[A-Za-z0-9_]{4,32}$", message = "账号必须为 4 到 32 位字母、数字或下划线")
        String account,
        @NotBlank(message = "密码不能为空")
        @Size(min = 8, max = 128, message = "密码长度必须在 8 到 128 位之间")
        @Pattern(regexp = "^(?=.*[A-Za-z])(?=.*\\d).{8,}$", message = "密码必须同时包含字母和数字")
        String password,
        @NotBlank(message = "确认密码不能为空")
        @Size(min = 8, max = 128, message = "确认密码长度必须在 8 到 128 位之间")
        String confirmPassword,
        @Size(max = 64, message = "昵称长度不能超过 64 位")
        String nickname,
        @NotBlank(message = "验证码不能为空")
        @Pattern(regexp = "^\\d{6}$", message = "验证码必须为 6 位数字")
        String smsCode
) {
    /**
     * 兼容旧构造方式；旧调用未显式传确认密码时默认等于 password。
     */
    public RegisterRequest(String phone, String account, String password, String nickname, String smsCode) {
        this(phone, account, password, password, nickname, smsCode);
    }
}
