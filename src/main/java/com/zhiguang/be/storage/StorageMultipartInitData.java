package com.zhiguang.be.storage;

import java.time.Instant;
import java.util.Map;

public record StorageMultipartInitData(
        String uploadId,
        String objectKey,
        String publicUrl,
        Instant expireAt,
        long partSize,
        Map<String, String> headers
) {
}
