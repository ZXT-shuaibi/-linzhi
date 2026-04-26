package com.zhiguang.be.search;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 搜索索引文档原始行。
 */
public record SearchIndexDocumentRow(
        Long postId,
        String title,
        String summary,
        String tagsJson,
        String imgUrlsJson,
        Long authorId,
        String authorNickname,
        String authorAvatar,
        String authorTagJson,
        Integer isTop,
        Instant publishTime,
        Double latitude,
        Double longitude,
        String status,
        String visible
) {

    /**
     * 组装补全建议输入。
     */
    public List<String> suggestInputs(List<String> tags) {
        List<String> inputs = new ArrayList<String>();
        if (title != null && !title.isBlank()) {
            inputs.add(title.trim());
        }
        if (tags != null) {
            for (String tag : tags) {
                if (tag == null) {
                    continue;
                }
                String normalized = tag.trim();
                if (!normalized.isEmpty() && !inputs.contains(normalized)) {
                    inputs.add(normalized);
                }
            }
        }
        return inputs;
    }
}
