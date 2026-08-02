package com.zhiguang.be.social.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhiguang.be.common.id.SnowflakeIdGenerator;
import com.zhiguang.be.discover.service.LbsDiscoverService;
import com.zhiguang.be.feed.service.FeedCacheInvalidationService;
import com.zhiguang.be.social.SocialCounterSchema;
import com.zhiguang.be.social.SocialRedisKeys;
import com.zhiguang.be.social.kafka.CounterEvent;
import com.zhiguang.be.social.kafka.CounterEventProducer;
import com.zhiguang.be.social.mapper.SocialMapper;
import com.zhiguang.be.social.service.FollowService;
import com.zhiguang.be.social.service.UserSocialCounterService;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentCaptor.forClass;
import org.mockito.ArgumentCaptor;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class InteractionServiceImplTest {

    private StringRedisTemplate redisTemplate;
    private HashOperations<String, Object, Object> hashOperations;
    private ValueOperations<String, String> valueOperations;
    private SocialMapper socialMapper;
    private SnowflakeIdGenerator snowflakeIdGenerator;
    private CounterEventProducer counterEventProducer;
    private InteractionServiceImpl service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redisTemplate = mock(StringRedisTemplate.class);
        hashOperations = mock(HashOperations.class);
        valueOperations = mock(ValueOperations.class);
        socialMapper = mock(SocialMapper.class);
        snowflakeIdGenerator = mock(SnowflakeIdGenerator.class);
        counterEventProducer = mock(CounterEventProducer.class);
        when(redisTemplate.opsForHash()).thenReturn(hashOperations);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(redisTemplate.execute(
                any(DefaultRedisScript.class),
                eq(List.of(
                        SocialRedisKeys.counterEventDedupKey("counter-event-1"),
                        SocialRedisKeys.aggregateBucketKey("post", 1001L)
                )),
                eq("1"),
                eq(String.valueOf(Duration.ofDays(7).getSeconds())),
                eq(String.valueOf(SocialCounterSchema.IDX_LIKE)),
                eq("1")
        )).thenReturn(1L, 0L);

        ObjectProvider<LbsDiscoverService> lbsDiscoverServiceProvider = mock(ObjectProvider.class);
        ObjectProvider<FeedCacheInvalidationService> feedCacheInvalidationServiceProvider = mock(ObjectProvider.class);

        service = new InteractionServiceImpl(
                socialMapper,
                mock(FollowService.class),
                snowflakeIdGenerator,
                mock(UserSocialCounterService.class),
                counterEventProducer,
                lbsDiscoverServiceProvider,
                feedCacheInvalidationServiceProvider,
                redisTemplate,
                new ObjectMapper().findAndRegisterModules(),
                Runnable::run
        );
    }

    @Test
    void acceptAggregateEventShouldDeduplicateSameEventId() {
        CounterEvent event = CounterEvent.of(
                "post",
                "1001",
                "like",
                SocialCounterSchema.IDX_LIKE,
                7L,
                1,
                "counter-event-1"
        );

        service.acceptAggregateEvent(event);
        service.acceptAggregateEvent(event);

        verify(redisTemplate, times(2)).execute(
                any(DefaultRedisScript.class),
                eq(List.of(
                        SocialRedisKeys.counterEventDedupKey("counter-event-1"),
                        SocialRedisKeys.aggregateBucketKey("post", 1001L)
                )),
                eq("1"),
                eq(String.valueOf(Duration.ofDays(7).getSeconds())),
                eq(String.valueOf(SocialCounterSchema.IDX_LIKE)),
                eq("1")
        );
        verify(hashOperations, never()).increment(
                eq(SocialRedisKeys.aggregateBucketKey("post", 1001L)),
                eq(String.valueOf(SocialCounterSchema.IDX_LIKE)),
                eq(1)
        );
    }

    @Test
    void acceptAggregateEventShouldKeepLegacyEventWithoutEventId() {
        CounterEvent event = CounterEvent.of(
                "post",
                "1002",
                "fav",
                SocialCounterSchema.IDX_FAV,
                8L,
                1
        );

        service.acceptAggregateEvent(event);

        verify(hashOperations).increment(
                SocialRedisKeys.aggregateBucketKey("post", 1002L),
                String.valueOf(SocialCounterSchema.IDX_FAV),
                1
        );
    }

    @Test
    void acceptAggregateEventShouldFailWhenAtomicScriptReturnsNull() {
        CounterEvent event = CounterEvent.of(
                "post",
                "1003",
                "like",
                SocialCounterSchema.IDX_LIKE,
                9L,
                1,
                "counter-event-null"
        );
        when(redisTemplate.execute(
                any(DefaultRedisScript.class),
                eq(List.of(
                        SocialRedisKeys.counterEventDedupKey("counter-event-null"),
                        SocialRedisKeys.aggregateBucketKey("post", 1003L)
                )),
                eq("1"),
                eq(String.valueOf(Duration.ofDays(7).getSeconds())),
                eq(String.valueOf(SocialCounterSchema.IDX_LIKE)),
                eq("1")
        )).thenReturn(null);

        assertThrows(IllegalStateException.class, () -> service.acceptAggregateEvent(event));

        verify(hashOperations, never()).increment(
                eq(SocialRedisKeys.aggregateBucketKey("post", 1003L)),
                eq(String.valueOf(SocialCounterSchema.IDX_LIKE)),
                eq(1)
        );
    }

    @Test
    void acceptAggregateEventShouldFailWhenAtomicScriptReportsAggregateWriteError() {
        CounterEvent event = CounterEvent.of(
                "post",
                "1005",
                "like",
                SocialCounterSchema.IDX_LIKE,
                10L,
                1,
                "counter-event-script-error"
        );
        when(redisTemplate.execute(
                any(DefaultRedisScript.class),
                eq(List.of(
                        SocialRedisKeys.counterEventDedupKey("counter-event-script-error"),
                        SocialRedisKeys.aggregateBucketKey("post", 1005L)
                )),
                eq("1"),
                eq(String.valueOf(Duration.ofDays(7).getSeconds())),
                eq(String.valueOf(SocialCounterSchema.IDX_LIKE)),
                eq("1")
        )).thenReturn(-1L);

        assertThrows(IllegalStateException.class, () -> service.acceptAggregateEvent(event));

        verify(hashOperations, never()).increment(
                eq(SocialRedisKeys.aggregateBucketKey("post", 1005L)),
                eq(String.valueOf(SocialCounterSchema.IDX_LIKE)),
                eq(1)
        );
    }

    @Test
    void aggregateEventConsumeScriptShouldRollbackDedupWhenAggregateWriteFails() {
        String script = InteractionServiceImpl.AGGREGATE_EVENT_CONSUME_SCRIPT;

        Assertions.assertTrue(script.contains("redis.pcall('HINCRBY'"));
        Assertions.assertTrue(script.contains("redis.call('DEL', dedupKey)"));
        Assertions.assertTrue(script.contains("return -1"));
    }

    @Test
    void likeShouldPublishCounterEventWithOutboxEventId() {
        when(socialMapper.findPostSnapshot(1004L)).thenReturn(Map.of(
                "postId", 1004L,
                "creatorId", 20L,
                "status", "published",
                "visible", "public"
        ));
        when(socialMapper.reactivateInteraction(7L, "post", 1004L, "like")).thenReturn(0);
        when(socialMapper.existsActiveInteraction(7L, "post", 1004L, "like")).thenReturn(0);
        when(snowflakeIdGenerator.nextId()).thenReturn(90001L, 90002L);
        when(counterEventProducer.isEnabled()).thenReturn(true);
        when(counterEventProducer.publish(any(CounterEvent.class))).thenReturn(true);

        service.like(7L, "post", 1004L);

        verify(socialMapper).insertInteraction(90001L, 7L, "post", 1004L, "like");
        verify(socialMapper).insertOutboxEvent(
                eq(90002L),
                eq("interaction"),
                eq(1004L),
                eq("LIKE_CHANGED"),
                any(String.class)
        );
        ArgumentCaptor<CounterEvent> eventCaptor = forClass(CounterEvent.class);
        verify(counterEventProducer).publish(eventCaptor.capture());
        Assertions.assertEquals("90002", eventCaptor.getValue().getEventId());
    }

    @Test
    void likeShouldEnqueueProjectionWhenAsyncModeIsEnabled() {
        List<Runnable> submittedProjections = new ArrayList<Runnable>();
        ObjectProvider<LbsDiscoverService> lbsDiscoverServiceProvider = mock(ObjectProvider.class);
        ObjectProvider<FeedCacheInvalidationService> feedCacheInvalidationServiceProvider = mock(ObjectProvider.class);
        service = new InteractionServiceImpl(
                socialMapper,
                mock(FollowService.class),
                snowflakeIdGenerator,
                mock(UserSocialCounterService.class),
                counterEventProducer,
                lbsDiscoverServiceProvider,
                feedCacheInvalidationServiceProvider,
                redisTemplate,
                new ObjectMapper().findAndRegisterModules(),
                submittedProjections::add
        );
        ReflectionTestUtils.setField(service, "asyncProjectionEnabled", true);
        when(socialMapper.findPostSnapshot(1008L)).thenReturn(Map.of(
                "postId", 1008L,
                "creatorId", 20L,
                "status", "published",
                "visible", "public"
        ));
        when(socialMapper.reactivateInteraction(7L, "post", 1008L, "like")).thenReturn(0);
        when(socialMapper.existsActiveInteraction(7L, "post", 1008L, "like")).thenReturn(0);
        when(snowflakeIdGenerator.nextId()).thenReturn(93001L, 93002L);

        service.like(7L, "post", 1008L);

        Assertions.assertEquals(1, submittedProjections.size());
        verify(counterEventProducer, never()).publish(any(CounterEvent.class));
    }

    @Test
    void favoriteShouldSkipProjectionWhenMysqlOnlyModeIsEnabled() {
        List<Runnable> submittedProjections = new ArrayList<Runnable>();
        service = new InteractionServiceImpl(
                socialMapper,
                mock(FollowService.class),
                snowflakeIdGenerator,
                mock(UserSocialCounterService.class),
                counterEventProducer,
                mock(ObjectProvider.class),
                mock(ObjectProvider.class),
                redisTemplate,
                new ObjectMapper().findAndRegisterModules(),
                submittedProjections::add
        );
        ReflectionTestUtils.setField(service, "interactionProjectionEnabled", false);
        when(socialMapper.findPostSnapshot(1009L)).thenReturn(Map.of(
                "postId", 1009L,
                "creatorId", 20L,
                "status", "published",
                "visible", "public"
        ));
        when(socialMapper.reactivateInteraction(7L, "post", 1009L, "favorite")).thenReturn(0);
        when(socialMapper.existsActiveInteraction(7L, "post", 1009L, "favorite")).thenReturn(0);
        when(snowflakeIdGenerator.nextId()).thenReturn(94001L, 94002L);

        service.favorite(7L, "post", 1009L);

        Assertions.assertTrue(submittedProjections.isEmpty());
        verify(counterEventProducer, never()).publish(any(CounterEvent.class));
    }

    @Test
    void likeShouldFallbackThroughCounterEventDedupWhenKafkaPublishFails() {
        when(socialMapper.findPostSnapshot(1006L)).thenReturn(Map.of(
                "postId", 1006L,
                "creatorId", 20L,
                "status", "published",
                "visible", "public"
        ));
        when(socialMapper.reactivateInteraction(7L, "post", 1006L, "like")).thenReturn(0);
        when(socialMapper.existsActiveInteraction(7L, "post", 1006L, "like")).thenReturn(0);
        when(snowflakeIdGenerator.nextId()).thenReturn(91001L, 91002L);
        when(counterEventProducer.isEnabled()).thenReturn(true);
        when(counterEventProducer.publish(any(CounterEvent.class))).thenReturn(false);
        when(redisTemplate.execute(
                any(DefaultRedisScript.class),
                eq(List.of(
                        SocialRedisKeys.counterEventDedupKey("91002"),
                        SocialRedisKeys.aggregateBucketKey("post", 1006L)
                )),
                eq("1"),
                eq(String.valueOf(Duration.ofDays(7).getSeconds())),
                eq(String.valueOf(SocialCounterSchema.IDX_LIKE)),
                eq("1")
        )).thenReturn(1L);

        service.like(7L, "post", 1006L);

        verify(redisTemplate).execute(
                any(DefaultRedisScript.class),
                eq(List.of(
                        SocialRedisKeys.counterEventDedupKey("91002"),
                        SocialRedisKeys.aggregateBucketKey("post", 1006L)
                )),
                eq("1"),
                eq(String.valueOf(Duration.ofDays(7).getSeconds())),
                eq(String.valueOf(SocialCounterSchema.IDX_LIKE)),
                eq("1")
        );
        verify(hashOperations, never()).increment(
                eq(SocialRedisKeys.aggregateBucketKey("post", 1006L)),
                eq(String.valueOf(SocialCounterSchema.IDX_LIKE)),
                eq(1)
        );
    }

    @Test
    void likeShouldScheduleRetryWhenBitmapScriptReturnsNull() {
        when(socialMapper.findPostSnapshot(1007L)).thenReturn(Map.of(
                "postId", 1007L,
                "creatorId", 20L,
                "status", "published",
                "visible", "public"
        ));
        when(socialMapper.reactivateInteraction(7L, "post", 1007L, "like")).thenReturn(0);
        when(socialMapper.existsActiveInteraction(7L, "post", 1007L, "like")).thenReturn(0);
        when(snowflakeIdGenerator.nextId()).thenReturn(92001L, 92002L);
        when(counterEventProducer.isEnabled()).thenReturn(false);
        when(redisTemplate.execute(
                any(DefaultRedisScript.class),
                eq(List.of(SocialRedisKeys.bitmapKey("like", "post", 1007L, SocialRedisKeys.chunkOf(7L)))),
                eq(String.valueOf(SocialRedisKeys.bitOffsetOf(7L))),
                eq("add")
        )).thenReturn(null);

        service.like(7L, "post", 1007L);

        verify(valueOperations).set(
                SocialRedisKeys.interactionRetryKey("post", 1007L, 7L, "like"),
                "1",
                Duration.ofMinutes(5)
        );
    }
}
