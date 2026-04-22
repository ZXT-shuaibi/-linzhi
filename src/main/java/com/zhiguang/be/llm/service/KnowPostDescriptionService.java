package com.zhiguang.be.llm.service;

/**
 * 帖子描述生成服务。
 */
public interface KnowPostDescriptionService {

    /**
     * 基于正文生成简要描述。
     */
    String generateDescription(String content);
}
