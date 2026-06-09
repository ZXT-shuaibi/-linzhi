package com.zhiguang.be.content.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * 确认正文上传请求。
 */
public record ConfirmContentRequest(
        @NotBlank(message = "objectKey 不能为空")
        @Size(max = 512, message = "objectKey 长度不能超过 512")
        String objectKey,

        @NotBlank(message = "etag 不能为空")
        @Size(max = 128, message = "etag 长度不能超过 128")
        String etag,

        @NotBlank(message = "sha256 不能为空")
        @Size(min = 64, max = 64, message = "sha256 必须是 64 位")
        @Pattern(regexp = "^[0-9a-fA-F]{64}$", message = "sha256 必须是 64 位十六进制字符串")
        String sha256,

        @NotNull(message = "size 不能为空")
        @Positive(message = "size 必须大于 0")
        Long size
) {
}
