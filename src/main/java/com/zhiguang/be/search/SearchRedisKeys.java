package com.zhiguang.be.search;

/**
 * Search module Redis key factory.
 */
public final class SearchRedisKeys {

    private SearchRedisKeys() {
    }

    /**
     * Returns the idempotency marker key for a post outbox event projected to the search index.
     *
     * @param eventId outbox event id
     * @return Redis key
     */
    public static String postOutboxDedupKey(String eventId) {
        return "dedup:search-post:" + eventId;
    }
}
