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

    /**
     * 从字节缓冲区中按大端序读取 int32 值。
     *
     * @param buffer 字节缓冲区
     * @param offset 起始偏移
     * @return 读取的 long 值
     */
    public static long readInt32BE(byte[] buffer, int offset) {
        if (buffer == null || buffer.length < offset + FIELD_SIZE) {
            return 0L;
        }
        long value = 0L;
        for (int i = 0; i < FIELD_SIZE; i++) {
            value = (value << 8) | (buffer[offset + i] & 0xFFL);
        }
        return value;
    }

    /**
     * 按大端序将 int32 值写入字节缓冲区。
     *
     * @param buffer 字节缓冲区
     * @param offset 起始偏移
     * @param value  要写入的值
     */
    public static void writeInt32BE(byte[] buffer, int offset, long value) {
        long safeValue = Math.max(0L, Math.min(value, 0xFFFF_FFFFL));
        buffer[offset] = (byte) ((safeValue >>> 24) & 0xFF);
        buffer[offset + 1] = (byte) ((safeValue >>> 16) & 0xFF);
        buffer[offset + 2] = (byte) ((safeValue >>> 8) & 0xFF);
        buffer[offset + 3] = (byte) (safeValue & 0xFF);
    }
}
