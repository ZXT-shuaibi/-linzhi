package com.zhiguang.be.search;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.Instant;
import java.util.List;

/**
 * 搜索模块数据访问接口。
 */
@Mapper
public interface SearchMapper {

    /**
     * 查询公开帖子搜索结果。
     */
    List<SearchPostRow> searchPosts(
            @Param("keywordLike") String keywordLike,
            @Param("tagLike") String tagLike,
            @Param("afterIsTop") Integer afterIsTop,
            @Param("afterPublishTime") Instant afterPublishTime,
            @Param("afterPostId") Long afterPostId,
            @Param("limit") int limit,
            @Param("offset") int offset
    );

    /**
     * 查询标题联想词。
     */
    List<String> suggestTitles(
            @Param("keywordLike") String keywordLike,
            @Param("limit") int limit
    );
}
