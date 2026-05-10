package com.zhiguang.be.auth.config;

import com.zhiguang.be.rag.controller.RagController;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SecurityConfigurationRagRouteTest {

    @Test
    void ragReindexSecurityPathsShouldMatchControllerRoutes() throws Exception {
        assertEquals(
                fullPostPath("reindexPublicPosts"),
                SecurityConfiguration.RAG_PUBLIC_REINDEX_PATH
        );
        assertEquals(
                fullPostPath("reindex", long.class).replace("{postId}", "*"),
                SecurityConfiguration.RAG_POST_REINDEX_PATH
        );
    }

    private String fullPostPath(String methodName, Class<?>... parameterTypes) throws Exception {
        String basePath = RagController.class.getAnnotation(RequestMapping.class).value()[0];
        Method method = RagController.class.getMethod(methodName, parameterTypes);
        String methodPath = method.getAnnotation(PostMapping.class).value()[0];
        return basePath + methodPath;
    }
}
