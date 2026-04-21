package com.zhiguang.be.social;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 社交计数 schema 定义。
 * 参考 zhiguang 的计数口径，保留固定槽位，便于后续平滑扩展评论、转发等指标。
 */
public final class SocialCounterSchema {

    public static final String SCHEMA_ID = "s1";
    public static final int FIELD_SIZE = 4;
    public static final int SCHEMA_LEN = 5;

    public static final int IDX_LIKE = 1;
    public static final int IDX_FAV = 2;

    private static final Map<String, Integer> NAME_TO_IDX;
    public static final Set<String> SUPPORTED_METRICS;

    static {
        Map<String, Integer> mapping = new LinkedHashMap<String, Integer>();
        mapping.put("like", IDX_LIKE);
        mapping.put("fav", IDX_FAV);
        NAME_TO_IDX = Collections.unmodifiableMap(mapping);
        SUPPORTED_METRICS = NAME_TO_IDX.keySet();
    }

    private SocialCounterSchema() {
    }

    /**
     * 返回指标名对应的槽位索引。
     *
     * @param metric 指标名
     * @return 槽位索引，不支持时返回 null
     */
    public static Integer indexOf(String metric) {
        return NAME_TO_IDX.get(metric);
    }

    /**
     * 返回指标槽位在 SDS 中的字节偏移。
     *
     * @param idx 槽位索引
     * @return 字节偏移
     */
    public static int offsetOf(int idx) {
        return idx * FIELD_SIZE;
    }
}
