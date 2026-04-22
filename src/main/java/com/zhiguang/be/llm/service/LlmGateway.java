package com.zhiguang.be.llm.service;

import java.util.List;

/**
 * LLM 网关。
 */
public interface LlmGateway {

    /**
     * 当前生效模型名。
     */
    String currentModelName();

    /**
     * 生成帖子描述。
     */
    String generateDescription(String content, int maxCodePoints);

    /**
     * 生成 RAG 回答。
     */
    String generateRagAnswer(String question, List<RagAnswerService.Context> contexts);
}
