package com.zhiguang.be.social.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhiguang.be.social.SocialCounterSchema;
import com.zhiguang.be.social.SocialRedisKeys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 计数灾难回放消费者。
 * 开启后从 earliest 回放历史计数事件，并按可选范围安全写回 Redis 计数快照。
 */
@Service
@ConditionalOnProperty(name = "social.rebuild.enabled", havingValue = "true")
public class CounterRebuildConsumer {

    private static final Logger log = LoggerFactory.getLogger(CounterRebuildConsumer.class);

    private final ObjectMapper objectMapper;
    private final StringRedisTemplate stringRedisTemplate;
    private final DefaultRedisScript<Long> incrementFieldScript;
    private final String targetType;
    private final long entityIdMin;
    private final long entityIdMax;
    private final boolean dryRun;
    private final long dedupTtlDays;
    private final AtomicLong received = new AtomicLong();
    private final AtomicLong applied = new AtomicLong();
    private final AtomicLong skipped = new AtomicLong();
    private final AtomicLong dryRunCount = new AtomicLong();
    private final AtomicLong duplicate = new AtomicLong();
    private final AtomicLong failed = new AtomicLong();

    /**
     * 构造灾难回放消费者，并加载回放范围和 dry-run 配置。
     *
     * @param objectMapper JSON 组件
     * @param stringRedisTemplate Redis 模板
     * @param targetType 非空时只回放该实体类型
     * @param entityIdMin 大于等于 0 时只回放不小于该值的实体 ID
     * @param entityIdMax 大于等于 0 时只回放不大于该值的实体 ID
     * @param dryRun true 时只解析和确认 offset，不写 Redis
     */
    public CounterRebuildConsumer(
            ObjectMapper objectMapper,
            StringRedisTemplate stringRedisTemplate,
            @Value("${social.rebuild.target-type:}") String targetType,
            @Value("${social.rebuild.entity-id-min:-1}") long entityIdMin,
            @Value("${social.rebuild.entity-id-max:-1}") long entityIdMax,
            @Value("${social.rebuild.dry-run:false}") boolean dryRun,
            @Value("${social.rebuild.dedup-ttl-days:7}") long dedupTtlDays) {
        if (entityIdMin >= 0 && entityIdMax >= 0 && entityIdMin > entityIdMax) {
            throw new IllegalArgumentException("social.rebuild.entity-id-min must be <= entity-id-max");
        }
        this.objectMapper = objectMapper;
        this.stringRedisTemplate = stringRedisTemplate;
        this.targetType = targetType == null || targetType.isBlank() ? null : targetType.trim();
        this.entityIdMin = entityIdMin;
        this.entityIdMax = entityIdMax;
        this.dryRun = dryRun;
        this.dedupTtlDays = Math.max(1L, dedupTtlDays);
        this.incrementFieldScript = new DefaultRedisScript<Long>();
        this.incrementFieldScript.setResultType(Long.class);
        this.incrementFieldScript.setScriptText(
                "local dedupKey = KEYS[1]\n"
                        + "local cntKey = KEYS[2]\n"
                        + "local seenValue = ARGV[1]\n"
                        + "local ttlSeconds = tonumber(ARGV[2])\n"
                        + "local schemaLen = tonumber(ARGV[3])\n"
                        + "local fieldSize = tonumber(ARGV[4])\n"
                        + "local idx = tonumber(ARGV[5])\n"
                        + "local delta = tonumber(ARGV[6])\n"
                        + "if not ttlSeconds or not schemaLen or not fieldSize or not idx or not delta then return -4 end\n"
                        + "if idx < 0 or idx >= schemaLen then return -3 end\n"
                        + "local function read32be(s, off)\n"
                        + "  local b = {string.byte(s, off + 1, off + 4)}\n"
                        + "  local n = 0\n"
                        + "  for i = 1, 4 do n = n * 256 + b[i] end\n"
                        + "  return n\n"
                        + "end\n"
                        + "local function write32be(n)\n"
                        + "  local t = {}\n"
                        + "  for i = 4, 1, -1 do t[i] = n % 256; n = math.floor(n / 256) end\n"
                        + "  return string.char(unpack(t))\n"
                        + "end\n"
                        + "local expectedLen = schemaLen * fieldSize\n"
                        + "local cntResult = redis.pcall('GET', cntKey)\n"
                        + "if type(cntResult) == 'table' and cntResult['err'] then return -2 end\n"
                        + "local cnt = cntResult\n"
                        + "if not cnt then\n"
                        + "  cnt = string.rep(string.char(0), expectedLen)\n"
                        + "elseif string.len(cnt) ~= schemaLen * fieldSize then\n"
                        + "  return -2\n"
                        + "end\n"
                        + "local marked = redis.call('SET', dedupKey, seenValue, 'NX', 'EX', ttlSeconds)\n"
                        + "if not marked then return 0 end\n"
                        + "local off = idx * fieldSize\n"
                        + "local v = read32be(cnt, off) + delta\n"
                        + "if v < 0 then v = 0 end\n"
                        + "local seg = write32be(v)\n"
                        + "cnt = string.sub(cnt, 1, off) .. seg .. string.sub(cnt, off + fieldSize + 1)\n"
                        + "local setResult = redis.pcall('SET', cntKey, cnt)\n"
                        + "if type(setResult) == 'table' and setResult['err'] then\n"
                        + "  redis.call('DEL', dedupKey)\n"
                        + "  return -5\n"
                        + "end\n"
                        + "return 1\n"
        );
    }

