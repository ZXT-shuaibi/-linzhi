package com.zhiguang.be.auth.security;

import com.zhiguang.be.common.exception.BusinessException;
import com.zhiguang.be.common.exception.ErrorCode;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;

public final class JwtSubjects {

    private JwtSubjects() {
    }

    public static long requireUserId(Jwt jwt) {
        String subject = requireSubject(jwt);
        try {
            return Long.parseLong(subject);
        } catch (NumberFormatException ex) {
            throw unauthorized();
        }
    }

    public static long optionalUserId(Jwt jwt) {
        if (jwt == null || jwt.getSubject() == null || jwt.getSubject().isBlank()) {
            return 0L;
        }
        try {
            return Long.parseLong(jwt.getSubject());
        } catch (NumberFormatException ex) {
            return 0L;
        }
    }

    public static String requireSubject(Jwt jwt) {
        if (jwt == null || jwt.getSubject() == null || jwt.getSubject().isBlank()) {
            throw unauthorized();
        }
        return jwt.getSubject();
    }

    private static BusinessException unauthorized() {
        return new BusinessException(ErrorCode.UNAUTHORIZED, HttpStatus.UNAUTHORIZED, "Invalid login state");
    }
}
