package com.zhiguang.be.social;

/**
 * 位图分片配置与辅助函数。
 * 使用固定 32K 位分片，避免单个 key 过大。
 */
public final class BitmapShard {

    public static final int CHUNK_SIZE = 32_768;

    private BitmapShard() {
    }

    /**
     * 计算用户所在位图分片号。
     *
     * @param userId 用户 ID
     * @return 分片号
     */
    public static long chunkOf(long userId) {
        return userId / CHUNK_SIZE;
    }

    /**
     * 计算用户在分片内的位偏移。
     *
     * @param userId 用户 ID
     * @return 位偏移
     */
    public static long bitOf(long userId) {
        return userId % CHUNK_SIZE;
    }
}
