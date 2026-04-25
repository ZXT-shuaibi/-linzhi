package com.zhiguang.be.llm.service;

import java.util.List;
import java.util.function.Consumer;

/**
 * LLM 网关接口。
 */
public interface LlmGateway {

    /**
     * 获取当前生效的模型名。
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

    /**
     * 尝试流式输出 RAG 回答。
     *
     * @param question 问题
     * @param contexts 检索上下文
     * @param consumer 增量文本消费者
     * @return 成功走真实流式输出时返回 true，否则返回 false
     */
    boolean streamRagAnswer(String question, List<RagAnswerService.Context> contexts, Consumer<String> consumer);
}
