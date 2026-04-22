package com.zhiguang.be.llm.service.impl;

import com.zhiguang.be.common.exception.BusinessException;
import com.zhiguang.be.common.exception.ErrorCode;
import com.zhiguang.be.llm.LlmConfig;
import com.zhiguang.be.llm.service.KnowPostDescriptionService;
import com.zhiguang.be.llm.service.LlmGateway;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * 帖子描述生成服务实现。
 */
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
        return llmGateway.generateDescription(content, LlmConfig.DESCRIPTION_MAX_CODE_POINTS);
    }
}
