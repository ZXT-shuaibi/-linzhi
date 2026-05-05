package com.zhiguang.be.storage;

import java.time.Instant;
import java.util.Map;

public record StorageMultipartPartPresignData(
        String uploadUrl,
        Instant expireAt,
        Map<String, String> headers
) {
}
