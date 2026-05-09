package com.zhiguang.be.content.controller;

import com.zhiguang.be.common.api.ApiResponse;
import com.zhiguang.be.auth.security.JwtSubjects;
import com.zhiguang.be.content.dto.ConfirmContentRequest;
import com.zhiguang.be.content.dto.DraftData;
import com.zhiguang.be.content.dto.PostDetail;
import com.zhiguang.be.content.dto.PostPageData;
import com.zhiguang.be.content.dto.UpdatePostMetadataRequest;
import com.zhiguang.be.content.service.ContentService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Validated
@RequestMapping("/api/v1")
public class ContentController {

    private final ContentService contentService;

    public ContentController(ContentService contentService) {
        this.contentService = contentService;
    }

    @PostMapping({"/posts/drafts", "/knowposts/drafts"})
    public ApiResponse<DraftData> createDraft(@AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.success(contentService.createDraft(JwtSubjects.requireUserId(jwt)));
    }

    @PostMapping({"/posts/{postId}/content/confirm", "/knowposts/{postId}/content/confirm"})
    public ApiResponse<Void> confirmContent(
            @PathVariable long postId,
            @Valid @RequestBody ConfirmContentRequest request,
            @AuthenticationPrincipal Jwt jwt
    ) {
        contentService.confirmContent(JwtSubjects.requireUserId(jwt), postId, request);
        return ApiResponse.success(null);
    }

    @PutMapping({"/posts/{postId}/metadata", "/knowposts/{postId}/metadata"})
    public ApiResponse<PostDetail> updateMetadata(
            @PathVariable long postId,
            @Valid @RequestBody UpdatePostMetadataRequest request,
            @AuthenticationPrincipal Jwt jwt
    ) {
        return ApiResponse.success(contentService.updateMetadata(JwtSubjects.requireUserId(jwt), postId, request));
    }

    @PatchMapping({"/posts/{postId}", "/knowposts/{postId}"})
    public ApiResponse<PostDetail> patchMetadata(
            @PathVariable long postId,
            @Valid @RequestBody UpdatePostMetadataRequest request,
            @AuthenticationPrincipal Jwt jwt
    ) {
        return ApiResponse.success(contentService.updateMetadata(JwtSubjects.requireUserId(jwt), postId, request));
    }

    @PostMapping({"/posts/{postId}/publish", "/knowposts/{postId}/publish"})
    public ApiResponse<PostDetail> publish(@PathVariable long postId, @AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.success(contentService.publish(JwtSubjects.requireUserId(jwt), postId));
    }

    @PatchMapping({"/posts/{postId}/top", "/knowposts/{postId}/top"})
    public ApiResponse<PostDetail> updateTop(
            @PathVariable long postId,
            @RequestParam boolean isTop,
            @AuthenticationPrincipal Jwt jwt
    ) {
        return ApiResponse.success(contentService.updateTop(JwtSubjects.requireUserId(jwt), postId, isTop));
    }

    @PatchMapping({"/posts/{postId}/visibility", "/knowposts/{postId}/visibility"})
    public ApiResponse<PostDetail> updateVisibility(
            @PathVariable long postId,
            @RequestParam
            @Pattern(regexp = "^(public|followers|private)$", message = "visibility 只能是 public、followers、private")
            String visibility,
            @AuthenticationPrincipal Jwt jwt
    ) {
        return ApiResponse.success(contentService.updateVisibility(JwtSubjects.requireUserId(jwt), postId, visibility));
    }

    @DeleteMapping({"/posts/{postId}", "/knowposts/{postId}"})
    public ApiResponse<Void> delete(@PathVariable long postId, @AuthenticationPrincipal Jwt jwt) {
        contentService.delete(JwtSubjects.requireUserId(jwt), postId);
        return ApiResponse.success(null);
    }

    @GetMapping({"/posts/feed", "/knowposts/feed"})
    public ApiResponse<PostPageData> feed(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @AuthenticationPrincipal Jwt jwt
    ) {
        return ApiResponse.success(contentService.getPublicFeed(JwtSubjects.optionalUserId(jwt), page, size));
    }

    @GetMapping({"/posts/mine", "/knowposts/mine"})
    public ApiResponse<PostPageData> mine(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @AuthenticationPrincipal Jwt jwt
    ) {
        return ApiResponse.success(contentService.getMyPublished(JwtSubjects.requireUserId(jwt), page, size));
    }

    @GetMapping({"/posts/{postId}", "/knowposts/{postId}"})
    public ApiResponse<PostDetail> getDetail(@PathVariable long postId, @AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.success(contentService.getDetail(postId, JwtSubjects.optionalUserId(jwt)));
    }

}
