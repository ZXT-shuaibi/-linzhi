package com.zhiguang.be.auth.model;

/**
 * 认证用户实体。
 * 用于承载登录、注册和密码重置流程中需要的最小账户信息。
 */
public class AuthUserEntity {

    private final String userId;
    private final String phone;
    private final String nickname;
    private final String passwordHash;
    private final String role;

    public AuthUserEntity(String userId, String phone, String nickname, String passwordHash, String role) {
        this.userId = userId;
        this.phone = phone;
        this.nickname = nickname;
        this.passwordHash = passwordHash;
        this.role = role;
    }

    public String userId() { return userId; }
    public String phone() { return phone; }
    public String nickname() { return nickname; }
    public String passwordHash() { return passwordHash; }
    public String role() { return role; }

    public AuthUserEntity withPasswordHash(String newPasswordHash) {
        return new AuthUserEntity(this.userId, this.phone, this.nickname, newPasswordHash, this.role);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof AuthUserEntity that)) return false;
        return userId.equals(that.userId);
    }

    @Override
    public int hashCode() {
        return userId.hashCode();
    }
}
