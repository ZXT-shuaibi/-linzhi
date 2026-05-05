package com.zhiguang.be.storage;

import com.zhiguang.be.common.api.ApiResponse;
import com.zhiguang.be.common.exception.BusinessException;
import com.zhiguang.be.common.exception.ErrorCode;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 对象存储控制器。
 * 统一提供预签名上传入口，供正文、图片、头像等场景复用。
 */
@Validated
@RestController
@RequestMapping("/api/v1/storage")
public class StorageController {

    private final StorageService storageService;

    public StorageController(StorageService storageService) {
        this.storageService = storageService;
    }

    /**
     * 申请上传预签名。
     */
    @PostMapping("/presign")
    public ApiResponse<StoragePresignData> createPresign(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody StoragePresignRequest request
    ) {
        return ApiResponse.success(storageService.createPresign(requireUserId(jwt), request));
    }

    @PostMapping("/multipart/init")
    public ApiResponse<StorageMultipartInitData> initiateMultipartUpload(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody StorageMultipartInitRequest request
    ) {
        return ApiResponse.success(storageService.initiateMultipartUpload(requireUserId(jwt), request));
    }

    @PostMapping("/multipart/part/presign")
    public ApiResponse<StorageMultipartPartPresignData> createMultipartPartPresign(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody StorageMultipartPartPresignRequest request
    ) {
        return ApiResponse.success(storageService.createMultipartPartPresign(requireUserId(jwt), request));
    }

    @PostMapping("/multipart/complete")
    public ApiResponse<StorageMultipartCompleteData> completeMultipartUpload(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody StorageMultipartCompleteRequest request
    ) {
        return ApiResponse.success(storageService.completeMultipartUpload(requireUserId(jwt), request));
    }

    @PostMapping("/multipart/abort")
    public ApiResponse<Void> abortMultipartUpload(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody StorageMultipartAbortRequest request
    ) {
        storageService.abortMultipartUpload(requireUserId(jwt), request);
        return ApiResponse.success(null);
    }

    private long requireUserId(Jwt jwt) {
        if (jwt == null || jwt.getSubject() == null || jwt.getSubject().isBlank()) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, HttpStatus.UNAUTHORIZED, "当前请求未登录");
        }
        return Long.parseLong(jwt.getSubject());
    }
}
