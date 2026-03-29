package com.zhiguang.be.auth.model;

/**
 * 发送短信验证码响应。
 * 返回生成的6位数字验证码（个人项目，不涉及真实短信发送）。
 *
 * @param code 6位数字验证码
 */
public record SendSmsCodeResponse(
        String code
) {
}
