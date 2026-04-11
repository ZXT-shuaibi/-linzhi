package com.zhiguang.be.auth.model;

/**
 * 认证用户实体。
 * 用于承载登录、注册和密码重置流程中需要的最小账户信息。
 */
public class AuthUserEntity {

    private final String userId;
    private final String phone;
    private final String account;
    private final String nickname;
    private final String passwordHash;

    /**
     * 构造认证用户实体。
     *
     * @param userId 用户唯一 ID
     * @param phone 手机号
     * @param account 登录账号
     * @param nickname 昵称
     * @param passwordHash 加密后的密码哈希
     */
    public AuthUserEntity(String userId, String phone, String account, String nickname, String passwordHash) {
        this.userId = userId;
        this.phone = phone;
        this.account = account;
        this.nickname = nickname;
        this.passwordHash = passwordHash;
    }

    /**
     * 返回用户唯一 ID。
     *
     * @return 用户唯一 ID
     */
    public String userId() {
        return userId;
    }

    /**
     * 返回手机号。
     *
     * @return 手机号
     */
    public String phone() {
        return phone;
    }

    /**
     * 返回登录账号。
     *
     * @return 登录账号
     */
    public String account() {
        return account;
    }

    /**
     * 返回昵称。
     *
     * @return 昵称
     */
    public String nickname() {
        return nickname;
    }

    /**
     * 返回密码哈希。
     *
     * @return 密码哈希
     */
    public String passwordHash() {
        return passwordHash;
    }

    /**
     * 基于当前实体创建一份仅替换密码哈希的新对象。
     *
     * @param newPasswordHash 新密码哈希
     * @return 新的用户实体
     */
    public AuthUserEntity withPasswordHash(String newPasswordHash) {
        return new AuthUserEntity(this.userId, this.phone, this.account, this.nickname, newPasswordHash);
    }
}
