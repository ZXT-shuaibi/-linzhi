package com.zhiguang.be.llm;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * LLM 模块配置。
 * 参考 zhiguang 的写法，优先把大模型对话能力收口成统一的 ChatClient。
 */
@Configuration
public class LlmConfig {

    /**
     * 构建统一的 ChatClient。
     * 当 Spring AI 已经创建出 DeepSeek ChatModel 时，业务层统一通过它发起对话。
     */
    @Bean(name = "zhiguangChatClient")
    @ConditionalOnBean(name = "deepSeekChatModel")
    public ChatClient chatClient(@Qualifier("deepSeekChatModel") ChatModel chatModel) {
        return ChatClient.builder(chatModel).build();
    }
}
