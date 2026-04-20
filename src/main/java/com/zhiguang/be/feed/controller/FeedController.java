package com.zhiguang.be.feed.controller;

import com.zhiguang.be.common.api.ApiResponse;
import com.zhiguang.be.feed.FeedData;
import com.zhiguang.be.feed.service.FeedService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 首页 Feed 控制器。
 */
@RestController
@Validated
@RequestMapping("/api/v1/feed")
public class FeedController {

    private final FeedService feedService;

    /**
     * 注入 Feed 服务。
     *
     * @param feedService Feed 服务
     */
    public FeedController(FeedService feedService) {
        this.feedService = feedService;
    }

    /**
     * 查询首页 Feed。
     * 一期先支持匿名浏览、分页和可选地理位置混排。
     *
     * @param page 页码
     * @param size 每页大小
     * @param lat 可选纬度
     * @param lng 可选经度
     * @param geoHash 可选 GeoHash
     * @return 首页 Feed 数据
     */
    @GetMapping("/home")
    public ApiResponse<FeedData> home(
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(50) int size,
            @RequestParam(required = false) Double lat,
            @RequestParam(required = false) Double lng,
            @RequestParam(required = false) String geoHash
    ) {
        return ApiResponse.success(feedService.getHomeFeed(page, size, lat, lng, geoHash));
    }
}
