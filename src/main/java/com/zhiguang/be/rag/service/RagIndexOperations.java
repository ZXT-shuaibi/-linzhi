package com.zhiguang.be.rag.service;

public interface RagIndexOperations {

    int reindexSinglePost(String postId);

    int reindexPublicPosts();
}
