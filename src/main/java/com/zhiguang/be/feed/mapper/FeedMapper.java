package com.zhiguang.be.feed.mapper;

import com.zhiguang.be.feed.FeedPostRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * Feed 模块 Mapper。
 * 负责首页浏览态所需的只读查询，不直接复用 content 域的 Mapper。
 */
@Mapper
public interface FeedMapper {

    /**
     * 查询首页公开内容候选集。
     *
     * @param limit 查询条数
     * @param offset 偏移量
     * @return 候选内容列表
     */
    List<FeedPostRow> listHomeFeedCandidates(@Param("limit") int limit, @Param("offset") int offset);

    /**
     * 统计首页公开内容总量。
     *
     * @return 公开内容总量
     */
    long countHomeFeed();
}
