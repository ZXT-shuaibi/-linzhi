package com.zhiguang.be.feed.service;

import com.zhiguang.be.feed.FeedData;

/**
 * 首页 Feed 服务接口。
 * 对齐 zhiguang 的 service 写法，控制器只依赖接口，不直接依赖实现类。
 */
public interface FeedService {

    /**
     * 查询首页 Feed。
     *
     * @param page 页码
     * @param size 每页大小
     * @param lat 可选纬度
     * @param lng 可选经度
     * @param geoHash 可选 GeoHash
     * @return 首页 Feed 分页结果
     */
    FeedData getHomeFeed(int page, int size, Double lat, Double lng, String geoHash, long viewerId);
}
