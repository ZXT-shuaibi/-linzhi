package com.zhiguang.be.llm.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 帖子描述生成请求。
 */
public record KnowPostDescriptionRequest(
        @NotBlank(message = "content 不能为空")
        @Size(max = 20000, message = "content 长度不能超过 20000")
        String content
) {
}
