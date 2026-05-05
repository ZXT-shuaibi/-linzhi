package com.zhiguang.be.storage;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record StorageMultipartInitRequest(
        @NotBlank(message = "scene is required")
        @Pattern(
                regexp = "^(knowpost_content|knowpost_image|profile_avatar)$",
                message = "unsupported storage scene"
        )
        String scene,

        @Size(max = 32, message = "postId is too long")
        String postId,

        @NotBlank(message = "filename is required")
        @Size(max = 255, message = "filename is too long")
        String filename,

        @NotBlank(message = "contentType is required")
        @Size(max = 128, message = "contentType is too long")
        String contentType,

        @Size(max = 16, message = "ext is too long")
        String ext,

        @NotNull(message = "fileSize is required")
        @Positive(message = "fileSize must be positive")
        Long fileSize
) {
}
