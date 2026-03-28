package com.zhiguang.be.discover.model;

public record NearbyItem(
    String id,
    String type,
    String title,
    Double lat,
    Double lng,
    Double distance,
    Long publishTime,
    Integer likeCount,
    Double score
) {}
