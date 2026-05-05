package com.zhiguang.be.content.controller;

import com.zhiguang.be.content.dto.ConfirmContentRequest;
import com.zhiguang.be.content.dto.UpdatePostMetadataRequest;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ContentControllerMappingTest {

    @Test
    void createDraftShouldExposePostsAndKnowpostsRoutes() throws Exception {
        Method method = ContentController.class.getMethod("createDraft", Jwt.class);

        List<String> routes = List.of(method.getAnnotation(PostMapping.class).value());

        assertTrue(routes.contains("/posts/drafts"));
        assertTrue(routes.contains("/knowposts/drafts"));
    }

    @Test
    void progressivePublishRoutesShouldExposeKnowpostsAliases() throws Exception {
        assertPostRoute(
                "confirmContent",
                new Class<?>[] {long.class, ConfirmContentRequest.class, Jwt.class},
                "/posts/{postId}/content/confirm",
                "/knowposts/{postId}/content/confirm"
        );
        assertPatchRoute(
                "patchMetadata",
                new Class<?>[] {long.class, UpdatePostMetadataRequest.class, Jwt.class},
                "/posts/{postId}",
                "/knowposts/{postId}"
        );
        assertPostRoute(
                "publish",
                new Class<?>[] {long.class, Jwt.class},
                "/posts/{postId}/publish",
                "/knowposts/{postId}/publish"
        );
    }

    private void assertPostRoute(String methodName, Class<?>[] parameterTypes, String current, String alias)
            throws Exception {
        Method method = ContentController.class.getMethod(methodName, parameterTypes);
        List<String> routes = List.of(method.getAnnotation(PostMapping.class).value());

        assertTrue(routes.contains(current));
        assertTrue(routes.contains(alias));
    }

    private void assertPatchRoute(String methodName, Class<?>[] parameterTypes, String current, String alias)
            throws Exception {
        Method method = ContentController.class.getMethod(methodName, parameterTypes);
        List<String> routes = List.of(method.getAnnotation(PatchMapping.class).value());

        assertTrue(routes.contains(current));
        assertTrue(routes.contains(alias));
    }
}
