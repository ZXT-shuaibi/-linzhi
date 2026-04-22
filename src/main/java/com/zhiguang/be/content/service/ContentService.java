package com.zhiguang.be.content.service;

import com.zhiguang.be.content.dto.PostPageData;
import com.zhiguang.be.content.dto.PostDetail;

/**
 * 内容模块对外服务接口。
 * 用于给其他模块暴露内容查询能力，避免跨模块直接依赖 mapper。
 */
public interface ContentService {

    /**
     * 查询公开内容流。
     *
     * @param viewerId 当前查看者 ID，匿名时可为 null
     * @param page 页码
     * @param size 每页大小
     * @return 公开内容分页结果
     */
    PostPageData getPublicFeed(String viewerId, int page, int size);

    /**
     * 查询文章详情。
     *
     * @param postId 文章 ID
     * @param viewerId 当前查看者 ID，匿名时可为 null
     * @return 文章详情
     */
    PostDetail getDetail(String postId, String viewerId);

    /**
     * 查询当前用户已发布内容。
     *
     * @param creatorId 当前用户 ID
     * @param page 页码
     * @param size 每页大小
     * @return 已发布内容分页结果
     */
    PostPageData getMyPublished(String creatorId, int page, int size);

    /**
     * 查询指定用户主页可见的已发布内容。
     * 会根据查看者身份决定是否展示 followers 可见内容。
     *
     * @param creatorId 目标用户 ID
     * @param viewerId 当前查看者 ID，匿名时可为 null
     * @param page 页码
     * @param size 每页大小
     * @return 个人主页内容分页结果
     */
    PostPageData getUserPublished(String creatorId, String viewerId, int page, int size);
}
