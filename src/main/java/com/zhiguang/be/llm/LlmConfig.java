package com.zhiguang.be.llm;

/**
 * LLM 模块基础配置。
 * 当前阶段先使用模板化实现，后续接入真实模型时沿用这一层常量口径。
 */
public final class LlmConfig {

    /**
     * 描述生成最大字数。
     */
    public static final int DESCRIPTION_MAX_CODE_POINTS = 50;

    /**
     * RAG 回答最多参考的上下文条数。
     */
    public static final int RAG_CONTEXT_LIMIT = 3;

    /**
     * 当前占位模型名。
     */
    public static final String MODEL_NAME = "template-llm";

    private LlmConfig() {
    }
}
