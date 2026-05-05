package com.zhiguang.be.storage;

public record StorageObjectMetadata(
        String objectKey,
        String etag,
        long size,
        String contentType
) {
}
