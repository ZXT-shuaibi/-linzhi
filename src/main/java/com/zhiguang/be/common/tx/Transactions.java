package com.zhiguang.be.common.tx;

import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

public final class Transactions {

    private static final ThreadLocal<Boolean> RUNNING_AFTER_COMMIT = new ThreadLocal<Boolean>();

    private Transactions() {
    }

    public static void runAfterCommit(Runnable task) {
        if (task == null) {
            return;
        }
        if (Boolean.TRUE.equals(RUNNING_AFTER_COMMIT.get())
                || !TransactionSynchronizationManager.isSynchronizationActive()) {
            task.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                RUNNING_AFTER_COMMIT.set(Boolean.TRUE);
                try {
                    task.run();
                } finally {
                    RUNNING_AFTER_COMMIT.remove();
                }
            }
        });
    }
}
