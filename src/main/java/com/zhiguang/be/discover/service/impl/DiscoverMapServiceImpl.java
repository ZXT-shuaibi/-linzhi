package com.zhiguang.be.discover.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhiguang.be.discover.config.DiscoverProperties;
import com.zhiguang.be.discover.service.DiscoverMapService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * 地图能力服务默认实现。
 * 当前按高德开放平台协议组织请求，后续如果切到腾讯地图等其他提供方，
 * 只需要替换这里的请求构造和响应解析逻辑。
 */
@Service
public class DiscoverMapServiceImpl implements DiscoverMapService {

    private static final Logger log = LoggerFactory.getLogger(DiscoverMapServiceImpl.class);

    private final DiscoverProperties discoverProperties;
    private final ObjectMapper objectMapper;
    private final RestTemplate restTemplate;

    public DiscoverMapServiceImpl(DiscoverProperties discoverProperties, ObjectMapper objectMapper) {
        this.discoverProperties = discoverProperties;
        this.objectMapper = objectMapper;
        this.restTemplate = createRestTemplate(discoverProperties.getMapProvider());
    }

    @Override
    public Optional<GeoPoint> geocode(String address, String city) {
        if (!enabled() || !isAmapProvider() || !StringUtils.hasText(address)) {
            return Optional.empty();
        }
        try {
            String response = restTemplate.getForObject(
                    UriComponentsBuilder.fromHttpUrl(discoverProperties.getMapProvider().getGeocodeEndpoint())
                            .queryParam("key", discoverProperties.getMapProvider().getApiKey())
                            .queryParam("address", address.trim())
                            .queryParamIfPresent("city", optionalText(city))
                            .build(true)
                            .toUri(),
                    String.class
            );
            JsonNode root = readTree(response);
            if (!isAmapSuccess(root)) {
                return Optional.empty();
            }
            JsonNode geocodes = root.path("geocodes");
            if (!geocodes.isArray() || geocodes.isEmpty()) {
                return Optional.empty();
            }
            JsonNode first = geocodes.get(0);
            double[] location = parseLocation(first.path("location").asText(null));
            if (location == null) {
                return Optional.empty();
            }
            return Optional.of(new GeoPoint(
                    location[1],
                    location[0],
                    textOrNull(first, "formatted_address"),
                    normalizeAmapText(first.get("province")),
                    normalizeAmapText(first.get("city")),
                    normalizeAmapText(first.get("district")),
                    textOrNull(first, "adcode")
            ));
        } catch (Exception ex) {
            log.warn("Discover geocode request failed. address={}, city={}", address, city, ex);
            return Optional.empty();
        }
    }

    @Override
    public Optional<ReverseGeoResult> reverseGeocode(Double lat, Double lng) {
        if (!enabled() || !isAmapProvider() || lat == null || lng == null) {
            return Optional.empty();
        }
        try {
            String response = restTemplate.getForObject(
                    UriComponentsBuilder.fromHttpUrl(discoverProperties.getMapProvider().getReverseGeocodeEndpoint())
                            .queryParam("key", discoverProperties.getMapProvider().getApiKey())
                            .queryParam("location", formatLocation(lng, lat))
                            .queryParam("extensions", "base")
                            .build(true)
                            .toUri(),
                    String.class
            );
            JsonNode root = readTree(response);
            if (!isAmapSuccess(root)) {
                return Optional.empty();
            }
            JsonNode regeocode = root.path("regeocode");
            if (regeocode.isMissingNode()) {
                return Optional.empty();
            }
            JsonNode addressComponent = regeocode.path("addressComponent");
            JsonNode streetNumber = addressComponent.path("streetNumber");
            return Optional.of(new ReverseGeoResult(
                    lat,
                    lng,
                    textOrNull(regeocode, "formatted_address"),
                    normalizeAmapText(addressComponent.get("province")),
                    normalizeAmapText(addressComponent.get("city")),
                    normalizeAmapText(addressComponent.get("district")),
                    normalizeAmapText(addressComponent.get("township")),
                    normalizeAmapText(streetNumber.get("street")),
                    textOrNull(addressComponent, "adcode")
            ));
        } catch (Exception ex) {
            log.warn("Discover reverse geocode request failed. lat={}, lng={}", lat, lng, ex);
            return Optional.empty();
        }
    }

