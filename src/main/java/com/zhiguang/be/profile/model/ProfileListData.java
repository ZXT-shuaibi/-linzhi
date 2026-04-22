package com.zhiguang.be.profile.model;

import com.zhiguang.be.social.PageMeta;

import java.util.List;

/**
 * 个人模块列表结果。
 * 统一承接关注列表、粉丝列表这类用户卡片分页返回。
 */
public record ProfileListData(
        List<ProfileListItem> items,
        PageMeta page
) {
}
