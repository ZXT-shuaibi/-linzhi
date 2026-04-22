package com.zhiguang.be.profile.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 头像更新请求。
 * 当前玩具项目先由前端传入头像 URL，后续若接 OSS/对象存储可再平滑替换。
 */
public record ProfileAvatarRequest(
        @NotBlank(message = "头像地址不能为空")
        @Size(max = 512, message = "头像地址长度不能超过 512")
        String avatarUrl
) {
}
