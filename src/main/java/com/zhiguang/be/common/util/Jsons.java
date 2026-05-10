package com.zhiguang.be.common.util;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhiguang.be.common.exception.BusinessException;
import com.zhiguang.be.common.exception.ErrorCode;
import org.springframework.http.HttpStatus;

import java.util.ArrayList;
import java.util.List;

public final class Jsons {

    private static final TypeReference<List<String>> STRING_LIST_TYPE = new TypeReference<List<String>>() {
    };

    private Jsons() {
    }

    public static String toJson(ObjectMapper objectMapper, Object value, String errorMessage) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, HttpStatus.INTERNAL_SERVER_ERROR, errorMessage);
        }
    }

    public static List<String> parseStringList(ObjectMapper objectMapper, String json) {
        if (!Texts.hasText(json)) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, STRING_LIST_TYPE);
        } catch (JsonProcessingException ex) {
            return List.of();
        }
    }

    public static List<String> parseNormalizedStringList(ObjectMapper objectMapper, String json) {
        List<String> parsed = parseStringList(objectMapper, json);
        if (parsed.isEmpty()) {
            return List.of();
        }
        List<String> normalized = new ArrayList<String>();
        for (String item : parsed) {
            String normalizedItem = Texts.normalizeNullable(item);
            if (normalizedItem != null && !normalized.contains(normalizedItem)) {
                normalized.add(normalizedItem);
            }
        }
        return normalized;
    }
}
