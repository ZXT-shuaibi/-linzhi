package com.zhiguang.be.content.dto;

import java.time.Instant;

/**
 * 预签名结果。
 */
public record StoragePresignData(
        String uploadUrl,
        String objectKey,
        Instant expireAt
) {
}
