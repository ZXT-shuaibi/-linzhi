package com.zhiguang.be.social.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhiguang.be.common.exception.BusinessException;
import com.zhiguang.be.common.exception.ErrorCode;
import com.zhiguang.be.common.id.SnowflakeIdGenerator;
import com.zhiguang.be.social.mapper.SocialMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FollowServiceImplRateLimitTest {

    @Test
    void followRateLimitShouldFailClosedWhenRedisCheckFails() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenThrow(new RuntimeException("redis down"));
        FollowServiceImpl service = new FollowServiceImpl(
                mock(SocialMapper.class),
                mock(SnowflakeIdGenerator.class),
                mock(RelationEventProcessor.class),
                repairProvider(),
                redisTemplate,
                new ObjectMapper(),
                true,
                100,
                1
        );

        BusinessException ex = assertThrows(BusinessException.class,
                () -> ReflectionTestUtils.invokeMethod(service, "enforceFollowRateLimit", 10001L));

        assertEquals(ErrorCode.RATE_LIMITED, ex.errorCode());
    }

    @SuppressWarnings("unchecked")
    private ObjectProvider<RelationProjectionRepairService> repairProvider() {
        ObjectProvider<RelationProjectionRepairService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        return provider;
    }
}
