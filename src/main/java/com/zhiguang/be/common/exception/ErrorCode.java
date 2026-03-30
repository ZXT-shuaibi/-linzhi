package com.zhiguang.be.common.exception;

/**
 * 系统统一错误码枚举。
 * 定义服务层和接口层会使用到的标准错误编码及默认文案。
 */
public enum ErrorCode {
    VALIDATION_ERROR("COMMON_400_VALIDATION", "Parameter validation failed"),
    BAD_REQUEST("COMMON_400_BAD_REQUEST", "Bad request"),
    UNAUTHORIZED("AUTH_401_UNAUTHORIZED", "Authentication failed"),
    USER_NOT_REGISTERED("AUTH_401_NOT_REGISTERED", "您当前未注册"),
    LOGIN_BLOCKED("AUTH_403_LOGIN_BLOCKED", "Login is blocked due to account risk"),
    FORBIDDEN("COMMON_403_FORBIDDEN", "Access denied"),
    INVALID_REFRESH_TOKEN("AUTH_401_REFRESH_REPLAY", "Refresh token invalid or revoked"),
    PHONE_EXISTS("AUTH_409_PHONE_EXISTS", "Phone already registered"),
    CAPTCHA_REQUIRED("AUTH_400_CAPTCHA_REQUIRED", "Captcha verification required"),
    INVALID_CAPTCHA("AUTH_400_INVALID_CAPTCHA", "Captcha verification failed"),
    NOT_FOUND("COMMON_404_NOT_FOUND", "Resource not found"),
    RATE_LIMITED("COMMON_429_RATE_LIMIT", "Too many requests"),
    INTERNAL_ERROR("COMMON_500_INTERNAL", "Internal server error");

    private final String code;
    private final String defaultMessage;

    ErrorCode(String code, String defaultMessage) {
        this.code = code;
        this.defaultMessage = defaultMessage;
    }

    /**
     * 返回错误码字符串。
     *
     * @return 形如 {@code AUTH_401_UNAUTHORIZED} 的错误码
     */
    public String code() {
        return code;
    }

    /**
     * 返回错误码对应的默认提示文案。
     *
     * @return 默认错误消息
     */
    public String defaultMessage() {
        return defaultMessage;
    }
}