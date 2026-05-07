package com.zhiguang.be.discover.controller;

import com.zhiguang.be.common.api.ApiResponse;
import com.zhiguang.be.discover.model.NearbySearchRequest;
import com.zhiguang.be.discover.model.NearbySearchResponse;
import com.zhiguang.be.discover.service.DiscoverMapService;
import com.zhiguang.be.discover.service.LbsDiscoverService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 发现模块控制器。
 * 负责附近搜索、地图服务和位置索引管理。
 */
@RestController
@RequestMapping("/api/v1/discover")
public class DiscoverController {

    private final LbsDiscoverService lbsDiscoverService;
    private final DiscoverMapService discoverMapService;

    public DiscoverController(LbsDiscoverService lbsDiscoverService, DiscoverMapService discoverMapService) {
        this.lbsDiscoverService = lbsDiscoverService;
        this.discoverMapService = discoverMapService;
    }

    /**
     * 附近搜索（POST），支持更丰富的查询条件。
     *
     * @param request 附近搜索请求
     * @return 附近搜索结果
     */
    @PostMapping("/nearby")
    public ApiResponse<NearbySearchResponse> searchNearby(@Valid @RequestBody NearbySearchRequest request) {
        return ApiResponse.success(lbsDiscoverService.searchNearby(request));
    }

    /**
     * 提供匿名 GET 查询入口，便于前端浏览页直接按查询参数页化拉取附近结果。
     *
     * @param lat 纬度
     * @param lng 经度
     * @param radius 搜索半径（米）
     * @param page 页码
     * @param size 每页数量
     * @param entityType 对外暴露的实体类型，当前兼容 post / merchant / mixed
     * @param tag 可选标签过滤
     * @return 标准响应包装的搜索结果
     */
    @GetMapping("/nearby")
    public ApiResponse<NearbySearchResponse> searchNearbyByQuery(
            @RequestParam Double lat,
            @RequestParam Double lng,
            @RequestParam Integer radius,
            @RequestParam(defaultValue = "1") Integer page,
            @RequestParam(defaultValue = "20") Integer size,
            @RequestParam(name = "entityType", required = false) String entityType,
            @RequestParam(required = false) String tag
    ) {
        NearbySearchRequest request = new NearbySearchRequest(lat, lng, radius, page, size, entityType, tag);
        NearbySearchResponse response = lbsDiscoverService.searchNearby(request);
        return ApiResponse.success(response);
    }

    /**
     * 地址转坐标。
     * 当前先预留地图服务接入位，后续接入高德等外部服务后可直接返回真实结果。
     *
     * @param address 地址文本
     * @param city 城市（可选）
     * @return 地理坐标点
     */
    @GetMapping("/map/geocode")
    public ApiResponse<DiscoverMapService.GeoPoint> geocode(
            @RequestParam String address,
            @RequestParam(required = false) String city
    ) {
        return ApiResponse.success(discoverMapService.geocode(address, city).orElse(null));
    }

    /**
     * 坐标转地址。
     *
     * @param lat 纬度
     * @param lng 经度
     * @return 反转地理编码结果
     */
    @GetMapping("/map/reverse-geocode")
    public ApiResponse<DiscoverMapService.ReverseGeoResult> reverseGeocode(
            @RequestParam Double lat,
            @RequestParam Double lng
    ) {
        return ApiResponse.success(discoverMapService.reverseGeocode(lat, lng).orElse(null));
    }

    /**
     * 周边 POI 搜索。
     *
     * @param lat 纬度
     * @param lng 经度
     * @param keyword 搜索关键词
     * @param radius 搜索半径（米）
     * @param page 页码
     * @param size 每页数量
     * @return POI 列表
     */
    @GetMapping("/map/pois")
    public ApiResponse<java.util.List<DiscoverMapService.PoiItem>> searchPois(
            @RequestParam Double lat,
            @RequestParam Double lng,
            @RequestParam String keyword,
            @RequestParam(required = false) Integer radius,
            @RequestParam(required = false, defaultValue = "1") Integer page,
            @RequestParam(required = false, defaultValue = "10") Integer size
    ) {
        return ApiResponse.success(discoverMapService.searchPoi(lat, lng, keyword, radius, page, size));
    }

    /**
     * 新增或更新某条内容的地理位置索引。
     * 除坐标外，也支持同时写入标题、地址、作者和互动数等辅助元数据。
     *
     * @param id 内容唯一标识
     * @param type 内容类型
     * @param lat 纬度
     * @param lng 经度
     * @param title 内容标题
     * @param summary 内容摘要
     * @param coverUrl 封面图 URL
     * @param address 地址文本
     * @param authorId 作者 ID
     * @param authorName 作者昵称
     * @param authorAvatar 作者头像
     * @param tagsJson 标签 JSON
     * @param publishTime 发布时间时间戳
     * @param likeCount 点赞数
     * @param favoriteCount 收藏数
     * @return 标准成功响应
     */
    @PostMapping("/location")
    public ApiResponse<Void> addLocation(
        @RequestParam String id,
        @RequestParam String type,
        @RequestParam Double lat,
        @RequestParam Double lng,
        @RequestParam(required = false) String title,
        @RequestParam(required = false) String summary,
        @RequestParam(required = false) String coverUrl,
        @RequestParam(required = false) String address,
        @RequestParam(required = false) String authorId,
        @RequestParam(required = false) String authorName,
        @RequestParam(required = false) String authorAvatar,
        @RequestParam(required = false) String tagsJson,
        @RequestParam(required = false) Long publishTime,
        @RequestParam(required = false) Integer likeCount,
        @RequestParam(required = false) Integer favoriteCount
    ) {
        lbsDiscoverService.addLocation(id, type, lat, lng, title, summary, coverUrl, address, authorId, authorName, authorAvatar, tagsJson, publishTime, likeCount, favoriteCount);
        return ApiResponse.success(null);
    }

    /**
     * 删除某条内容的地理位置索引及其附属元数据。
     *
     * @param id 内容唯一标识
     * @param type 内容类型
     * @return 标准成功响应
     */
    @DeleteMapping("/location")
    public ApiResponse<Void> removeLocation(
        @RequestParam String id,
        @RequestParam String type
    ) {
        lbsDiscoverService.removeLocation(id, type);
        return ApiResponse.success(null);
    }
}
