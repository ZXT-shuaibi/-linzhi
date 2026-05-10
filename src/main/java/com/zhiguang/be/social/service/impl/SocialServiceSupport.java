package com.zhiguang.be.social.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhiguang.be.common.exception.BusinessException;
import com.zhiguang.be.common.exception.ErrorCode;
import org.springframework.http.HttpStatus;

final class SocialServiceSupport {

    private SocialServiceSupport() {
    }

    static void ensureAuthenticatedUser(long currentUserId) {
        if (currentUserId <= 0L) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, HttpStatus.UNAUTHORIZED, "Invalid login state");
        }
    }

    static String serialize(ObjectMapper objectMapper, Object payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException ex) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, HttpStatus.INTERNAL_SERVER_ERROR, "Event serialization failed");
        }
    }

    static String toStringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
