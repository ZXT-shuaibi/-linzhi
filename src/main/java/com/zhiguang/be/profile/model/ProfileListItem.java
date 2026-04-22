package com.zhiguang.be.profile.model;

import com.zhiguang.be.social.RelationStatusData;
import com.zhiguang.be.social.UserSocialCounterData;

import java.time.Instant;

/**
 * 个人模块列表卡片条目。
 * 用于关注列表、粉丝列表等用户卡片场景。
 */
public record ProfileListItem(
        String userId,
        String nickname,
        String avatar,
        String bio,
        UserSocialCounterData socialCounters,
        RelationStatusData relationStatus,
        Instant followedAt,
        boolean self
) {
}
