package com.zhiguang.be.storage;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record StorageMultipartPart(
        @Min(value = 1, message = "partNumber must be at least 1")
        @Max(value = 10000, message = "partNumber must be at most 10000")
        int partNumber,

        @NotBlank(message = "etag is required")
        @Size(max = 128, message = "etag is too long")
        String etag
) {
}