    @Override
    public List<PoiItem> searchPoi(Double lat, Double lng, String keyword, Integer radius, Integer page, Integer size) {
        if (!enabled() || !isAmapProvider() || lat == null || lng == null || !StringUtils.hasText(keyword)) {
            return List.of();
        }
        int safeRadius = radius == null ? 1000 : Math.max(100, Math.min(radius.intValue(), 50000));
        int safePage = page == null ? 1 : Math.max(1, page.intValue());
        int safeSize = size == null ? 10 : Math.max(1, Math.min(size.intValue(), 20));
        try {
            String response = restTemplate.getForObject(
                    UriComponentsBuilder.fromHttpUrl(discoverProperties.getMapProvider().getPoiSearchEndpoint())
                            .queryParam("key", discoverProperties.getMapProvider().getApiKey())
                            .queryParam("keywords", keyword.trim())
                            .queryParam("location", formatLocation(lng, lat))
                            .queryParam("radius", safeRadius)
                            .queryParam("page_num", safePage)
                            .queryParam("page_size", safeSize)
                            .build(true)
                            .toUri(),
                    String.class
            );
            JsonNode root = readTree(response);
            if (!isAmapSuccess(root)) {
                return List.of();
            }

            JsonNode pois = resolvePoisNode(root);
            if (pois == null || !pois.isArray() || pois.isEmpty()) {
                return List.of();
            }

            List<PoiItem> items = new ArrayList<PoiItem>(pois.size());
            for (JsonNode poi : pois) {
                double[] location = parseLocation(textOrNull(poi, "location"));
                Double poiLat = location == null ? null : location[1];
                Double poiLng = location == null ? null : location[0];
                items.add(new PoiItem(
                        textOrNull(poi, "id"),
                        textOrNull(poi, "name"),
                        firstNonBlank(
                                textOrNull(poi, "type"),
                                textOrNull(poi, "category")
                        ),
                        textOrNull(poi, "address"),
                        poiLat,
                        poiLng,
                        asDouble(poi.get("distance"))
                ));
            }
            return items;
        } catch (Exception ex) {
            log.warn("Discover poi search request failed. lat={}, lng={}, keyword={}", lat, lng, keyword, ex);
            return List.of();
        }
    }

    @Override
    public boolean enabled() {
        DiscoverProperties.MapProvider mapProvider = discoverProperties.getMapProvider();
        return mapProvider.isEnabled()
                && StringUtils.hasText(mapProvider.getProvider())
                && StringUtils.hasText(mapProvider.getApiKey());
    }

    private RestTemplate createRestTemplate(DiscoverProperties.MapProvider mapProvider) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Math.max(1, mapProvider.getConnectTimeoutSeconds()) * 1000);
        factory.setReadTimeout(Math.max(1, mapProvider.getReadTimeoutSeconds()) * 1000);
        return new RestTemplate(factory);
    }

    private boolean isAmapProvider() {
        String provider = discoverProperties.getMapProvider().getProvider();
        return "amap".equalsIgnoreCase(provider) || "gaode".equalsIgnoreCase(provider);
    }

    private JsonNode readTree(String response) throws Exception {
        if (!StringUtils.hasText(response)) {
            return objectMapper.createObjectNode();
        }
        return objectMapper.readTree(response);
    }

    private boolean isAmapSuccess(JsonNode root) {
        String status = textOrNull(root, "status");
        String infoCode = textOrNull(root, "infocode");
        return "1".equals(status) || "10000".equals(infoCode);
    }

    private JsonNode resolvePoisNode(JsonNode root) {
        JsonNode pois = root.get("pois");
        if (pois != null && pois.isArray()) {
            return pois;
        }
        JsonNode data = root.get("data");
        if (data != null && data.isArray()) {
            return data;
        }
        return null;
    }

    private double[] parseLocation(String location) {
        if (!StringUtils.hasText(location)) {
            return null;
        }
        String[] parts = location.split(",");
        if (parts.length != 2) {
            return null;
        }
        try {
            return new double[]{
                    Double.parseDouble(parts[0].trim()),
                    Double.parseDouble(parts[1].trim())
            };
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private String formatLocation(Double lng, Double lat) {
        return String.format(Locale.ROOT, "%.6f,%.6f", lng.doubleValue(), lat.doubleValue());
    }

    private Optional<String> optionalText(String value) {
        return StringUtils.hasText(value) ? Optional.of(value.trim()) : Optional.empty();
    }

    private String textOrNull(JsonNode parent, String fieldName) {
        if (parent == null) {
            return null;
        }
        JsonNode field = parent.get(fieldName);
        return normalizeAmapText(field);
    }

    private String normalizeAmapText(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isArray()) {
            for (JsonNode item : node) {
                String value = normalizeAmapText(item);
                if (StringUtils.hasText(value)) {
                    return value;
                }
            }
            return null;
        }
        String value = node.asText(null);
        if (!StringUtils.hasText(value) || "[]".equals(value) || "null".equalsIgnoreCase(value)) {
            return null;
        }
        return value.trim();
    }

    private Double asDouble(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isNumber()) {
            return node.asDouble();
        }
        String value = node.asText(null);
        if (!StringUtils.hasText(value)) {
            return null;
        }
        try {
            return Double.valueOf(value.trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private String firstNonBlank(String first, String second) {
        if (StringUtils.hasText(first)) {
            return first;
        }
        return StringUtils.hasText(second) ? second : null;
    }
}
