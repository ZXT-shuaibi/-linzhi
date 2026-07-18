package com.zhiguang.be.auth.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PublicApiPathsTest {

    @Test
    void followListsShouldNotBeAnonymousPublicEndpoints() {
        assertFalse(PublicApiPaths.matches(request("GET", "/api/v1/follows/users/7/following")));
        assertFalse(PublicApiPaths.matches(request("GET", "/api/v1/follows/users/7/followers")));
        assertFalse(PublicApiPaths.matches(request("GET", "/api/v1/profile/users/7/following")));
        assertFalse(PublicApiPaths.matches(request("GET", "/api/v1/profile/users/7/followers")));
    }

    @Test
    void publicProfileAndPostsShouldRemainAnonymousPublicEndpoints() {
        assertTrue(PublicApiPaths.matches(request("GET", "/api/v1/profile/users/7")));
        assertTrue(PublicApiPaths.matches(request("GET", "/api/v1/profile/users/7/posts")));
    }

    private MockHttpServletRequest request(String method, String path) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.setServletPath(path);
        return request;
    }
}
