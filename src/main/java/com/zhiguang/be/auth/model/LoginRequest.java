package com.zhiguang.be.auth.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 登录请求。
 * 支持使用手机号或账号进行登录，在命中风控阈值后需要补充登录验证码。
 *
 * @param identifier 登录标识，支持手机号或账号
 * @param password 登录密码
 * @param channel 登录渠道，如 APP/H5/MP
 * @param captchaCode 达到风控阈值后提交的登录验证码
 */
public record LoginRequest(
        @NotBlank(message = "登录标识不能为空")
        @Size(min = 4, max = 32, message = "登录标识长度必须在 4 到 32 位之间")
        @Pattern(regexp = "^(1\\d{10}|[A-Za-z0-9_]{4,32})$", message = "登录标识必须为手机号或账号")
        String identifier,
        @NotBlank(message = "密码不能为空")
        @Size(min = 8, max = 128, message = "密码长度必须在 8 到 128 位之间")
        String password,
        @Size(max = 32, message = "渠道长度不能超过 32 位")
        String channel,
        @Pattern(regexp = "^\\d{6}$", message = "登录验证码必须为 6 位数字")
        String captchaCode
) {
}
