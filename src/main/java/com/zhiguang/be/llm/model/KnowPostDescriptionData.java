package com.zhiguang.be.llm.model;

/**
 * 帖子描述生成结果。
 */
public record KnowPostDescriptionData(
        String model,
        String description
) {
}
