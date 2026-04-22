package com.zhiguang.be.llm;

/**
 * LLM 模块基础配置。
 * 当前阶段同时兼容模板兜底和 HTTP 模型接入，统一使用这组常量控制行为。
 */
public final class LlmConfig {

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
    public static final String MODEL_NAME = "template-llm";

    private LlmConfig() {
    }
}
