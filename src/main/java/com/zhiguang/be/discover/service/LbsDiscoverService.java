package com.zhiguang.be.discover.service;

import com.zhiguang.be.discover.model.NearbySearchRequest;
import com.zhiguang.be.discover.model.NearbySearchResponse;

/**
 * LBS 发现服务接口。
 * 对外定义附近搜索、位置写入和位置删除等核心能力。
 */
public interface LbsDiscoverService {

    /**
     * 执行附近搜索。
     * 根据请求中的坐标、半径、分页和类型信息返回附近内容列表。
     *
     * @param request 附近搜索请求
     * @return 搜索结果与分页信息
     */
    NearbySearchResponse searchNearby(NearbySearchRequest request);

    /**
     * 使用简化参数写入位置索引。
     * 当调用方没有额外元数据时，会自动补空并转发到完整重载方法。
     *
     * @param id 内容唯一标识
     * @param type 内容类型
     * @param lat 纬度
     * @param lng 经度
     */
    default void addLocation(String id, String type, Double lat, Double lng) {
        addLocation(id, type, lat, lng, null, null, null);
    }

    /**
     * 写入位置索引及扩展元数据。
     * 除 Geo 坐标外，还可同步保存标题、发布时间和点赞数等用于搜索排序的字段。
     *
     * @param id 内容唯一标识
     * @param type 内容类型
     * @param lat 纬度
     * @param lng 经度
     * @param title 内容标题
     * @param publishTime 发布时间时间戳
     * @param likeCount 点赞数
     */
    void addLocation(
        String id,
        String type,
        Double lat,
        Double lng,
        String title,
        Long publishTime,
        Integer likeCount
    );

    /**
     * 从位置索引中移除指定内容。
     *
     * @param id 内容唯一标识
     * @param type 内容类型
     */
    void removeLocation(String id, String type);
}