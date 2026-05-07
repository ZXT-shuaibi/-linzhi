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

    DraftData createDraft(long userId);

    void confirmContent(long userId, long postId, ConfirmContentRequest request);

    PostDetail updateMetadata(long userId, long postId, UpdatePostMetadataRequest request);

    PostDetail publish(long userId, long postId);

    PostDetail updateTop(long userId, long postId, boolean isTop);

    PostDetail updateVisibility(long userId, long postId, String visibility);

    void delete(long userId, long postId);

    PostPageData getPublicFeed(Long viewerId, int page, int size);

    PostDetail getDetail(long postId, Long viewerId);

    PostPageData getMyPublished(long creatorId, int page, int size);

    PostPageData getUserPublished(long creatorId, Long viewerId, int page, int size);
}
