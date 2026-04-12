package com.zhiguang.be.content.controller;

import com.zhiguang.be.common.api.ApiResponse;
import com.zhiguang.be.common.exception.BusinessException;
import com.zhiguang.be.common.exception.ErrorCode;
import com.zhiguang.be.content.ContentModels.ConfirmContentData;
import com.zhiguang.be.content.ContentModels.ConfirmContentRequest;
import com.zhiguang.be.content.ContentModels.CreateDraftRequest;
import com.zhiguang.be.content.ContentModels.DraftData;
import com.zhiguang.be.content.ContentModels.PostDetail;
import com.zhiguang.be.content.ContentModels.PublishPostRequest;
import com.zhiguang.be.content.ContentModels.StoragePresignData;
import com.zhiguang.be.content.ContentModels.StoragePresignRequest;
import com.zhiguang.be.content.ContentModels.UpdatePostMetadataRequest;
import com.zhiguang.be.content.ContentModels.UpdateTopRequest;
import com.zhiguang.be.content.ContentModels.UpdateVisibilityRequest;
import com.zhiguang.be.content.service.ContentServiceImpl;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
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
import org.springframework.web.bind.annotation.RestController;

/**
 * 内容模块控制器。
 * 统一暴露草稿、上传、元数据更新、发布和内容管理接口。
 */
@RestController
@Validated
@RequestMapping("/api/v1")
public class ContentController {

    private final ContentServiceImpl contentService;

    /**
     * 构造内容控制器。
     *
     * @param contentService 内容服务
     */
    public ContentController(ContentServiceImpl contentService) {
        this.contentService = contentService;
    }

    /**
     * 创建文章草稿。
     *
     * @param request 草稿请求
     * @param jwt 当前访问令牌
     * @return 草稿结果
     */
    @PostMapping("/posts/drafts")
    public ApiResponse<DraftData> createDraft(
            @Valid @RequestBody CreateDraftRequest request,
            @AuthenticationPrincipal Jwt jwt
    ) {
        return ApiResponse.success(contentService.createDraft(requireUserId(jwt), request));
    }

    /**
     * 申请上传预签名地址。
     *
     * @param request 预签名请求
     * @param jwt 当前访问令牌
     * @return 预签名结果
     */
    @PostMapping("/storage/presign")
    public ApiResponse<StoragePresignData> createPresign(
            @Valid @RequestBody StoragePresignRequest request,
            @AuthenticationPrincipal Jwt jwt
    ) {
        return ApiResponse.success(contentService.createPresign(requireUserId(jwt), request));
    }

    /**
     * 确认正文上传成功。
     *
     * @param postId 文章 ID
     * @param request 正文确认请求
     * @param jwt 当前访问令牌
     * @return 确认结果
     */
    @PostMapping("/posts/{postId}/content/confirm")
    public ApiResponse<ConfirmContentData> confirmContent(
            @PathVariable long postId,
            @Valid @RequestBody ConfirmContentRequest request,
            @AuthenticationPrincipal Jwt jwt
    ) {
        return ApiResponse.success(contentService.confirmContent(requireUserId(jwt), String.valueOf(postId), request));
    }

    /**
     * 更新文章元数据。
     *
     * @param postId 文章 ID
     * @param request 元数据请求
     * @param jwt 当前访问令牌
     * @return 最新文章详情
     */
    @PutMapping("/posts/{postId}/metadata")
    public ApiResponse<PostDetail> updateMetadata(
            @PathVariable long postId,
            @Valid @RequestBody UpdatePostMetadataRequest request,
            @AuthenticationPrincipal Jwt jwt
    ) {
        return ApiResponse.success(contentService.updateMetadata(requireUserId(jwt), String.valueOf(postId), request));
    }

    /**
     * 按 zhiguang 风格兼容 PATCH 更新元数据。
     *
     * @param postId 文章 ID
     * @param request 元数据请求
     * @param jwt 当前访问令牌
     * @return 最新文章详情
     */
    @PatchMapping("/posts/{postId}")
    public ApiResponse<PostDetail> patchMetadata(
            @PathVariable long postId,
            @Valid @RequestBody UpdatePostMetadataRequest request,
            @AuthenticationPrincipal Jwt jwt
    ) {
        return ApiResponse.success(contentService.updateMetadata(requireUserId(jwt), String.valueOf(postId), request));
    }

    /**
     * 发布文章。
     *
     * @param postId 文章 ID
     * @param request 发布请求
     * @param jwt 当前访问令牌
     * @return 发布后的文章详情
     */
    @PostMapping("/posts/{postId}/publish")
    public ApiResponse<PostDetail> publish(
            @PathVariable long postId,
            @Valid @RequestBody PublishPostRequest request,
            @AuthenticationPrincipal Jwt jwt
    ) {
        return ApiResponse.success(contentService.publish(requireUserId(jwt), String.valueOf(postId), request));
    }

    /**
     * 更新文章置顶状态。
     *
     * @param postId 文章 ID
     * @param request 置顶请求
     * @param jwt 当前访问令牌
     * @return 最新文章详情
     */
    @PatchMapping("/posts/{postId}/top")
    public ApiResponse<PostDetail> updateTop(
            @PathVariable long postId,
            @Valid @RequestBody UpdateTopRequest request,
            @AuthenticationPrincipal Jwt jwt
    ) {
        return ApiResponse.success(contentService.updateTop(requireUserId(jwt), String.valueOf(postId), request));
    }

    /**
     * 更新文章可见性。
     *
     * @param postId 文章 ID
     * @param request 可见性请求
     * @param jwt 当前访问令牌
     * @return 最新文章详情
     */
    @PatchMapping("/posts/{postId}/visibility")
    public ApiResponse<PostDetail> updateVisibility(
            @PathVariable long postId,
            @Valid @RequestBody UpdateVisibilityRequest request,
            @AuthenticationPrincipal Jwt jwt
    ) {
        return ApiResponse.success(contentService.updateVisibility(requireUserId(jwt), String.valueOf(postId), request));
    }

    /**
     * 软删除文章。
     *
     * @param postId 文章 ID
     * @param jwt 当前访问令牌
     * @return 空响应
     */
    @DeleteMapping("/posts/{postId}")
    public ApiResponse<Void> delete(
            @PathVariable long postId,
            @AuthenticationPrincipal Jwt jwt
    ) {
        contentService.delete(requireUserId(jwt), String.valueOf(postId));
        return ApiResponse.success(null);
    }

    /**
     * 查询文章详情。
     *
     * @param postId 文章 ID
     * @param jwt 当前访问令牌，可为空
     * @return 文章详情
     */
    @GetMapping("/posts/{postId}")
    public ApiResponse<PostDetail> getDetail(@PathVariable long postId, @AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.success(contentService.getDetail(String.valueOf(postId), jwt == null ? null : jwt.getSubject()));
    }

    /**
     * 提取当前登录用户 ID。
     *
     * @param jwt 当前访问令牌
     * @return 当前用户 ID
     */
    private String requireUserId(Jwt jwt) {
        if (jwt == null || jwt.getSubject() == null || jwt.getSubject().isBlank()) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, HttpStatus.UNAUTHORIZED, "当前请求未登录");
        }
        return jwt.getSubject();
    }
}
