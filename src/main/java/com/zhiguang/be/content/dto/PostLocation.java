package com.zhiguang.be.content.dto;

/**
 * 文章位置对象。
 */
public record PostLocation(
        Double lat,
        Double lng,
        String geoHash,
        String address
) {
}
