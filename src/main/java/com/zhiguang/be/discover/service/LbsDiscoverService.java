package com.zhiguang.be.discover.service;

import com.zhiguang.be.discover.model.NearbySearchRequest;
import com.zhiguang.be.discover.model.NearbySearchResponse;

/**
 * LBS 发现服务接口。
 */
public interface LbsDiscoverService {

    /**
     * 执行附近搜索。
     */
    NearbySearchResponse searchNearby(NearbySearchRequest request);

    /**
     * 使用最小参数写入位置索引。
     */
    default void addLocation(String id, String type, Double lat, Double lng) {
        addLocation(id, type, lat, lng, null, null, null, null, null, null, null, null, null, null, null);
    }

    /**
     * 写入基础元数据。
     */
    default void addLocation(
            String id,
            String type,
            Double lat,
            Double lng,
        String title,
        Long publishTime,
        Integer likeCount
    ) {
        addLocation(id, type, lat, lng, title, null, null, null, null, null, null, null, publishTime, likeCount, null);
    }

    /**
     * 写入基础互动元数据。
     */
    default void addLocation(
            String id,
            String type,
            Double lat,
            Double lng,
            String title,
        Long publishTime,
        Integer likeCount,
        Integer favoriteCount
    ) {
        addLocation(id, type, lat, lng, title, null, null, null, null, null, null, null, publishTime, likeCount, favoriteCount);
    }

    /**
     * 写入位置索引和完整卡片元数据。
     */
    void addLocation(
            String id,
            String type,
            Double lat,
            Double lng,
            String title,
            String summary,
            String coverUrl,
            String address,
            String authorId,
            String authorName,
            String authorAvatar,
            String tagsJson,
            Long publishTime,
            Integer likeCount,
            Integer favoriteCount
    );

    /**
     * 增量刷新互动统计。
     */
    void incrementInteractionStats(String id, String type, int likeDelta, int favoriteDelta);

    /**
     * 删除位置索引。
     */
    void removeLocation(String id, String type);
}
