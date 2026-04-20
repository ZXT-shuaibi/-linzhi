package com.zhiguang.be.content.controller;

import com.zhiguang.be.common.api.ApiResponse;
import com.zhiguang.be.common.exception.BusinessException;
import com.zhiguang.be.common.exception.ErrorCode;
import com.zhiguang.be.content.dto.ConfirmContentRequest;
import com.zhiguang.be.content.dto.DraftData;
import com.zhiguang.be.content.dto.PostDetail;
import com.zhiguang.be.content.dto.PostPageData;
import com.zhiguang.be.content.dto.StoragePresignData;
import com.zhiguang.be.content.dto.StoragePresignRequest;
import com.zhiguang.be.content.dto.UpdatePostMetadataRequest;
import com.zhiguang.be.content.service.ContentServiceImpl;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 内容模块控制器。
 * 风格参考 zhiguang，但保持 linli 当前 `/posts` 路由组织方式。
 */
@RestController
@Validated
@RequestMapping("/api/v1")
public class ContentController {

    private final ContentServiceImpl contentService;

    /**
     * 注入内容服务。
     */
    public ContentController(ContentServiceImpl contentService) {
        this.contentService = contentService;
    }

    /**
     * 创建内容草稿。
     * 参考 zhiguang，这一步不再额外接收空请求体。
     */
    @PostMapping("/posts/drafts")
    public ApiResponse<DraftData> createDraft(@AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.success(contentService.createDraft(requireUserId(jwt)));
    }

    /**
     * 申请对象存储预签名上传地址。
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
     */
    @PostMapping("/posts/{postId}/content/confirm")
    public ApiResponse<Void> confirmContent(
            @PathVariable long postId,
            @Valid @RequestBody ConfirmContentRequest request,
            @AuthenticationPrincipal Jwt jwt
    ) {
        contentService.confirmContent(requireUserId(jwt), String.valueOf(postId), request);
        return ApiResponse.success(null);
    }

    /**
     * 更新文章元数据。
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
     * 兼容 zhiguang 风格的 PATCH 元数据更新。
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
     */
    @PostMapping("/posts/{postId}/publish")
    public ApiResponse<PostDetail> publish(@PathVariable long postId, @AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.success(contentService.publish(requireUserId(jwt), String.valueOf(postId)));
    }

    /**
     * 更新文章置顶状态。
     */
    @PatchMapping("/posts/{postId}/top")
    public ApiResponse<PostDetail> updateTop(
            @PathVariable long postId,
            @RequestParam boolean isTop,
            @AuthenticationPrincipal Jwt jwt
    ) {
        return ApiResponse.success(contentService.updateTop(requireUserId(jwt), String.valueOf(postId), isTop));
    }

    /**
     * 更新文章可见性。
     */
    @PatchMapping("/posts/{postId}/visibility")
    public ApiResponse<PostDetail> updateVisibility(
            @PathVariable long postId,
            @RequestParam
            @Pattern(regexp = "^(public|followers|private)$", message = "visibility 只能是 public、followers、private")
            String visibility,
            @AuthenticationPrincipal Jwt jwt
    ) {
        return ApiResponse.success(contentService.updateVisibility(requireUserId(jwt), String.valueOf(postId), visibility));
    }

    /**
     * 删除文章。
     */
    @DeleteMapping("/posts/{postId}")
    public ApiResponse<Void> delete(@PathVariable long postId, @AuthenticationPrincipal Jwt jwt) {
        contentService.delete(requireUserId(jwt), String.valueOf(postId));
        return ApiResponse.success(null);
    }

    /**
     * 查询公开内容流。
     * 若请求携带 accessToken，则补充用户维度字段。
     */
    @GetMapping("/posts/feed")
    public ApiResponse<PostPageData> feed(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @AuthenticationPrincipal Jwt jwt
    ) {
        return ApiResponse.success(contentService.getPublicFeed(optionalUserId(jwt), page, size));
    }

    /**
     * 查询当前用户已发布内容。
     */
    @GetMapping("/posts/mine")
    public ApiResponse<PostPageData> mine(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @AuthenticationPrincipal Jwt jwt
    ) {
        return ApiResponse.success(contentService.getMyPublished(requireUserId(jwt), page, size));
    }

    /**
     * 查询文章详情。
     */
    @GetMapping("/posts/{postId}")
    public ApiResponse<PostDetail> getDetail(@PathVariable long postId, @AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.success(contentService.getDetail(String.valueOf(postId), optionalUserId(jwt)));
    }

    /**
     * 提取当前登录用户 ID。
     */
    private String requireUserId(Jwt jwt) {
        if (jwt == null || jwt.getSubject() == null || jwt.getSubject().isBlank()) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, HttpStatus.UNAUTHORIZED, "当前请求未登录");
        }
        return jwt.getSubject();
    }

    /**
     * 提取可选用户 ID。
     */
    private String optionalUserId(Jwt jwt) {
        if (jwt == null || jwt.getSubject() == null || jwt.getSubject().isBlank()) {
            return null;
        }
        return jwt.getSubject();
    }
}
