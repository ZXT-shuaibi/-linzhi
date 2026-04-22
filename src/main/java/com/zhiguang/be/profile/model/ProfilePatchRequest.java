package com.zhiguang.be.profile.model;

import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.util.List;

/**
 * 个人资料局部更新请求。
 * 仅更新请求体里显式提交的字段，未提交字段保持不变。
 */
public record ProfilePatchRequest(
        @Size(min = 1, max = 64, message = "昵称长度需在 1-64 之间")
        String nickname,

        @Size(max = 512, message = "个人简介长度不能超过 512")
        String bio,

        @Pattern(
                regexp = "^(?i)(male|female|other|unknown)$",
                message = "性别取值只能是 male、female、other、unknown"
        )
        String gender,

        @PastOrPresent(message = "生日不能晚于今天")
        LocalDate birthday,

        @Size(max = 128, message = "学校名称长度不能超过 128")
        String school,

        List<@Size(max = 32, message = "标签长度不能超过 32") String> tags
) {
}
