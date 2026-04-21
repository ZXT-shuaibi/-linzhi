package com.zhiguang.be.social.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhiguang.be.social.SocialCounterSchema;
import com.zhiguang.be.social.SocialRedisKeys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Service;

import java.util.Collections;

/**
 * 计数灾难回放消费者。
 * 只在显式开启后生效，按 earliest 从头回放历史事件，
 * 直接把增量折叠进实体计数快照。
 */
@Service
@ConditionalOnProperty(name = "social.rebuild.enabled", havingValue = "true")
public class CounterRebuildConsumer {

    private static final Logger log = LoggerFactory.getLogger(CounterRebuildConsumer.class);
    private final ObjectMapper objectMapper;
    private final StringRedisTemplate stringRedisTemplate;
    private final DefaultRedisScript<Long> incrementFieldScript;

    /**
     * 构造灾难回放消费者。
     *
     * @param objectMapper JSON 组件
     * @param stringRedisTemplate Redis 模板
     */
    public CounterRebuildConsumer(ObjectMapper objectMapper, StringRedisTemplate stringRedisTemplate) {
        this.objectMapper = objectMapper;
        this.stringRedisTemplate = stringRedisTemplate;
        this.incrementFieldScript = new DefaultRedisScript<Long>();
        this.incrementFieldScript.setResultType(Long.class);
        this.incrementFieldScript.setScriptText(
                "local cntKey = KEYS[1]\n"
                        + "local schemaLen = tonumber(ARGV[1])\n"
                        + "local fieldSize = tonumber(ARGV[2])\n"
                        + "local idx = tonumber(ARGV[3])\n"
                        + "local delta = tonumber(ARGV[4])\n"
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
                        + "local cnt = redis.call('GET', cntKey)\n"
                        + "if not cnt then cnt = string.rep(string.char(0), schemaLen * fieldSize) end\n"
                        + "local off = idx * fieldSize\n"
                        + "local v = read32be(cnt, off) + delta\n"
                        + "if v < 0 then v = 0 end\n"
                        + "local seg = write32be(v)\n"
                        + "cnt = string.sub(cnt, 1, off) .. seg .. string.sub(cnt, off + fieldSize + 1)\n"
                        + "redis.call('SET', cntKey, cnt)\n"
                        + "return 1\n"
        );
    }

    /**
     * 从最早位点开始消费历史计数事件并折叠进 Redis 计数快照。
     * 只有写入成功后才提交位点，避免回放缺口。
     *
     * @param message Kafka 消息内容
     * @param acknowledgment 手动位点确认器
     * @throws Exception 反序列化异常交给容器处理
     */
    @KafkaListener(
            topics = CounterTopics.EVENTS,
            groupId = "counter-rebuild",
            properties = {"auto.offset.reset=earliest"},
            containerFactory = "kafkaManualAckListenerContainerFactory"
    )
    public void onMessage(String message, Acknowledgment acknowledgment) throws Exception {
        CounterEvent event = objectMapper.readValue(message, CounterEvent.class);
        String cntKey = SocialRedisKeys.entityCounterKey(event.getEntityType(), event.getEntityId());

        try {
            stringRedisTemplate.execute(
                    incrementFieldScript,
                    Collections.singletonList(cntKey),
                    String.valueOf(SocialCounterSchema.SCHEMA_LEN),
                    String.valueOf(SocialCounterSchema.FIELD_SIZE),
                    String.valueOf(event.getIdx()),
                    String.valueOf(event.getDelta())
            );
            acknowledgment.acknowledge();
        } catch (Exception ex) {
            log.warn("replay counter event failed, entityType={}, entityId={}, metric={}",
                    event.getEntityType(), event.getEntityId(), event.getMetric(), ex);
        }
    }
}
