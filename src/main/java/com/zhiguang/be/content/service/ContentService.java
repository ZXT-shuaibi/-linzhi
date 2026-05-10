package com.zhiguang.be.content.service;

import com.zhiguang.be.content.dto.ConfirmContentRequest;
import com.zhiguang.be.content.dto.DraftData;
import com.zhiguang.be.content.dto.PostDetail;
import com.zhiguang.be.content.dto.PostPageData;
import com.zhiguang.be.content.dto.UpdatePostMetadataRequest;

/**
 * 内容模块对外服务接口。
 */
public interface ContentService {

    /**
     * 创建内容草稿。
     *
     * @param userId 当前用户 ID
     * @return 草稿数据
     */
    DraftData createDraft(long userId);

    /**
     * 确认正文上传成功，将草稿转为已确认状态。
     *
     * @param userId 当前用户 ID
     * @param postId 文章 ID
     * @param request 正文确认请求
     */
    void confirmContent(long userId, long postId, ConfirmContentRequest request);

    /**
     * 更新文章元数据（标题、摘要、标签、图片、位置、可见性、置顶）。
     *
     * @param userId 当前用户 ID
     * @param postId 文章 ID
     * @param request 元数据更新请求
     * @return 更新后的文章详情
     */
    PostDetail updateMetadata(long userId, long postId, UpdatePostMetadataRequest request);

    /**
     * 发布文章。要求正文和标题均已补全。
     *
     * @param userId 当前用户 ID
     * @param postId 文章 ID
     * @return 发布后的文章详情
     */
    PostDetail publish(long userId, long postId);

    /**
     * 更新文章置顶状态。仅已发布文章支持。
     *
     * @param userId 当前用户 ID
     * @param postId 文章 ID
     * @param isTop 是否置顶
     * @return 更新后的文章详情
     */
    PostDetail updateTop(long userId, long postId, boolean isTop);

    /**
     * 更新文章可见性。仅已发布文章支持。
     *
     * @param userId 当前用户 ID
     * @param postId 文章 ID
     * @param visibility 可见性（public / followers / private）
     * @return 更新后的文章详情
     */
    PostDetail updateVisibility(long userId, long postId, String visibility);

    /**
     * 删除文章（软删除）。
     *
     * @param userId 当前用户 ID
     * @param postId 文章 ID
     */
    void delete(long userId, long postId);

    /**
     * 查询公开内容流。
     *
     * @param viewerId 当前查看者 ID，匿名时为 null
     * @param page 页码
     * @param size 每页大小
     * @return 公开内容分页结果
     */
    PostPageData getPublicFeed(Long viewerId, int page, int size);

    /**
     * 查询文章详情。
     *
     * @param postId 文章 ID
     * @param viewerId 当前查看者 ID，匿名时为 null
     * @return 文章详情
     */
    PostDetail getDetail(long postId, Long viewerId);

    /**
     * 查询当前用户已发布内容。
     *
     * @param creatorId 当前用户 ID
     * @param page 页码
     * @param size 每页大小
     * @return 已发布内容分页结果
     */
    PostPageData getMyPublished(long creatorId, int page, int size);

    /**
     * 查询指定用户主页可见的已发布内容。
     * 本人可见全部，关注者可见 followers 内容，其他人仅可见 public。
     *
     * @param creatorId 目标用户 ID
     * @param viewerId 当前查看者 ID，匿名时为 null
     * @param page 页码
     * @param size 每页大小
     * @return 个人主页内容分页结果
     */
    PostPageData getUserPublished(long creatorId, Long viewerId, int page, int size);
}
