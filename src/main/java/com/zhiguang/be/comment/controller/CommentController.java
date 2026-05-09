package com.zhiguang.be.comment.controller;

import com.zhiguang.be.auth.security.JwtSubjects;
import com.zhiguang.be.comment.model.CommentItemData;
import com.zhiguang.be.comment.model.CommentPageData;
import com.zhiguang.be.comment.model.CreateCommentRequest;
import com.zhiguang.be.comment.service.CommentService;
import com.zhiguang.be.common.api.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/v1/posts/{postId}/comments")
public class CommentController {

    private final CommentService commentService;

    public CommentController(CommentService commentService) {
        this.commentService = commentService;
    }

    @GetMapping
    public ApiResponse<CommentPageData> listComments(
            @PathVariable @Min(1) long postId,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) int size,
            @AuthenticationPrincipal Jwt jwt
    ) {
        return ApiResponse.success(commentService.listComments(postId, optionalUserId(jwt), page, size));
    }

    @PostMapping
    public ApiResponse<CommentItemData> createComment(
            @PathVariable @Min(1) long postId,
            @Valid @RequestBody CreateCommentRequest request,
            @AuthenticationPrincipal Jwt jwt
    ) {
        return ApiResponse.success(commentService.createComment(requireUserId(jwt), postId, request));
    }

    private long requireUserId(Jwt jwt) {
        return JwtSubjects.requireUserId(jwt);
    }

    private Long optionalUserId(Jwt jwt) {
        return JwtSubjects.optionalUserId(jwt);
    }
}
