package com.zhiguang.be.social.service;

import com.zhiguang.be.social.InteractionActionData;
import com.zhiguang.be.social.InteractionSummary;

import java.util.List;
import java.util.Map;

/**
 * 点赞与收藏服务接口。
 * 对外定义互动动作、汇总查询以及批量回填能力。
 */
public interface InteractionService {

    /**
     * 对目标内容执行点赞。
     *
     * @param currentUserId 当前登录用户 ID
     * @param targetType 目标类型
     * @param targetId 目标 ID
     * @return 点赞动作结果
     */
    InteractionActionData like(long currentUserId, String targetType, long targetId);

    /**
     * 对目标内容取消点赞。
     *
     * @param currentUserId 当前登录用户 ID
     * @param targetType 目标类型
     * @param targetId 目标 ID
     * @return 取消点赞动作结果
     */
    InteractionActionData unlike(long currentUserId, String targetType, long targetId);

    /**
     * 对目标内容执行收藏。
     *
     * @param currentUserId 当前登录用户 ID
     * @param targetType 目标类型
     * @param targetId 目标 ID
     * @return 收藏动作结果
     */
    InteractionActionData favorite(long currentUserId, String targetType, long targetId);

    /**
     * 对目标内容取消收藏。
     *
     * @param currentUserId 当前登录用户 ID
     * @param targetType 目标类型
     * @param targetId 目标 ID
     * @return 取消收藏动作结果
     */
    InteractionActionData unfavorite(long currentUserId, String targetType, long targetId);

    /**
     * 查询单个目标内容的互动汇总。
     *
     * @param currentUserId 当前查看用户 ID
     * @param targetType 目标类型
     * @param targetId 目标 ID
     * @return 互动汇总
     */
    InteractionSummary summary(long currentUserId, String targetType, long targetId);

    /**
     * 批量查询多个目标内容的互动汇总。
     *
     * @param currentUserId 当前查看用户 ID
     * @param targetType 目标类型
     * @param targetIds 目标 ID 列表
     * @return 以目标 ID 为键的互动汇总映射
     */
    Map<String, InteractionSummary> summaryBatch(long currentUserId, String targetType, List<Long> targetIds);
}
