package com.zhiguang.be.llm.service;

import java.util.List;

/**
 * RAG 回答生成服务。
 */
public interface RagAnswerService {

    /**
     * 根据问题和召回片段生成答案。
     */
    String buildAnswer(String question, List<Context> contexts);

    /**
     * 回答生成上下文。
     */
    record Context(
            String title,
            String content
    ) {
    }
}
