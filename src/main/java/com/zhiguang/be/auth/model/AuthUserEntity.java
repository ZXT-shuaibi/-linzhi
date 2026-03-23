package com.zhiguang.be.auth.model;

/**
 * 类说明。
 */
public class AuthUserEntity {

    private final String userId;
    private final String phone;
    private final String nickname;
    private final String passwordHash;

    /**
     * 方法说明。
     */
    public AuthUserEntity(String userId, String phone, String nickname, String passwordHash) {
        this.userId = userId;
        this.phone = phone;
        this.nickname = nickname;
        this.passwordHash = passwordHash;
    }

    /**
     * 方法说明。
     */
    public String userId() {
        return userId;
    }

    /**
     * 方法说明。
     */
    public String phone() {
        return phone;
    }

    /**
     * 方法说明。
     */
    public String nickname() {
        return nickname;
    }

    /**
     * 方法说明。
     */
    public String passwordHash() {
        return passwordHash;
    }

    /**
     * 方法说明。
     */
    public AuthUserEntity withPasswordHash(String newPasswordHash) {
        return new AuthUserEntity(this.userId, this.phone, this.nickname, newPasswordHash);
    }
}
