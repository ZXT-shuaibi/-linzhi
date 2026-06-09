package com.zhiguang.be.feed;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FeedCacheKeysTest {

    @Test
    void keysShouldUseDefaultVersionWhenVersionIsBlank() {
        assertEquals(
                "feed:v1:page:home:global:1:20",
                FeedCacheKeys.homePageKey(" ", "global", 1, 20)
        );
        assertEquals("feed:v1:page:home:*", FeedCacheKeys.homePagePattern(null));
        assertEquals("feed:v1:fragment:post:1001", FeedCacheKeys.fragmentKey("", "1001"));
    }

    @Test
    void keysShouldNormalizeConfiguredVersion() {
        assertEquals(
                "feed:v_2:page:home:global:1:20",
                FeedCacheKeys.homePageKey(" V@2 ", "global", 1, 20)
        );
        assertEquals("feed:v_2:page:home:*", FeedCacheKeys.homePagePattern(" V@2 "));
        assertEquals("feed:v_2:fragment:post:1001", FeedCacheKeys.fragmentKey(" V@2 ", "1001"));
    }

    @Test
    void keysShouldNormalizeLocationSegment() {
        assertEquals(
                "feed:v1:page:home:geo_31_121__:1:20",
                FeedCacheKeys.homePageKey("v1", " Geo:31/121:* ", 1, 20)
        );
    }
}
