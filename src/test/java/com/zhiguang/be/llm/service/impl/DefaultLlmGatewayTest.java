package com.zhiguang.be.llm.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.zhiguang.be.llm.config.LlmProperties;
import com.zhiguang.be.llm.service.RagAnswerService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultLlmGatewayTest {

    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void generateDescriptionShouldSendChatMessagesAndReadOpenAiStyleOutput() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<String>();
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/llm", exchange -> {
            requestBody.set(readBody(exchange));
            writeJson(exchange, 200, """
                    {
                      "output": [
                        {
                          "content": [
                            {
                              "type": "output_text",
                              "text": "社区义卖本周末在中心广场举行"
                            }
                          ]
                        }
                      ]
                    }
                    """);
        });
        server.start();

        DefaultLlmGateway gateway = new DefaultLlmGateway(
                new ObjectMapper(),
                httpProperties(serverUrl(), true),
                emptyChatClientProvider()
        );

        String result = gateway.generateDescription("正文内容", 50);

        assertEquals("社区义卖本周末在中心广场举行", result);
        assertTrue(requestBody.get().contains("\"task\":\"post_description\""));
        assertTrue(requestBody.get().contains("\"messages\""));
        assertTrue(requestBody.get().contains("\"role\":\"system\""));
    }

    @Test
    void generateRagAnswerShouldReadChoicesMessageContentArray() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/llm", exchange -> writeJson(exchange, 200, """
                {
                  "choices": [
                    {
                      "message": {
                        "content": [
                          {
                            "type": "text",
                            "text": "根据社区公告，"
                          },
                          {
                            "type": "text",
                            "text": "本周六上午九点开始。"
                          }
                        ]
                      }
                    }
                  ]
                }
                """));
        server.start();

        DefaultLlmGateway gateway = new DefaultLlmGateway(
                new ObjectMapper(),
                httpProperties(serverUrl(), true),
                emptyChatClientProvider()
        );

        String result = gateway.generateRagAnswer(
                "活动什么时候开始？",
                List.of(new RagAnswerService.Context("社区义卖", "本周六上午九点开始"))
        );

        assertEquals("根据社区公告，本周六上午九点开始。", result);
    }

    @Test
    void generateDescriptionShouldFallbackToTemplateWhenHttpProviderFails() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/llm", exchange -> writeJson(exchange, 500, "{\"error\":\"boom\"}"));
        server.start();

        DefaultLlmGateway gateway = new DefaultLlmGateway(
                new ObjectMapper(),
                httpProperties(serverUrl(), true),
                emptyChatClientProvider()
        );

        String result = gateway.generateDescription("社区今天举办亲子读书会。欢迎带孩子参加。", 50);

        assertEquals("社区今天举办亲子读书会", result);
    }

    private LlmProperties httpProperties(String endpoint, boolean fallbackToTemplate) {
        LlmProperties properties = new LlmProperties();
        properties.setProvider("http");
        properties.setModelName("mock-chat-model");
        properties.setFallbackToTemplate(fallbackToTemplate);
        properties.getHttp().setEndpoint(endpoint);
        properties.getHttp().setTimeoutSeconds(5);
        return properties;
    }

    private ObjectProvider<ChatClient> emptyChatClientProvider() {
        return new StaticListableBeanFactory().getBeanProvider(ChatClient.class);
    }

    private String serverUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/llm";
    }

    private String readBody(HttpExchange exchange) throws IOException {
        return new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    }

    private void writeJson(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream outputStream = exchange.getResponseBody()) {
            outputStream.write(bytes);
        }
    }
}
