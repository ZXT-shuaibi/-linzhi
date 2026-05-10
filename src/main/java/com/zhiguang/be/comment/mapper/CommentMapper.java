package com.zhiguang.be.comment.mapper;

import com.zhiguang.be.comment.model.CommentRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface CommentMapper {

    void insertComment(
            @Param("commentId") long commentId,
            @Param("postId") long postId,
            @Param("userId") long userId,
            @Param("content") String content
    );

    List<CommentRow> listComments(
            @Param("postId") long postId,
            @Param("limit") int limit,
            @Param("offset") int offset
    );

    CommentRow findById(@Param("commentId") long commentId);
}
