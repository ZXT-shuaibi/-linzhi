package com.zhiguang.be.rag.service;

public interface RagIndexOperations {

    int reindexSinglePost(String postId);

    void removeIndexedPost(String postId);

    int reindexPublicPosts();
}
