package com.zhiguang.be.auth.security;

import java.util.regex.Pattern;

/**
 * 认证标识校验工具。
 * 参考 zhiguang 的做法，把手机号和账号的正则校验统一收口到一个入口。
 */
public final class IdentifierValidator {

    private static final Pattern PHONE_PATTERN = Pattern.compile("^1\\d{10}$");
    private static final Pattern ACCOUNT_PATTERN = Pattern.compile("^[A-Za-z0-9_]{4,32}$");

    /**
     * 私有构造函数，禁止外部实例化。
     */
    private IdentifierValidator() {
    }

    /**
     * 校验手机号格式。
     *
     * @param phone 手机号
     * @return 匹配手机号格式返回 true
     */
    public static boolean isValidPhone(String phone) {
        return phone != null && PHONE_PATTERN.matcher(phone).matches();
    }

    /**
     * 校验账号格式。
     *
     * @param account 登录账号
     * @return 匹配账号格式返回 true
     */
    public static boolean isValidAccount(String account) {
        return account != null && ACCOUNT_PATTERN.matcher(account).matches();
    }

    /**
     * 校验登录标识是否为合法手机号或账号。
     *
     * @param identifier 登录标识
     * @return 是合法手机号或账号返回 true
     */
    public static boolean isValidPhoneOrAccount(String identifier) {
        return isValidPhone(identifier) || isValidAccount(identifier);
    }
}
