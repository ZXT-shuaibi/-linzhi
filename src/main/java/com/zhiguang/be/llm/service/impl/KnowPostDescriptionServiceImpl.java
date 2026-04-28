package com.zhiguang.be.llm.service.impl;

import com.zhiguang.be.common.exception.BusinessException;
import com.zhiguang.be.common.exception.ErrorCode;
import com.zhiguang.be.llm.LlmConstants;
import com.zhiguang.be.llm.config.LlmProperties;
import com.zhiguang.be.llm.service.KnowPostDescriptionService;
import com.zhiguang.be.llm.service.LlmGateway;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.deepseek.DeepSeekChatOptions;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.text.Normalizer;

/**
 * 帖子描述生成服务实现。
 * 优先走 Spring AI ChatClient，对齐 zhiguang 的主链；
 * ChatClient 不可用时，再回退到当前网关和模板兜底。
 */
@Service
public class KnowPostDescriptionServiceImpl implements KnowPostDescriptionService {

    private final LlmGateway llmGateway;
    private final LlmProperties llmProperties;
    private final ChatClient chatClient;

    public KnowPostDescriptionServiceImpl(
            LlmGateway llmGateway,
            LlmProperties llmProperties,
            ObjectProvider<ChatClient> chatClientProvider
    ) {
        this.llmGateway = llmGateway;
        this.llmProperties = llmProperties;
        this.chatClient = chatClientProvider.getIfAvailable();
    }

    @Override
    public String generateDescription(String content) {
        if (content == null || content.trim().isEmpty()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, HttpStatus.BAD_REQUEST, "正文内容不能为空");
        }

        String description = postProcess(generateWithSpringAi(content));
        if (!description.isEmpty()) {
            return description;
        }

        description = postProcess(llmGateway.generateDescription(content, LlmConstants.DESCRIPTION_MAX_CODE_POINTS));
        if (!description.isEmpty()) {
            return description;
        }
        return fallbackDescription(content);
    }

    /**
     * 优先使用 Spring AI ChatClient 生成帖子描述。
     */
    private String generateWithSpringAi(String content) {
        if (!useSpringAiProvider()) {
            return "";
        }
        try {
            String response = chatClient.prompt()
                    .system("你是中文社区文案助手，只输出一条精炼描述。")
                    .user("正文如下：\n" + content + "\n\n请输出不超过 "
                            + LlmConstants.DESCRIPTION_MAX_CODE_POINTS + " 个 Unicode 字符的中文描述。")
                    .options(buildSpringAiOptions(LlmConstants.DESCRIPTION_MAX_CODE_POINTS))
                    .call()
                    .content();
            return response == null ? "" : response;
        } catch (Exception ex) {
            return "";
        }
    }

    /**
     * 判断当前是否应优先走 Spring AI 主链。
     */
    private boolean useSpringAiProvider() {
        return chatClient != null
                && ("spring-ai".equalsIgnoreCase(llmProperties.getProvider())
                || "deepseek".equalsIgnoreCase(llmProperties.getProvider()));
    }

    /**
     * 构建 Spring AI 请求参数。
     */
    private DeepSeekChatOptions buildSpringAiOptions(int maxTokens) {
        return DeepSeekChatOptions.builder()
                .model(llmProperties.getModelName())
                .temperature(llmProperties.getHttp().getTemperature())
                .maxTokens(Math.max(1, maxTokens))
                .build();
    }

    private String postProcess(String text) {
        if (text == null) {
            return "";
        }
        String normalized = Normalizer.normalize(text, Normalizer.Form.NFKC)
                .replaceAll("\\s+", " ")
                .trim();
        normalized = stripWrappingQuotes(normalized);
        normalized = normalizeChinesePunctuation(normalized);
        normalized = stripTrailingPunctuation(normalized);
        return truncateByCodePoint(normalized, LlmConstants.DESCRIPTION_MAX_CODE_POINTS);
    }

    /**
     * 将常见半角标点归一成更适合中文展示的全角标点。
     */
    private String normalizeChinesePunctuation(String text) {
        return text
                .replace(",", "，")
                .replace(";", "；")
                .replace(":", "：");
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
            if (last == '。' || last == '，' || last == '！' || last == '？'
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
        String[] parts = normalized.split("[。！？]");
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
