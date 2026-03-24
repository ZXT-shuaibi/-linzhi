package com.zhiguang.be.auth.risk;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 内存登录限流实现。
 */
@Component
@ConditionalOnMissingBean(LoginRateLimitStore.class)
public class InMemoryLoginRateLimitStore implements LoginRateLimitStore {

    private final ConcurrentHashMap<String, Instant> lastAttemptAt = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, FailureCounter> failureCounter = new ConcurrentHashMap<>();

    @Override
    public boolean tryAcquire(String phone, String accessToken, Duration minInterval) {
        if (phone == null || phone.isBlank()) {
            return false;
        }

        Instant now = Instant.now();
        Instant current = lastAttemptAt.compute(phone, (key, previous) -> {
            if (previous == null) {
                return now;
            }
            if (Duration.between(previous, now).compareTo(minInterval) >= 0) {
                return now;
            }
            return previous;
        });
        return now.equals(current);
    }

    @Override
    public int incrementFailure(String phone, Duration ttl) {
        if (phone == null || phone.isBlank()) {
            return 0;
        }

        Instant now = Instant.now();
        FailureCounter counter = failureCounter.compute(phone, (key, old) -> {
            if (old == null || now.isAfter(old.expiresAt())) {
                return new FailureCounter(new AtomicInteger(1), now.plus(ttl));
            }
            old.count().incrementAndGet();
            return old;
        });
        return counter.count().get();
    }

    @Override
    public void resetFailures(String phone) {
        if (phone == null || phone.isBlank()) {
            return;
        }
        failureCounter.remove(phone);
    }

    private record FailureCounter(AtomicInteger count, Instant expiresAt) {
    }
}
