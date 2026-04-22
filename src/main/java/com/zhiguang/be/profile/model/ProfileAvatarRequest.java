package com.zhiguang.be.profile.model;

import jakarta.validation.constraints.Size;

/**
 * 头像更新请求。
 * 支持直接传公开 URL，也支持传预签名上传后的 objectKey，优先使用 objectKey。
 */
public record ProfileAvatarRequest(
        @Size(max = 512, message = "avatarUrl 长度不能超过 512")
        String avatarUrl,
        @Size(max = 512, message = "objectKey 长度不能超过 512")
        String objectKey
) {
}
