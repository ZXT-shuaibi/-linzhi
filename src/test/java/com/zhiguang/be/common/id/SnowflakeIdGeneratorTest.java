package com.zhiguang.be.common.id;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.RepeatedTest;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Snowflake ID 生成器测试。
 */
class SnowflakeIdGeneratorTest {

    @Test
    void shouldGenerateUniqueIds() {
        SnowflakeIdGenerator generator = new SnowflakeIdGenerator(1, 1);
        Set<Long> ids = new HashSet<>();

        for (int i = 0; i < 10000; i++) {
            long id = generator.nextId();
            assertTrue(ids.add(id), "ID should be unique");
        }

        assertEquals(10000, ids.size());
    }

    @Test
    void shouldGenerateIncreasingIds() {
        SnowflakeIdGenerator generator = new SnowflakeIdGenerator(1, 1);
        long previousId = 0;

        for (int i = 0; i < 1000; i++) {
            long id = generator.nextId();
            assertTrue(id > previousId, "ID should be increasing");
            previousId = id;
        }
    }

    @Test
    void shouldHandleConcurrentGeneration() throws InterruptedException {
        SnowflakeIdGenerator generator = new SnowflakeIdGenerator(1, 1);
        int threadCount = 10;
        int idsPerThread = 1000;

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);
        Set<Long> ids = new HashSet<>();
        AtomicInteger errorCount = new AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    for (int j = 0; j < idsPerThread; j++) {
                        long id = generator.nextId();
                        synchronized (ids) {
                            ids.add(id);
                        }
                    }
                } catch (Exception e) {
                    errorCount.incrementAndGet();
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();
        executor.shutdown();

        assertEquals(0, errorCount.get(), "Should not have errors");
        assertEquals(threadCount * idsPerThread, ids.size(), "All IDs should be unique");
    }

    @Test
    void shouldRejectInvalidWorkerId() {
        assertThrows(IllegalArgumentException.class, () -> {
            new SnowflakeIdGenerator(32, 1);
        });

        assertThrows(IllegalArgumentException.class, () -> {
            new SnowflakeIdGenerator(-1, 1);
        });
    }

    @Test
    void shouldRejectInvalidDatacenterId() {
        assertThrows(IllegalArgumentException.class, () -> {
            new SnowflakeIdGenerator(1, 32);
        });

        assertThrows(IllegalArgumentException.class, () -> {
            new SnowflakeIdGenerator(1, -1);
        });
    }

    @RepeatedTest(5)
    void shouldGenerateConsistentFormat() {
        SnowflakeIdGenerator generator = new SnowflakeIdGenerator(1, 1);
        long id = generator.nextId();

        String idStr = String.valueOf(id);
        assertTrue(idStr.length() >= 18 && idStr.length() <= 19,
            "ID string length should be 18-19 digits");
        assertTrue(id > 0, "ID should be positive");
    }
}
