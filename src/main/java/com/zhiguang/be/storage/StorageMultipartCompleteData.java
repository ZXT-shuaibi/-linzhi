package com.zhiguang.be.storage;

public record StorageMultipartCompleteData(
        String objectKey,
        String publicUrl,
        String etag
) {
}
