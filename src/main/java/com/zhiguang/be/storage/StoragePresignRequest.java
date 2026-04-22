package com.zhiguang.be.storage;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 对象存储预签名请求。
 */
public record StoragePresignRequest(
        @NotBlank(message = "scene 不能为空")
        @Pattern(
                regexp = "^(knowpost_content|knowpost_image|profile_avatar)$",
                message = "scene 只支持 knowpost_content、knowpost_image、profile_avatar"
        )
        String scene,

        @Size(max = 32, message = "postId 长度不能超过 32")
        String postId,

        @NotBlank(message = "filename 不能为空")
        @Size(max = 255, message = "filename 长度不能超过 255")
        String filename,

        @NotBlank(message = "contentType 不能为空")
        @Size(max = 128, message = "contentType 长度不能超过 128")
        String contentType,

        @Size(max = 16, message = "ext 长度不能超过 16")
        String ext
) {
}
