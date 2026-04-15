package com.zhiguang.be.content.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 申请预签名上传地址请求。
 */
public record StoragePresignRequest(
        @NotBlank(message = "postId 不能为空")
        @Pattern(regexp = "^\\d+$", message = "postId 必须为数字 ID")
        String postId,

        @NotBlank(message = "filename 不能为空")
        @Size(max = 255, message = "filename 长度不能超过 255")
        String filename,

        @NotBlank(message = "contentType 不能为空")
        @Size(max = 128, message = "contentType 长度不能超过 128")
        String contentType,

        @NotBlank(message = "purpose 不能为空")
        @Pattern(regexp = "^(content|image)$", message = "purpose 只能是 content 或 image")
        String purpose
) {
}
