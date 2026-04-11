package com.zhiguang.be.auth.verification;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Arrays;

/**
 * 验证码使用场景枚举。
 * 统一约束注册、登录和重置密码等场景使用的 Redis 键空间。
 */
public enum VerificationScene {
    REGISTER("register"),
    LOGIN("login"),
    PASSWORD_RESET("password_reset");

    private final String value;

    VerificationScene(String value) {
        this.value = value;
    }

    /**
     * 返回接口与 Redis 中使用的场景编码。
     *
     * @return 小写场景值
     */
    @JsonValue
    public String value() {
        return value;
    }

    /**
     * 按接口入参解析验证码场景。
     *
     * @param value 原始场景值
     * @return 对应的枚举值
     */
    @JsonCreator
    public static VerificationScene fromValue(String value) {
        return Arrays.stream(values())
                .filter(scene -> scene.value.equalsIgnoreCase(value))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unsupported verification scene: " + value));
    }
}
