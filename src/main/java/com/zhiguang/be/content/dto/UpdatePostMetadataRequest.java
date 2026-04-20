package com.zhiguang.be.content.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 更新文章元数据请求。
 * 对齐 zhiguang，只保留 images，不再把 coverUrl 作为独立存储字段。
 */
public record UpdatePostMetadataRequest(
        @Size(max = 256, message = "标题长度不能超过 256")
        String title,

        @Size(max = 128, message = "摘要长度不能超过 128")
        String summary,

        List<@Size(max = 32, message = "标签长度不能超过 32") String> tags,

        List<@Size(max = 512, message = "图片地址长度不能超过 512") String> imageUrls,

        @Pattern(
                regexp = "^(public|followers|private)$",
                message = "visibility 只能是 public、followers、private"
        )
        String visibility,

        Boolean isTop,

        @Valid
        PostLocation location
) {
}
