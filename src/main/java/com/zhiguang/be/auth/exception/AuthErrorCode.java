package com.zhiguang.be.auth.exception;

/**
 * 认证授权模块专用错误码。
 */
public enum AuthErrorCode {
    SMS_CODE_INVALID("AUTH_400_SMS_INVALID", "SMS code invalid or expired"),
    CAPTCHA_REQUIRED("AUTH_400_CAPTCHA_REQUIRED", "Captcha required due to multiple failed attempts"),
    USER_NOT_REGISTERED("AUTH_401_NOT_REGISTERED", "您当前未注册"),
    INVALID_REFRESH_TOKEN("AUTH_401_REFRESH_REPLAY", "Refresh token invalid or revoked"),
    LOGIN_BLOCKED("AUTH_403_LOGIN_BLOCKED", "Login is blocked due to account risk"),
    PHONE_EXISTS("AUTH_409_PHONE_EXISTS", "Phone already registered"),
    USERNAME_EXISTS("AUTH_409_USERNAME_EXISTS", "Username already registered");

    private final String code;
    private final String defaultMessage;

    AuthErrorCode(String code, String defaultMessage) {
        this.code = code;
        this.defaultMessage = defaultMessage;
    }

    /**
     * 获取错误码。
     */
    public String code() {
        return code;
    }

    /**
     * 获取默认错误消息。
     */
    public String defaultMessage() {
        return defaultMessage;
    }
}
