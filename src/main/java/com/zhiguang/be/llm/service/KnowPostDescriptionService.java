package com.zhiguang.be.llm.service;

/**
 * 帖子描述生成服务。
 */
public interface KnowPostDescriptionService {

    /**
     * 根据正文生成摘要描述。
     */
    String generateDescription(String content);
}
