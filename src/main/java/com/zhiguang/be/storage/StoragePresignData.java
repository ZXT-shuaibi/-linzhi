package com.zhiguang.be.storage;

import java.time.Instant;
import java.util.Map;

/**
 * 对象存储预签名结果。
 */
public record StoragePresignData(
        String uploadUrl,
        String objectKey,
        String publicUrl,
        Instant expireAt,
        Map<String, String> headers
) {
}
