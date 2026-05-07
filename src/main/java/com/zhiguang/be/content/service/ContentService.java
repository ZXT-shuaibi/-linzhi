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

    // -- 写操作 --

    DraftData createDraft(String userId);

    void confirmContent(String userId, String postId, ConfirmContentRequest request);

    PostDetail updateMetadata(String userId, String postId, UpdatePostMetadataRequest request);

    PostDetail publish(String userId, String postId);

    PostDetail updateTop(String userId, String postId, boolean isTop);

    PostDetail updateVisibility(String userId, String postId, String visibility);

    void delete(String userId, String postId);

    // -- 读操作 --

    PostPageData getPublicFeed(String viewerId, int page, int size);

    PostDetail getDetail(String postId, String viewerId);

    PostPageData getMyPublished(String creatorId, int page, int size);

    PostPageData getUserPublished(String creatorId, String viewerId, int page, int size);
}
