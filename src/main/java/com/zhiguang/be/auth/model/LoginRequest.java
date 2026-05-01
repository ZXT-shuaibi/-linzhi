package com.zhiguang.be.auth.model;

import com.fasterxml.jackson.annotation.JsonAlias;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 登录请求。
 * 支持手机号 + 密码或手机号 + 短信验证码两种登录方式。
 *
 * @param identifier 登录手机号，兼容 JSON 字段 phone
 * @param password 登录密码，密码登录时必填
 * @param channel 登录渠道，如 APP/H5/MP
 * @param smsCode 短信验证码，验证码登录时必填
 * @param captchaCode 达到风控阈值后提交的补充验证码，仅密码登录使用
 */
public record LoginRequest(
        @JsonAlias("phone")
        @NotBlank(message = "手机号不能为空")
        @Pattern(regexp = "^1\\d{10}$", message = "手机号格式不正确")
        String identifier,
        @Size(min = 8, max = 128, message = "密码长度必须在 8 到 128 位之间")
        String password,
        @Size(max = 32, message = "渠道长度不能超过 32 位")
        String channel,
        @Pattern(regexp = "^\\d{6}$", message = "短信验证码必须为 6 位数字")
        String smsCode,
        @Pattern(regexp = "^\\d{6}$", message = "登录验证码必须为 6 位数字")
        String captchaCode
) {
    /**
     * 兼容旧测试和旧客户端构造方式。
     */
    public LoginRequest(String identifier, String password, String channel, String captchaCode) {
        this(identifier, password, channel, null, captchaCode);
    }
}
