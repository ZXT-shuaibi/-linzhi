package com.zhiguang.be.auth.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class AdminGuardFilterTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void forbiddenResponseShouldEscapeMessageAsValidJson() throws Exception {
        AdminGuardFilter filter = new AdminGuardFilter();
        MockHttpServletResponse response = new MockHttpServletResponse();

        ReflectionTestUtils.invokeMethod(filter, "sendForbidden", response, "bad \"quote\"\nline");

        JsonNode body = objectMapper.readTree(response.getContentAsString(StandardCharsets.UTF_8));
        assertEquals("FORBIDDEN", body.path("code").asText());
        assertEquals("bad \"quote\"\nline", body.path("message").asText());
    }

    @Test
    void adminPathShouldOnlyMatchExactProtectedWriteRoutes() {
        AdminGuardFilter filter = new AdminGuardFilter();
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/rag/posts/1001/comments");

        Boolean adminPath = ReflectionTestUtils.invokeMethod(filter, "isAdminPath", request);

        assertFalse(Boolean.TRUE.equals(adminPath));
    }
}
