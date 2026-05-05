package com.zhiguang.be.storage;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record StorageMultipartAbortRequest(
        @NotBlank(message = "objectKey is required")
        @Size(max = 512, message = "objectKey is too long")
        String objectKey,

        @NotBlank(message = "uploadId is required")
        @Size(max = 256, message = "uploadId is too long")
        String uploadId
) {
}
