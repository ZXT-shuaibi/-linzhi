package com.zhiguang.be.discover.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhiguang.be.cache.config.CacheProperties;
import com.zhiguang.be.cache.service.CacheService;
import com.zhiguang.be.common.exception.BusinessException;
import com.zhiguang.be.discover.config.DiscoverProperties;
import com.zhiguang.be.discover.model.NearbyItem;
import com.zhiguang.be.discover.model.NearbySearchRequest;
import com.zhiguang.be.discover.model.NearbySearchResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.geo.Circle;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResult;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.geo.Metrics;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.connection.RedisGeoCommands.GeoRadiusCommandArgs;
import org.springframework.data.redis.core.GeoOperations;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * LBS 发现服务单元测试。
 * 主要覆盖缓存命中、元数据组装、异常处理、类型清洗和索引维护等行为。
 */
class LbsDiscoverServiceImplTest {

    private StringRedisTemplate redisTemplate;
    private ValueOperations<String, String> valueOperations;
    private GeoOperations<String, String> geoOperations;
    private HashOperations<String, Object, Object> hashOperations;
    private CacheService cacheService;
    private LbsDiscoverServiceImpl service;

    /**
     * 初始化 Redis 相关 mock 和待测服务。
     */
    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redisTemplate = mock(StringRedisTemplate.class);
        valueOperations = mock(ValueOperations.class);
        geoOperations = mock(GeoOperations.class);
        hashOperations = mock(HashOperations.class);

        doReturn(valueOperations).when(redisTemplate).opsForValue();
        doReturn(geoOperations).when(redisTemplate).opsForGeo();
        doReturn(hashOperations).when(redisTemplate).opsForHash();

