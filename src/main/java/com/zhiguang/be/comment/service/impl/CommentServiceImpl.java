package com.zhiguang.be.comment.service.impl;

import com.zhiguang.be.comment.mapper.CommentMapper;
import com.zhiguang.be.comment.model.CommentItemData;
import com.zhiguang.be.comment.model.CommentPageData;
import com.zhiguang.be.comment.model.CommentRow;
import com.zhiguang.be.comment.model.CreateCommentRequest;
import com.zhiguang.be.comment.service.CommentService;
import com.zhiguang.be.common.exception.BusinessException;
import com.zhiguang.be.common.exception.ErrorCode;
import com.zhiguang.be.common.id.SnowflakeIdGenerator;
import com.zhiguang.be.content.dto.PostDetail;
import com.zhiguang.be.content.service.ContentService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

@Service
public class CommentServiceImpl implements CommentService {

    private final CommentMapper commentMapper;
    private final SnowflakeIdGenerator snowflakeIdGenerator;
    private final ContentService contentService;

    public CommentServiceImpl(
            CommentMapper commentMapper,
            SnowflakeIdGenerator snowflakeIdGenerator,
            ContentService contentService
    ) {
        this.commentMapper = commentMapper;
        this.snowflakeIdGenerator = snowflakeIdGenerator;
        this.contentService = contentService;
    }

    @Override
    @Transactional(readOnly = true)
    public CommentPageData listComments(long postId, Long viewerId, int page, int size) {
        ensureCommentablePost(postId, viewerId);
        int safePage = Math.max(1, page);
        int safeSize = Math.max(1, Math.min(size, 50));
        int offset = (safePage - 1) * safeSize;
        List<CommentRow> rows = commentMapper.listComments(postId, safeSize + 1, offset);
        boolean hasMore = rows.size() > safeSize;
        if (hasMore) {
            rows = rows.subList(0, safeSize);
        }
        return new CommentPageData(toItems(rows), new CommentPageData.CommentPageMeta(safePage, safeSize, rows.size()));
    }

    @Override
    @Transactional
    public CommentItemData createComment(long currentUserId, long postId, CreateCommentRequest request) {
        ensureCommentablePost(postId, currentUserId);
        String content = normalizeContent(request == null ? null : request.content());
        long commentId = snowflakeIdGenerator.nextId();
        commentMapper.insertComment(commentId, postId, currentUserId, content);
        CommentRow row = commentMapper.findById(commentId);
        return row == null ? new CommentItemData(
                String.valueOf(commentId),
                String.valueOf(postId),
                content,
                String.valueOf(currentUserId), null, null,
                null
        ) : toItem(row);
    }

    private void ensureCommentablePost(long postId, Long viewerId) {
        if (postId <= 0L) {
            throw new BusinessException(ErrorCode.NOT_FOUND, HttpStatus.NOT_FOUND, "帖子不存在或未发布");
        }
        PostDetail detail = contentService.getDetail(postId, viewerId);
        if (detail == null || !"published".equals(detail.status())) {
            throw new BusinessException(ErrorCode.NOT_FOUND, HttpStatus.NOT_FOUND, "帖子不存在或未发布");
        }
    }

    private String normalizeContent(String rawContent) {
        if (rawContent == null || rawContent.isBlank()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, HttpStatus.BAD_REQUEST, "评论内容不能为空");
        }
        String content = rawContent.trim();
        if (content.length() > 1000) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, HttpStatus.BAD_REQUEST, "评论内容不能超过 1000 字");
        }
        return content;
    }

    private List<CommentItemData> toItems(List<CommentRow> rows) {
        List<CommentItemData> items = new ArrayList<>(rows.size());
        for (CommentRow row : rows) {
            items.add(toItem(row));
        }
        return items;
    }

    private CommentItemData toItem(CommentRow row) {
        return new CommentItemData(
                String.valueOf(row.getCommentId()),
                String.valueOf(row.getPostId()),
                row.getContent(),
                String.valueOf(row.getUserId()),
                row.getNickname(),
                row.getAvatar(),
                row.getCreatedAt()
        );
    }
}
