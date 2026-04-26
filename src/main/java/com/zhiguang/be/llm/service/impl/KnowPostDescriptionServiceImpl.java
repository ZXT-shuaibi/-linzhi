package com.zhiguang.be.llm.service.impl;

import com.zhiguang.be.common.exception.BusinessException;
import com.zhiguang.be.common.exception.ErrorCode;
import com.zhiguang.be.llm.LlmConstants;
import com.zhiguang.be.llm.service.KnowPostDescriptionService;
import com.zhiguang.be.llm.service.LlmGateway;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.text.Normalizer;

@Service
public class KnowPostDescriptionServiceImpl implements KnowPostDescriptionService {

    private final LlmGateway llmGateway;

    public KnowPostDescriptionServiceImpl(LlmGateway llmGateway) {
        this.llmGateway = llmGateway;
    }

    @Override
    public String generateDescription(String content) {
        if (content == null || content.trim().isEmpty()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, HttpStatus.BAD_REQUEST, "正文内容不能为空");
        }

        String description = postProcess(
                llmGateway.generateDescription(content, LlmConstants.DESCRIPTION_MAX_CODE_POINTS)
        );
        if (!description.isEmpty()) {
            return description;
        }
        return fallbackDescription(content);
    }

    private String postProcess(String text) {
        if (text == null) {
            return "";
        }
        String normalized = Normalizer.normalize(text, Normalizer.Form.NFKC)
                .replaceAll("\\s+", " ")
                .trim();
        normalized = stripWrappingQuotes(normalized);
        normalized = stripTrailingPunctuation(normalized);
        return truncateByCodePoint(normalized, LlmConstants.DESCRIPTION_MAX_CODE_POINTS);
    }

    private String stripWrappingQuotes(String text) {
        String normalized = text;
        while (normalized.length() >= 2 && isWrappingQuote(normalized.charAt(0), normalized.charAt(normalized.length() - 1))) {
            normalized = normalized.substring(1, normalized.length() - 1).trim();
        }
        return normalized;
    }

    private boolean isWrappingQuote(char start, char end) {
        return (start == '"' && end == '"')
                || (start == '\'' && end == '\'')
                || (start == '“' && end == '”')
                || (start == '‘' && end == '’');
    }

    private String stripTrailingPunctuation(String text) {
        String normalized = text;
        while (!normalized.isEmpty()) {
            char last = normalized.charAt(normalized.length() - 1);
            if (last == '。' || last == '！' || last == '？' || last == '；'
                    || last == '.' || last == '!' || last == '?' || last == ';') {
                normalized = normalized.substring(0, normalized.length() - 1).trim();
                continue;
            }
            break;
        }
        return normalized;
    }

    private String fallbackDescription(String content) {
        String normalized = Normalizer.normalize(content, Normalizer.Form.NFKC)
                .replaceAll("```[\\s\\S]*?```", " ")
                .replaceAll("`", " ")
                .replaceAll("\\s+", " ")
                .trim();
        String[] parts = normalized.split("[。！？!?]");
        for (String part : parts) {
            String candidate = part.trim();
            if (!candidate.isEmpty()) {
                return truncateByCodePoint(candidate, LlmConstants.DESCRIPTION_MAX_CODE_POINTS);
            }
        }
        return truncateByCodePoint(normalized, LlmConstants.DESCRIPTION_MAX_CODE_POINTS);
    }

    private String truncateByCodePoint(String value, int maxCodePoints) {
        int codePoints = value.codePointCount(0, value.length());
        if (codePoints <= maxCodePoints) {
            return value;
        }
        StringBuilder builder = new StringBuilder();
        int index = 0;
        int count = 0;
        while (index < value.length() && count < maxCodePoints) {
            int codePoint = value.codePointAt(index);
            builder.appendCodePoint(codePoint);
            index += Character.charCount(codePoint);
            count++;
        }
        return builder.toString();
    }
}