        cacheService = new CacheService(redisTemplate, new ObjectMapper(), new CacheProperties());
        service = new LbsDiscoverServiceImpl(redisTemplate, cacheService, new ObjectMapper(), new DiscoverProperties());
    }

    /**
     * 验证缓存命中时会直接返回缓存结果，不会再执行 Geo 查询。
     *
     * @throws Exception JSON 序列化异常
     */
    @Test
    void searchNearbyShouldReturnCachedResponseWhenCacheHit() throws Exception {
        NearbySearchRequest request = new NearbySearchRequest(31.2304, 121.4737, 500, 1, 20, "knowledge", null);
        NearbySearchResponse cached = new NearbySearchResponse(
            Collections.singletonList(new NearbyItem(
                    "id-1",
                    "post",
                    "Cached title",
                    "Cached summary",
                    "https://cdn.example/cached.png",
                    "Cached address",
                    Collections.singletonList("cached"),
                    "author-1",
                    "Cached author",
                    "https://cdn.example/avatar.png",
                    31.2305,
                    121.4738,
                    66.0,
                    1_700_000_000_000L,
                    8,
                    3,
                    0.92
            )),
            1,
            1,
            20
        );
        String cachedJson = new ObjectMapper().writeValueAsString(cached);

        when(valueOperations.get(anyString())).thenAnswer(invocation -> {
            String key = invocation.getArgument(0, String.class);
            return key.startsWith("lbs:version:") ? "3" : cachedJson;
        });

        NearbySearchResponse response = service.searchNearby(request);

        assertEquals(cached, response);
        verify(geoOperations, never()).radius(anyString(), any(Circle.class), any(GeoRadiusCommandArgs.class));
    }

    /**
     * 验证搜索结果会使用真实元数据，并返回正确的总数而不是当前页数量。
     */
    @Test
    void searchNearbyShouldUseRealMetadataAndCorrectTotalCount() {
        NearbySearchRequest request = new NearbySearchRequest(31.2000, 121.4000, 500, 1, 1, "knowledge", null);
        long publishTime = System.currentTimeMillis() - 3_600_000L;

        when(valueOperations.get(anyString())).thenAnswer(invocation -> {
            String key = invocation.getArgument(0, String.class);
            return key.startsWith("lbs:version:") ? "0" : null;
        });
        when(geoOperations.radius(eq("geo:knowledge"), any(Circle.class), any(GeoRadiusCommandArgs.class))).thenReturn(new GeoResults<>(Arrays.asList(
            geoResult("id-1", 121.4701, 31.2301, 80.0),
            geoResult("id-2", 121.4702, 31.2302, 220.0)
        )));
        doReturn(Arrays.asList(
            metadata("Real title 1", publishTime, 9, 31.2301, 121.4701),
            metadata("Real title 2", publishTime, 1, 31.2302, 121.4702)
        )).when(redisTemplate).executePipelined(any(SessionCallback.class));

        NearbySearchResponse response = service.searchNearby(request);

        assertEquals(2, response.total());
        assertEquals(1, response.items().size());
        NearbyItem firstItem = response.items().get(0);
        assertEquals("id-1", firstItem.id());
        assertEquals("Real title 1", firstItem.title());
        assertEquals(Double.valueOf(31.2301), firstItem.lat());
        assertEquals(Double.valueOf(121.4701), firstItem.lng());
        assertNotEquals(request.lat(), firstItem.lat());
        assertNotEquals(request.lng(), firstItem.lng());
        assertEquals(Long.valueOf(publishTime), firstItem.publishTime());
        assertEquals(Integer.valueOf(9), firstItem.likeCount());
        assertNotNull(firstItem.score());

        ArgumentCaptor<Circle> circleCaptor = ArgumentCaptor.forClass(Circle.class);
        ArgumentCaptor<String> cacheKeyCaptor = ArgumentCaptor.forClass(String.class);
        verify(geoOperations).radius(eq("geo:knowledge"), circleCaptor.capture(), any(GeoRadiusCommandArgs.class));
        assertEquals(0.5, circleCaptor.getValue().getRadius().getValue());
        assertEquals(Metrics.KILOMETERS, circleCaptor.getValue().getRadius().getMetric());
        verify(valueOperations).set(cacheKeyCaptor.capture(), anyString(), eq(Duration.ofSeconds(120)));
        assertTrue(cacheKeyCaptor.getValue().length() < 100);
        assertTrue(!cacheKeyCaptor.getValue().contains("31.2000"));
        assertTrue(!cacheKeyCaptor.getValue().contains("121.4000"));
    }

    /**
     * 验证缓存读取失败时会降级回源搜索，而不是直接中断请求。
     */
    @Test
    void searchNearbyShouldDegradeWhenCacheReadFails() {
        NearbySearchRequest request = new NearbySearchRequest(31.2304, 121.4737, 500, 1, 20, "knowledge", null);

        when(valueOperations.get(anyString())).thenThrow(new RuntimeException("redis unavailable"));
        when(geoOperations.radius(eq("geo:knowledge"), any(Circle.class), any(GeoRadiusCommandArgs.class))).thenReturn(new GeoResults<>(Collections.singletonList(
            geoResult("id-1", 121.4701, 31.2301, 120.0)
        )));
        doReturn(Collections.singletonList(singletonMetadata("title", "Fallback title")))
            .when(redisTemplate).executePipelined(any(SessionCallback.class));

        NearbySearchResponse response = assertDoesNotThrow(() -> service.searchNearby(request));

        assertEquals(1, response.total());
        assertEquals("Fallback title", response.items().get(0).title());
        verify(geoOperations).radius(eq("geo:knowledge"), any(Circle.class), any(GeoRadiusCommandArgs.class));
    }

    /**
     * 验证核心 Geo 查询失败时会抛出业务异常。
     */
    @Test
    void searchNearbyShouldFailWhenGeoQueryFails() {
        NearbySearchRequest request = new NearbySearchRequest(31.2304, 121.4737, 500, 1, 20, "knowledge", null);

        when(valueOperations.get(anyString())).thenAnswer(invocation -> {
            String key = invocation.getArgument(0, String.class);
            return key.startsWith("lbs:version:") ? "0" : null;
        });
        when(geoOperations.radius(eq("geo:knowledge"), any(Circle.class), any(GeoRadiusCommandArgs.class)))
            .thenThrow(new RuntimeException("geo query failed"));

        assertThrows(BusinessException.class, () -> service.searchNearby(request));
    }

    /**
     * 验证极端分页参数不会导致页偏移溢出成负数。
     */
    @Test
    void searchNearbyShouldSafelyHandleOverflowPageOffset() {
        NearbySearchRequest request = new NearbySearchRequest(31.2304, 121.4737, 500, Integer.MAX_VALUE, 100, "knowledge", null);

        when(valueOperations.get(anyString())).thenAnswer(invocation -> {
            String key = invocation.getArgument(0, String.class);
            return key.startsWith("lbs:version:") ? "0" : null;
        });
        when(geoOperations.radius(eq("geo:knowledge"), any(Circle.class), any(GeoRadiusCommandArgs.class))).thenReturn(new GeoResults<>(Collections.singletonList(
            geoResult("id-1", 121.4701, 31.2301, 120.0)
        )));
        doReturn(Collections.singletonList(singletonMetadata("title", "Overflow title")))
            .when(redisTemplate).executePipelined(any(SessionCallback.class));

        NearbySearchResponse response = assertDoesNotThrow(() -> service.searchNearby(request));

        assertEquals(1, response.total());
        assertEquals(0, response.items().size());
    }

    /**
     * 验证类型字段会在构造 Redis key 前被安全化处理。
     */
    @Test
    void searchNearbyShouldSanitizeTypeBeforeBuildingRedisKeys() {
        NearbySearchRequest request = new NearbySearchRequest(31.2304, 121.4737, 500, 1, 20, "User:Profile//", null);

        when(valueOperations.get(anyString())).thenAnswer(invocation -> {
            String key = invocation.getArgument(0, String.class);
            return key.startsWith("lbs:version:") ? "0" : null;
        });
        when(geoOperations.radius(eq("geo:user_profile__"), any(Circle.class), any(GeoRadiusCommandArgs.class))).thenReturn(new GeoResults<>(Collections.singletonList(
            geoResult("id-1", 121.4701, 31.2301, 120.0)
        )));
        doReturn(Collections.singletonList(singletonMetadata("title", "Sanitized type")))
            .when(redisTemplate).executePipelined(any(SessionCallback.class));

        NearbySearchResponse response = service.searchNearby(request);

        assertEquals(1, response.total());
        assertEquals("user_profile__", response.items().get(0).type());
        verify(geoOperations).radius(eq("geo:user_profile__"), any(Circle.class), any(GeoRadiusCommandArgs.class));
    }

    /**
     * 验证对外的 post/mixed 入口会回落到内部 knowledge 索引，并在响应中返回 post 口径。
     */
    @Test
    void searchNearbyShouldMapExternalPostTypeToKnowledgeIndex() {
        NearbySearchRequest request = new NearbySearchRequest(31.2304, 121.4737, 500, 1, 20, "post", null);

        when(valueOperations.get(anyString())).thenAnswer(invocation -> {
            String key = invocation.getArgument(0, String.class);
            return key.startsWith("lbs:version:") ? "0" : null;
        });
        when(geoOperations.radius(eq("geo:knowledge"), any(Circle.class), any(GeoRadiusCommandArgs.class))).thenReturn(new GeoResults<>(Collections.singletonList(
            geoResult("id-1", 121.4701, 31.2301, 120.0)
        )));
        doReturn(Collections.singletonList(singletonMetadata("title", "Mapped type")))
            .when(redisTemplate).executePipelined(any(SessionCallback.class));

        NearbySearchResponse response = service.searchNearby(request);

        assertEquals(1, response.total());
        assertEquals("post", response.items().get(0).type());
        verify(geoOperations).radius(eq("geo:knowledge"), any(Circle.class), any(GeoRadiusCommandArgs.class));
    }

    /**
     * 验证新增位置时会同步写入元数据并递增缓存版本。
     */
    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void addLocationShouldPersistMetadataAndBumpCacheVersion() {
        when(valueOperations.increment("lbs:version:knowledge")).thenReturn(1L);

        service.addLocation("id-1", "knowledge", 31.2301, 121.4701, "Real title", 1_700_000_000_000L, 12);

        ArgumentCaptor<Point> pointCaptor = ArgumentCaptor.forClass(Point.class);
        ArgumentCaptor<Map> metadataCaptor = ArgumentCaptor.forClass(Map.class);
        verify(geoOperations).add(eq("geo:knowledge"), pointCaptor.capture(), eq("id-1"));
        verify(hashOperations).putAll(eq("lbs:content:knowledge:id-1"), metadataCaptor.capture());
        verify(valueOperations).increment("lbs:version:knowledge");

        assertEquals(121.4701, pointCaptor.getValue().getX());
        assertEquals(31.2301, pointCaptor.getValue().getY());
        assertEquals("Real title", metadataCaptor.getValue().get("title"));
        assertEquals("1700000000000", metadataCaptor.getValue().get("publishTime"));
        assertEquals("12", metadataCaptor.getValue().get("likeCount"));
    }

    /**
     * 验证新增位置时也会对类型字段做安全化处理。
     */
    @Test
    void addLocationShouldSanitizeTypeBeforeWritingRedisKeys() {
        when(valueOperations.increment("lbs:version:user_profile__")).thenReturn(1L);

        service.addLocation("id-1", "User:Profile//", 31.2301, 121.4701, "Real title", 1_700_000_000_000L, 12);

        verify(geoOperations).add(eq("geo:user_profile__"), any(Point.class), eq("id-1"));
        verify(hashOperations).putAll(eq("lbs:content:user_profile__:id-1"), any(Map.class));
        verify(valueOperations).increment("lbs:version:user_profile__");
    }

    /**
     * 验证删除位置时会清除元数据并递增缓存版本。
     */
    @Test
    void removeLocationShouldDeleteMetadataAndBumpCacheVersion() {
        when(valueOperations.increment("lbs:version:knowledge")).thenReturn(2L);
        when(redisTemplate.delete("lbs:content:knowledge:id-1")).thenReturn(Boolean.TRUE);

        service.removeLocation("id-1", "knowledge");

        verify(geoOperations).remove("geo:knowledge", "id-1");
        verify(redisTemplate).delete("lbs:content:knowledge:id-1");
        verify(valueOperations).increment("lbs:version:knowledge");
    }

    /**
     * 构造带距离信息的 Geo 查询结果，用于测试附近搜索行为。
     *
     * @param id 内容 ID
     * @param lng 经度
     * @param lat 纬度
     * @param distanceMeters 距离，单位米
     * @return Geo 查询结果对象
     */
    private GeoResult<RedisGeoCommands.GeoLocation<String>> geoResult(String id, double lng, double lat, double distanceMeters) {
        return new GeoResult<>(
            new RedisGeoCommands.GeoLocation<>(id, new Point(lng, lat)),
            new Distance(distanceMeters / 1000.0, Metrics.KILOMETERS)
        );
    }

    /**
     * 构造一份完整元数据映射，用于模拟批量读取元数据后的结果。
     *
     * @param title 标题
     * @param publishTime 发布时间
     * @param likeCount 点赞数
     * @param lat 纬度
     * @param lng 经度
     * @return 元数据映射
     */
    private Map<Object, Object> metadata(String title, long publishTime, int likeCount, double lat, double lng) {
        Map<Object, Object> values = new HashMap<>();
        values.put("title", title);
        values.put("publishTime", String.valueOf(publishTime));
        values.put("likeCount", String.valueOf(likeCount));
        values.put("lat", String.valueOf(lat));
        values.put("lng", String.valueOf(lng));
        return values;
    }

    /**
     * 构造只包含单个字段的元数据映射。
     *
     * @param key 字段名
     * @param value 字段值
     * @return 元数据映射
     */
    private Map<Object, Object> singletonMetadata(String key, String value) {
        Map<Object, Object> values = new HashMap<>();
        values.put(key, value);
        return values;
    }
}
