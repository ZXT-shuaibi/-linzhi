package com.zhiguang.be.discover.service;

import java.util.List;
import java.util.Optional;

/**
 * 地图能力服务。
 * 为 discover 预留地理编码、逆地理编码和周边 POI 检索的统一接入点，
 * 后续可以平滑替换成高德、腾讯地图等外部服务实现。
 */
public interface DiscoverMapService {

    /**
     * 地址转坐标。
     */
    Optional<GeoPoint> geocode(String address, String city);

    /**
     * 坐标转地址。
     */
    Optional<ReverseGeoResult> reverseGeocode(Double lat, Double lng);

    /**
     * 周边 POI 搜索。
     */
    List<PoiItem> searchPoi(Double lat, Double lng, String keyword, Integer radius, Integer page, Integer size);

    /**
     * 当前地图能力是否可用。
     */
    boolean enabled();

    record GeoPoint(
            Double lat,
            Double lng,
            String address,
            String province,
            String city,
            String district,
            String adCode
    ) {
    }

    record ReverseGeoResult(
            Double lat,
            Double lng,
            String formattedAddress,
            String province,
            String city,
            String district,
            String township,
            String street,
            String adCode
    ) {
    }

    record PoiItem(
            String id,
            String name,
            String category,
            String address,
            Double lat,
            Double lng,
            Double distance
    ) {
    }
}
