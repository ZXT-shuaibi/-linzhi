package com.zhiguang.be.common.exception;

/**
 * 系统统一错误码枚举。
 * 定义服务层和接口层会使用到的标准错误编码及默认文案。
 */
public enum ErrorCode {
    VALIDATION_ERROR("COMMON_400_VALIDATION", "参数校验失败"),
    BAD_REQUEST("COMMON_400_BAD_REQUEST", "请求参数错误"),
    CONFLICT("COMMON_409_CONFLICT", "资源状态冲突"),
    UNAUTHORIZED("AUTH_401_UNAUTHORIZED", "认证失败"),
    LOGIN_BLOCKED("AUTH_403_LOGIN_BLOCKED", "当前账号登录受限"),
    FORBIDDEN("COMMON_403_FORBIDDEN", "访问被拒绝"),
    INVALID_REFRESH_TOKEN("AUTH_401_REFRESH_REPLAY", "刷新令牌已失效"),
    PHONE_EXISTS("AUTH_409_PHONE_EXISTS", "手机号已注册"),
    ACCOUNT_EXISTS("AUTH_409_ACCOUNT_EXISTS", "账号已存在"),
    ACCOUNT_NOT_FOUND("AUTH_404_ACCOUNT_NOT_FOUND", "账号未注册"),
    CAPTCHA_REQUIRED("AUTH_400_CAPTCHA_REQUIRED", "需要完成人机验证"),
    INVALID_CAPTCHA("AUTH_400_INVALID_CAPTCHA", "人机验证失败"),
    INVALID_SMS_CODE("AUTH_400_SMS_INVALID", "验证码错误或已过期"),
    VERIFICATION_NOT_FOUND("AUTH_404_VERIFICATION_NOT_FOUND", "验证码不存在"),
    VERIFICATION_EXPIRED("AUTH_400_VERIFICATION_EXPIRED", "验证码已过期"),
    VERIFICATION_MISMATCH("AUTH_400_VERIFICATION_MISMATCH", "验证码错误"),
    VERIFICATION_TOO_MANY_ATTEMPTS("AUTH_429_VERIFICATION_ATTEMPTS", "验证码尝试次数过多"),
    NOT_FOUND("COMMON_404_NOT_FOUND", "资源不存在"),
    RATE_LIMITED("AUTH_429_RATE_LIMIT", "操作过于频繁"),
    TRADE_ACTIVITY_NOT_FOUND("TRADE_404_ACTIVITY_NOT_FOUND", "活动不存在"),
    TRADE_ACTIVITY_NOT_ACTIVE("TRADE_409_ACTIVITY_NOT_ACTIVE", "活动当前不可下单"),
    TRADE_STOCK_EMPTY("TRADE_409_STOCK_EMPTY", "库存不足"),
    TRADE_DUPLICATE_ORDER("TRADE_409_DUPLICATE_ORDER", "当前活动已下单，请勿重复提交"),
    TRADE_ORDER_NOT_FOUND("TRADE_404_ORDER_NOT_FOUND", "订单不存在"),
    TRADE_ORDER_EXPIRED("TRADE_409_ORDER_EXPIRED", "订单已超时关闭"),
    TRADE_ORDER_CLOSED("TRADE_409_ORDER_CLOSED", "订单已关闭"),
    TRADE_ORDER_PAID("TRADE_409_ORDER_PAID", "订单已支付"),
    INTERNAL_ERROR("COMMON_500_INTERNAL", "系统内部错误");

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
