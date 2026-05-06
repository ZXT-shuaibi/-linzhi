package com.zhiguang.be.auth.model;

/**
 * 当前登录用户信息响应。
 * 用于 /me 接口返回认证域当前可见的用户概要信息。
 *
 * @param userId 用户唯一 ID
 * @param phone 手机号
 * @param nickname 昵称
 */
public record AuthUserResponse(
        String userId,
        String phone,
        String nickname
) {
}
