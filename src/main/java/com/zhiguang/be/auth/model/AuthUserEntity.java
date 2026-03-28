package com.zhiguang.be.auth.model;

/**
 * 认证用户实体。
 * 用于承载注册和登录流程中需要的基础账户信息。
 */
public class AuthUserEntity {

    private final String userId;
    private final String phone;
    private final String nickname;
    private final String passwordHash;

    /**
     * 构造认证用户实体。
     *
     * @param userId 用户 ID
     * @param phone 手机号
     * @param nickname 昵称
     * @param passwordHash 加密后的密码摘要
     */
    public AuthUserEntity(String userId, String phone, String nickname, String passwordHash) {
        this.userId = userId;
        this.phone = phone;
        this.nickname = nickname;
        this.passwordHash = passwordHash;
    }

    /**
     * 获取用户 ID。
     *
     * @return 用户 ID
     */
    public String userId() {
        return userId;
    }

    /**
     * 获取手机号。
     *
     * @return 手机号
     */
    public String phone() {
        return phone;
    }

    /**
     * 获取昵称。
     *
     * @return 昵称
     */
    public String nickname() {
        return nickname;
    }

    /**
     * 获取密码哈希。
     *
     * @return 加密后的密码摘要
     */
    public String passwordHash() {
        return passwordHash;
    }

    /**
     * 基于当前实体创建一个替换了密码哈希的新实体。
     *
     * @param newPasswordHash 新密码哈希
     * @return 新的用户实体
     */
    public AuthUserEntity withPasswordHash(String newPasswordHash) {
        return new AuthUserEntity(this.userId, this.phone, this.nickname, newPasswordHash);
    }
}