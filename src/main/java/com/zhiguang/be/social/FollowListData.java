package com.zhiguang.be.social;

import java.util.List;

/**
 * 关注列表或粉丝列表返回数据。
 */
public class FollowListData {

    private final List<FollowUserItem> items;
    private final PageMeta page;

    /**
     * 构造列表返回对象。
     *
     * @param items 用户条目列表
     * @param page 分页元数据
     */
    public FollowListData(List<FollowUserItem> items, PageMeta page) {
        this.items = items;
        this.page = page;
    }

    public List<FollowUserItem> getItems() {
        return items;
    }

    public PageMeta getPage() {
        return page;
    }
}