    /**
     * 消费历史计数事件；只有跳过、dry-run 或 Redis 写入成功后才提交位点。
     *
     * @param message Kafka 消息内容
     * @param acknowledgment 手动位点确认器
     * @throws Exception 失败时交给 Kafka 容器重试，避免跳过失败 offset
     */
    @KafkaListener(
            topics = CounterTopics.EVENTS,
            groupId = "${social.rebuild.group-id:counter-rebuild}",
            properties = {"auto.offset.reset=earliest"},
            containerFactory = "kafkaCounterRebuildListenerContainerFactory"
    )
    public void onMessage(String message, Acknowledgment acknowledgment) throws Exception {
        received.incrementAndGet();
        CounterEvent event = null;
        try {
            event = objectMapper.readValue(message, CounterEvent.class);
            if (!isInReplayScope(event)) {
                skipped.incrementAndGet();
                acknowledgment.acknowledge();
                return;
            }
            if (dryRun) {
                dryRunCount.incrementAndGet();
                acknowledgment.acknowledge();
                return;
            }

            if (applyCounterEvent(event)) {
                applied.incrementAndGet();
            } else {
                duplicate.incrementAndGet();
            }
            acknowledgment.acknowledge();
        } catch (Exception ex) {
            failed.incrementAndGet();
            if (event == null) {
                log.warn("replay counter event failed before parse", ex);
            } else {
                log.warn("replay counter event failed, entityType={}, entityId={}, metric={}",
                        event.getEntityType(), event.getEntityId(), event.getMetric(), ex);
            }
            throw ex;
        }
    }

    /**
     * 判断事件是否落在本次灾难回放的可选过滤范围内。
     */
    private boolean isInReplayScope(CounterEvent event) {
        if (targetType != null && !targetType.equals(event.getEntityType())) {
            return false;
        }
        if (!hasEntityIdRange()) {
            return true;
        }
        Long parsedEntityId = parseEntityId(event.getEntityId());
        if (parsedEntityId == null) {
            return false;
        }
        if (entityIdMin >= 0 && parsedEntityId < entityIdMin) {
            return false;
        }
        return entityIdMax < 0 || parsedEntityId <= entityIdMax;
    }

    /**
     * 执行 Redis Lua 写入，并校验脚本成功返回值。
     */
    private boolean applyCounterEvent(CounterEvent event) {
        validateCounterEventForReplay(event);
        String cntKey = SocialRedisKeys.entityCounterKey(event.getEntityType(), event.getEntityId());
        Long result = stringRedisTemplate.execute(
                incrementFieldScript,
                Arrays.asList(SocialRedisKeys.counterRebuildDedupKey(event.getEventId()), cntKey),
                "1",
                String.valueOf(Duration.ofDays(dedupTtlDays).getSeconds()),
                String.valueOf(SocialCounterSchema.SCHEMA_LEN),
                String.valueOf(SocialCounterSchema.FIELD_SIZE),
                String.valueOf(event.getIdx()),
                String.valueOf(event.getDelta())
        );
        if (result == null || result < 0L) {
            throw new IllegalStateException("counter rebuild redis script failed, result=" + result);
        }
        return result.longValue() > 0L;
    }

    /**
     * 校验灾难回放事件的最小安全字段，避免危险事件进入 Redis 原子脚本。
     */
    private void validateCounterEventForReplay(CounterEvent event) {
        if (event.getEventId() == null || event.getEventId().trim().isEmpty()) {
            throw new IllegalArgumentException("counter rebuild eventId is required");
        }
        if (event.getIdx() < 0 || event.getIdx() >= SocialCounterSchema.SCHEMA_LEN) {
            throw new IllegalArgumentException("counter rebuild idx out of schema range: " + event.getIdx());
        }
    }

    private boolean hasEntityIdRange() {
        return entityIdMin >= 0 || entityIdMax >= 0;
    }

    private Long parseEntityId(String entityId) {
        if (entityId == null) {
            return null;
        }
        try {
            return Long.parseLong(entityId);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    public long getReceivedCount() {
        return received.get();
    }

    public long getAppliedCount() {
        return applied.get();
    }

    public long getSkippedCount() {
        return skipped.get();
    }

    public long getDryRunCount() {
        return dryRunCount.get();
    }

    public long getDuplicateCount() {
        return duplicate.get();
    }

    public long getFailedCount() {
        return failed.get();
    }
}
