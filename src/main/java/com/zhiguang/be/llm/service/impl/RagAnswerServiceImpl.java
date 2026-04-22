package com.zhiguang.be.llm.service.impl;

import com.zhiguang.be.llm.service.LlmGateway;
import com.zhiguang.be.llm.service.RagAnswerService;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * RAG 回答生成服务实现。
 */
@Service
public class RagAnswerServiceImpl implements RagAnswerService {

    private final LlmGateway llmGateway;

    public RagAnswerServiceImpl(LlmGateway llmGateway) {
        this.llmGateway = llmGateway;
    }

    @Override
    public String buildAnswer(String question, List<Context> contexts) {
        return llmGateway.generateRagAnswer(question, contexts == null ? List.of() : contexts);
    }
}
