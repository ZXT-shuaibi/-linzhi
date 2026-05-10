package com.zhiguang.be.comment.service;

import com.zhiguang.be.comment.model.CommentItemData;
import com.zhiguang.be.comment.model.CommentPageData;
import com.zhiguang.be.comment.model.CreateCommentRequest;

public interface CommentService {

    CommentPageData listComments(long postId, Long viewerId, int page, int size);

    CommentItemData createComment(long currentUserId, long postId, CreateCommentRequest request);
}
