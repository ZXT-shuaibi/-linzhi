package com.zhiguang.be.profile.model;

import com.zhiguang.be.social.RelationStatusData;
import com.zhiguang.be.social.UserSocialCounterData;

import java.time.LocalDate;
import java.util.List;

/**
 * 个人资料聚合结果。
 * 同时承载基础信息、社交计数和查看者关系态，供“我的”与“TA 的主页”复用。
 */
public record ProfileData(
        String userId,
        String phone,
        String account,
        String email,
        String nickname,
        String avatar,
        String bio,
        String gender,
        LocalDate birthday,
        String school,
        List<String> tags,
        UserSocialCounterData socialCounters,
        RelationStatusData relationStatus,
        boolean self
) {
}
