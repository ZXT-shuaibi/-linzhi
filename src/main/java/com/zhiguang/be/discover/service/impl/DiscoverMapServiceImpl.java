package com.zhiguang.be.discover.service.impl;

import com.zhiguang.be.discover.config.DiscoverProperties;
import com.zhiguang.be.discover.service.DiscoverMapService;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Optional;

/**
 * 地图能力服务默认实现。
 * 当前只提供接入位和启用判断，后续接入高德、腾讯地图时，
 * 直接在这里补充 HTTP 调用和响应解析即可。
 */
@Service
public class DiscoverMapServiceImpl implements DiscoverMapService {

    private final DiscoverProperties discoverProperties;

    public DiscoverMapServiceImpl(DiscoverProperties discoverProperties) {
        this.discoverProperties = discoverProperties;
    }

    @Override
    public Optional<GeoPoint> geocode(String address, String city) {
        if (!enabled() || !StringUtils.hasText(address)) {
            return Optional.empty();
        }
        return Optional.empty();
    }

    @Override
    public Optional<ReverseGeoResult> reverseGeocode(Double lat, Double lng) {
        if (!enabled() || lat == null || lng == null) {
            return Optional.empty();
        }
        return Optional.empty();
    }

    @Override
    public List<PoiItem> searchPoi(Double lat, Double lng, String keyword, Integer radius, Integer page, Integer size) {
        if (!enabled() || lat == null || lng == null || !StringUtils.hasText(keyword)) {
            return List.of();
        }
        return List.of();
    }

    @Override
    public boolean enabled() {
        DiscoverProperties.MapProvider mapProvider = discoverProperties.getMapProvider();
        return mapProvider.isEnabled()
                && StringUtils.hasText(mapProvider.getProvider())
                && StringUtils.hasText(mapProvider.getApiKey());
    }
}
