package com.zhiguang.be.storage;

public interface StorageOperations {

    StoragePresignData createPresign(long currentUserId, StoragePresignRequest request);

    StorageMultipartInitData initiateMultipartUpload(long currentUserId, StorageMultipartInitRequest request);

    StorageMultipartPartPresignData createMultipartPartPresign(
            long currentUserId,
            StorageMultipartPartPresignRequest request
    );

    StorageMultipartCompleteData completeMultipartUpload(long currentUserId, StorageMultipartCompleteRequest request);

    void abortMultipartUpload(long currentUserId, StorageMultipartAbortRequest request);
}
