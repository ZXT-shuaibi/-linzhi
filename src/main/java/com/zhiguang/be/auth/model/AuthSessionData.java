package com.zhiguang.be.auth.model;

/**
 * 数据结构说明。
 */
public record AuthSessionData(String userId, AuthTokens tokens) {
}

