package com.zhiguang.be.social.service.impl;

import com.zhiguang.be.common.util.Numbers;
import com.zhiguang.be.social.FollowEventPayload;
import com.zhiguang.be.social.mapper.SocialMapper;
import com.zhiguang.be.social.service.UserSocialCounterService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 关系投影修复服务。
 * 定期以 following 主表为准修复 follower 投影、Redis 列表缓存和用户维计数，作为 Kafka/Canal 异常后的兜底对账。
 */
@Service
public class RelationProjectionRepairService {

    private static final Logger log = LoggerFactory.getLogger(RelationProjectionRepairService.class);

    private final SocialMapper socialMapper;
    private final RelationEventProcessor relationEventProcessor;
    private final UserSocialCounterService userSocialCounterService;
    private final boolean enabled;
    private final int batchSize;
    private final int maxBatchesPerRun;

    /**
     * 构造关系投影修复服务。
     *
     * @param socialMapper 社交模块 Mapper
     * @param relationEventProcessor 关系事件投影处理器
     * @param userSocialCounterService 用户维计数服务
     * @param enabled 是否启用修复任务
     * @param batchSize 单批扫描数量
     * @param maxBatchesPerRun 单轮最多扫描批次数
     */
    public RelationProjectionRepairService(
            SocialMapper socialMapper,
            RelationEventProcessor relationEventProcessor,
            UserSocialCounterService userSocialCounterService,
            @Value("${social.relation.repair.enabled:true}") boolean enabled,
            @Value("${social.relation.repair.batch-size:100}") int batchSize,
            @Value("${social.relation.repair.max-batches-per-run:20}") int maxBatchesPerRun
    ) {
        this.socialMapper = socialMapper;
        this.relationEventProcessor = relationEventProcessor;
        this.userSocialCounterService = userSocialCounterService;
        this.enabled = enabled;
        this.batchSize = Math.max(1, batchSize);
        this.maxBatchesPerRun = Math.max(1, maxBatchesPerRun);
    }

    /**
     * 定期修复关注关系投影。
     * 先补齐缺失 follower 投影，再清理陈旧 follower 投影，最后重建受影响用户的计数。
     */
    @Scheduled(fixedDelayString = "${social.relation.repair.fixed-delay-ms:60000}")
    public void repairRelationProjection() {
        if (!enabled) {
            return;
        }

        Set<Long> affectedUsers = new LinkedHashSet<Long>();
        int created = repairMissingFollowerProjections(affectedUsers);
        int removed = repairStaleFollowerProjections(affectedUsers);
        rebuildAffectedCounters(affectedUsers);

        if (created > 0 || removed > 0 || !affectedUsers.isEmpty()) {
            log.info("关系投影修复完成，补齐={}，清理={}，重建计数用户数={}",
                    created, removed, affectedUsers.size());
        }
    }

    /**
     * 补齐 following 主表中存在但 follower 投影缺失的关系。
     *
     * @param affectedUsers 受影响用户集合
     * @return 补齐数量
     */
    public void repairSingleRelation(FollowEventPayload payload) {
        if (payload == null) {
            return;
        }
        relationEventProcessor.process(payload);
        long followerId = Numbers.toLongOrZero(payload.getFollowerId());
        long followeeId = Numbers.toLongOrZero(payload.getFolloweeId());
        if (followerId > 0L) {
            userSocialCounterService.rebuildAllCounters(followerId);
        }
        if (followeeId > 0L && followeeId != followerId) {
            userSocialCounterService.rebuildAllCounters(followeeId);
        }
    }

    private int repairMissingFollowerProjections(Set<Long> affectedUsers) {
        int repaired = 0;
        long cursor = 0L;
        for (int i = 0; i < maxBatchesPerRun; i++) {
            List<Map<String, Object>> rows = socialMapper.listMissingFollowerProjections(cursor, batchSize);
            if (rows == null || rows.isEmpty()) {
                break;
            }

            for (Map<String, Object> row : rows) {
                long relationId = Numbers.toLong(row.get("relationId"));
                long followerId = Numbers.toLong(row.get("followerId"));
                long followeeId = Numbers.toLong(row.get("followeeId"));
                cursor = Math.max(cursor, relationId);

                relationEventProcessor.process(new FollowEventPayload(
                        "repair-create-" + relationId,
                        "FOLLOW_CREATED",
                        String.valueOf(followerId),
                        String.valueOf(followeeId),
                        String.valueOf(relationId),
                        Instant.now()
                ));
                affectedUsers.add(followerId);
                affectedUsers.add(followeeId);
                repaired++;
            }
        }
        return repaired;
    }

    /**
     * 清理 follower 投影中已经没有有效 following 主表关系的陈旧数据。
     *
     * @param affectedUsers 受影响用户集合
     * @return 清理数量
     */
    private int repairStaleFollowerProjections(Set<Long> affectedUsers) {
        int repaired = 0;
        long cursor = 0L;
        for (int i = 0; i < maxBatchesPerRun; i++) {
            List<Map<String, Object>> rows = socialMapper.listStaleFollowerProjections(cursor, batchSize);
            if (rows == null || rows.isEmpty()) {
                break;
            }

            for (Map<String, Object> row : rows) {
                long relationId = Numbers.toLong(row.get("relationId"));
                long followerId = Numbers.toLong(row.get("followerId"));
                long followeeId = Numbers.toLong(row.get("followeeId"));
                cursor = Math.max(cursor, relationId);

                relationEventProcessor.process(new FollowEventPayload(
                        "repair-remove-" + relationId,
                        "FOLLOW_REMOVED",
                        String.valueOf(followerId),
                        String.valueOf(followeeId),
                        String.valueOf(relationId),
                        Instant.now()
                ));
                affectedUsers.add(followerId);
                affectedUsers.add(followeeId);
                repaired++;
            }
        }
        return repaired;
    }

    /**
     * 重建受影响用户的社交计数。
     *
     * @param affectedUsers 受影响用户集合
     */
    private void rebuildAffectedCounters(Set<Long> affectedUsers) {
        for (Long userId : affectedUsers) {
            try {
                userSocialCounterService.rebuildAllCounters(userId);
            } catch (Exception ex) {
                log.warn("关系投影修复后重建用户计数失败，userId={}", userId, ex);
            }
        }
    }

    /**
     * 将数据库返回值转成 long。
     *
     * @param value 原始值
     * @return long 数值
     */
}
