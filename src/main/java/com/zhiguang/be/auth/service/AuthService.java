package com.zhiguang.be.auth.service;

import com.zhiguang.be.auth.model.ActionResult;
import com.zhiguang.be.auth.model.AuthSessionData;
import com.zhiguang.be.auth.model.AuthTokens;
import com.zhiguang.be.auth.model.LoginRequest;
import com.zhiguang.be.auth.model.PasswordResetRequest;
import com.zhiguang.be.auth.model.RegisterRequest;

/**
 * 接口说明。
 */
public interface AuthService {

    AuthSessionData register(RegisterRequest request);

    AuthSessionData login(LoginRequest request);

    AuthTokens refreshToken(String refreshToken);

    ActionResult logout(String refreshToken, String logoutScope);

    ActionResult resetPassword(PasswordResetRequest request);
}

