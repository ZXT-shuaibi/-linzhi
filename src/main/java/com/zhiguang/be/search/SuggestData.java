package com.zhiguang.be.search;

import java.util.List;

/**
 * 联想建议结果。
 */
public record SuggestData(
        List<SuggestItem> items
) {
}
