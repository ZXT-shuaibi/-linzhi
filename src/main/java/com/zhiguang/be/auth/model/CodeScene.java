package com.zhiguang.be.auth.model;

/**
 * 验证码使用场景。
 */
public enum CodeScene {
    /**
     * 注册场景
     */
    REGISTER,

    /**
     * 登录场景
     */
    LOGIN,

    /**
     * 重置密码场景
     */
    RESET_PASSWORD
}
