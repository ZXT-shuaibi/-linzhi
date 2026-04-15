package com.zhiguang.be.content.dto;

/**
 * 作者信息。
 */
public record PostAuthor(
        String userId,
        String nickname,
        String avatar
) {
}
