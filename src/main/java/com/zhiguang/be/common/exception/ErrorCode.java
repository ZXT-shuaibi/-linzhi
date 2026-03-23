package com.zhiguang.be.common.exception;

/**
 * 枚举说明。
 */
public enum ErrorCode {
    VALIDATION_ERROR("COMMON_400_VALIDATION", "Parameter validation failed"),
    BAD_REQUEST("COMMON_400_BAD_REQUEST", "Bad request"),
    UNAUTHORIZED("AUTH_401_UNAUTHORIZED", "Authentication failed"),
    LOGIN_BLOCKED("AUTH_403_LOGIN_BLOCKED", "Login is blocked due to account risk"),
    FORBIDDEN("COMMON_403_FORBIDDEN", "Access denied"),
    INVALID_REFRESH_TOKEN("AUTH_401_REFRESH_REPLAY", "Refresh token invalid or revoked"),
    PHONE_EXISTS("AUTH_409_PHONE_EXISTS", "Phone already registered"),
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
     * 方法说明。
     */
    public String code() {
        return code;
    }

    /**
     * 方法说明。
     */
    public String defaultMessage() {
        return defaultMessage;
    }
}

