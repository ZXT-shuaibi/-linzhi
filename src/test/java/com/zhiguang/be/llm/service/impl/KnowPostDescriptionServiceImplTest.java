package com.zhiguang.be.llm.service.impl;

import com.zhiguang.be.common.exception.BusinessException;
import com.zhiguang.be.common.exception.ErrorCode;
import com.zhiguang.be.llm.config.LlmProperties;
import com.zhiguang.be.llm.service.LlmGateway;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class KnowPostDescriptionServiceImplTest {

    @Test
    void generateDescriptionShouldPostProcessProviderOutput() {
        LlmGateway gateway = mock(LlmGateway.class);
        when(gateway.generateDescription("正文内容", 50)).thenReturn(" “社区周末旧物互换活动，欢迎来摆摊和淘宝。”\n");

        KnowPostDescriptionServiceImpl service = new KnowPostDescriptionServiceImpl(
                gateway,
                defaultProperties(),
                emptyChatClientProvider()
        );

        assertEquals("社区周末旧物互换活动，欢迎来摆摊和淘宝", service.generateDescription("正文内容"));
    }

    @Test
    void generateDescriptionShouldFallbackToContentWhenProviderReturnsBlank() {
        LlmGateway gateway = mock(LlmGateway.class);
        when(gateway.generateDescription("第一句。第二句。", 50)).thenReturn("   ");

        KnowPostDescriptionServiceImpl service = new KnowPostDescriptionServiceImpl(
                gateway,
                defaultProperties(),
                emptyChatClientProvider()
        );

        assertEquals("第一句", service.generateDescription("第一句。第二句。"));
    }

    @Test
    void generateDescriptionShouldRejectBlankContent() {
        LlmGateway gateway = mock(LlmGateway.class);
        KnowPostDescriptionServiceImpl service = new KnowPostDescriptionServiceImpl(
                gateway,
                defaultProperties(),
                emptyChatClientProvider()
        );

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> service.generateDescription(" ")
        );

        assertEquals(ErrorCode.BAD_REQUEST, exception.errorCode());
    }

    private LlmProperties defaultProperties() {
        LlmProperties properties = new LlmProperties();
        properties.setProvider("template");
        properties.setModelName("template-llm");
        return properties;
    }

    private ObjectProvider<ChatClient> emptyChatClientProvider() {
        return new StaticListableBeanFactory().getBeanProvider(ChatClient.class);
    }
}
