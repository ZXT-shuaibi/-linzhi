package com.zhiguang.be.discover.model;

import java.util.List;

public record NearbySearchResponse(
    List<NearbyItem> items,
    Integer total,
    Integer page,
    Integer size
) {}
