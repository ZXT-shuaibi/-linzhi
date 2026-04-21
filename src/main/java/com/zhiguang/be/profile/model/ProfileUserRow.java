package com.zhiguang.be.profile.model;

import java.time.LocalDate;

/**
 * profile 模块用户查询行对象。
 * 直接映射 users 表中个人主页会用到的字段。
 */
public record ProfileUserRow(
        long userId,
        String phone,
        String account,
        String nickname,
        String avatar,
        String bio,
        String gender,
        LocalDate birthday,
        String school,
        String tagsJson,
        String status
) {
}
