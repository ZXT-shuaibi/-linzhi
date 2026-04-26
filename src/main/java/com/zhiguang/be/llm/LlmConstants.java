package com.zhiguang.be.llm;

/**
 * LLM 模块常量。
 */
public final class LlmConstants {

    /**
     * 描述生成的最大字符数。
     */
    public static final int DESCRIPTION_MAX_CODE_POINTS = 50;

    /**
     * RAG 回答最多参考的上下文条数。
     */
    public static final int RAG_CONTEXT_LIMIT = 3;

    /**
     * 当前模板兜底模型名。
     */
    public static final String TEMPLATE_MODEL_NAME = "template-llm";

    private LlmConstants() {
    }
}
