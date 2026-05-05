package com.zhiguang.be.storage;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

public record StorageMultipartCompleteRequest(
        @NotBlank(message = "objectKey is required")
        @Size(max = 512, message = "objectKey is too long")
        String objectKey,

        @NotBlank(message = "uploadId is required")
        @Size(max = 256, message = "uploadId is too long")
        String uploadId,

        @NotEmpty(message = "parts is required")
        @Size(max = 10000, message = "too many parts")
        List<@Valid StorageMultipartPart> parts
) {
}
