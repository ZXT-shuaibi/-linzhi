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
 * 对外暴露附近搜索和位置索引维护相关的 HTTP 接口。
 */
@RestController
@RequestMapping("/api/v1/discover")
public class DiscoverController {

    private final LbsDiscoverService lbsDiscoverService;
    private final DiscoverMapService discoverMapService;

    /**
     * 构造发现控制器并注入 LBS 服务。
     *
     * @param lbsDiscoverService 附近搜索与位置管理服务
     */
    public DiscoverController(LbsDiscoverService lbsDiscoverService, DiscoverMapService discoverMapService) {
        this.lbsDiscoverService = lbsDiscoverService;
        this.discoverMapService = discoverMapService;
    }

    /**
     * 查询指定坐标附近的内容。
     * 请求体中包含经纬度、搜索半径、分页参数和可选的类型过滤条件。
     *
     * @param request 附近搜索请求
     * @return 标准响应包装的搜索结果
     */
    @PostMapping("/nearby")
    public ApiResponse<NearbySearchResponse> searchNearby(@Valid @RequestBody NearbySearchRequest request) {
        return ApiResponse.success(lbsDiscoverService.searchNearby(request));
    }

    /**
     * 鎻愪緵鍖垮悕 GET 鏌ヨ鍏ュ彛锛屼究浜庡墠绔祻瑙堥〉鐩存帴鎸夋煡璇㈠弬鏁伴〉鍖栨媺鍙栭檮杩戠粨鏋溿€?
     *
     * @param lat 绾害
     * @param lng 缁忓害
     * @param radius 鎼滅储鍗婂緞锛堢背锛?
     * @param page 椤电爜
     * @param size 姣忛〉鏁伴噺
     * @param entityType 瀵瑰鏆撮湶鐨勫疄浣撶被鍨嬶紝褰撳墠鍏煎 post / merchant / mixed
     * @param tag 鍙€夋爣绛捐繃婊?
     * @return 鏍囧噯鍝嶅簲鍖呰鐨勬悳绱㈢粨鏋?
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
     * 除坐标外，也支持同时写入标题、发布时间和点赞数等辅助元数据。
     *
     * @param id 内容唯一标识
     * @param type 内容类型
     * @param lat 纬度
     * @param lng 经度
     * @param title 内容标题
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
        @RequestParam(required = false) String address,
        @RequestParam(required = false) Long publishTime,
        @RequestParam(required = false) Integer likeCount,
        @RequestParam(required = false) Integer favoriteCount
    ) {
        lbsDiscoverService.addLocation(id, type, lat, lng, title, null, null, address, null, null, null, null, publishTime, likeCount, favoriteCount);
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
