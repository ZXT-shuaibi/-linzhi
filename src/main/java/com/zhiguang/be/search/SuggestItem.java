package com.zhiguang.be.search;

/**
 * 联想建议项。
 */
public record SuggestItem(
        String text,
        Double score,
        String source
) {
}
