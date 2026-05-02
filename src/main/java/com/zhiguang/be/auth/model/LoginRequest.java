package com.zhiguang.be.auth.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 登录请求。
 * 支持使用手机号配合密码或短信验证码登录，在命中风控阈值后需要补充登录验证码。
 *
 * @param identifier 登录手机号
 * @param password 登录密码；短信验证码登录时可为空
 * @param smsCode 短信验证码登录时提交的验证码
 * @param channel 登录渠道，如 APP/H5/MP
 * @param captchaCode 达到风控阈值后提交的登录验证码
 */
public record LoginRequest(
        @NotBlank(message = "登录手机号不能为空")
        @Pattern(regexp = "^1\\d{10}$", message = "登录手机号格式不正确")
        String identifier,
        @Size(min = 8, max = 128, message = "密码长度必须在 8 到 128 位之间")
        String password,
        @Pattern(regexp = "^\\d{6}$", message = "短信验证码必须为 6 位数字")
        String smsCode,
        @Size(max = 32, message = "渠道长度不能超过 32 位")
        String channel,
        @Pattern(regexp = "^\\d{6}$", message = "登录验证码必须为 6 位数字")
        String captchaCode
) {
    public LoginRequest(String identifier, String password, String channel, String captchaCode) {
        this(identifier, password, null, channel, captchaCode);
    }
}
