package com.zhiguang.be.content.dto;

import com.zhiguang.be.social.RelationStatusData;
import com.zhiguang.be.social.UserSocialCounterData;

/**
 * 作者信息。
 * 这里统一承载作者基础资料、社交计数和当前查看者关系态，
 * 方便内容详情、内容卡片和 Feed 卡片直接复用。
 */
public record PostAuthor(
        String userId,
        String nickname,
        String avatar,
        UserSocialCounterData socialCounters,
        RelationStatusData relationStatus
) {
}
