package com.zhiguang.be.platform.model;

/**
 * 热点 Key 观测数据。
 */
public record PlatformHotKeyData(
        String key,
        int heat,
        String level
) {
}
