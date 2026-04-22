package com.zhiguang.be.rag.service;

import com.zhiguang.be.content.dto.PostAuthor;
import com.zhiguang.be.content.dto.PostCard;
import com.zhiguang.be.content.dto.PostDetail;
import com.zhiguang.be.content.dto.PostLocation;
import com.zhiguang.be.content.dto.PostPageData;
import com.zhiguang.be.content.service.ContentService;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RagIndexServiceTest {

    @Test
    void searchShouldInspectMultiplePublicPages() {
        ContentService contentService = mock(ContentService.class);
        RagIndexService service = new RagIndexService(contentService);

        when(contentService.getPublicFeed(null, 1, 20)).thenReturn(new PostPageData(
                List.of(card("post-1", "First post", "Campus introduction", List.of("campus"))),
                1,
                20,
                true
        ));
        when(contentService.getPublicFeed(null, 2, 20)).thenReturn(new PostPageData(
                List.of(card("post-2", "Trade tips", "Payment guide for second-hand trade", List.of("trade", "payment"))),
                2,
                20,
                false
        ));
        when(contentService.getDetail("post-1", null)).thenReturn(detail(
                "post-1",
                "First post",
                "Campus introduction",
                List.of("campus"),
                "Alice",
                "Tongji campus"
        ));
        when(contentService.getDetail("post-2", null)).thenReturn(detail(
                "post-2",
                "Trade tips",
                "Payment guide for second-hand trade",
                List.of("trade", "payment"),
                "Bob",
                "Siping Road"
        ));

        RagIndexService.SearchResult result = service.search("payment", null, 5);

        assertFalse(result.hits().isEmpty());
        assertEquals("post-2", result.hits().get(0).postId());
        verify(contentService).getPublicFeed(null, 1, 20);
        verify(contentService).getPublicFeed(null, 2, 20);
    }

    @Test
    void searchShouldFallbackToSinglePostChunksWhenNoKeywordMatches() {
        ContentService contentService = mock(ContentService.class);
        RagIndexService service = new RagIndexService(contentService);

        when(contentService.getDetail("post-9", null)).thenReturn(detail(
                "post-9",
                "Dorm rules",
                "Lights out at 11 PM and keep shared areas clean.",
                List.of("campus", "dorm"),
                "Carol",
                "North dormitory"
        ));

        RagIndexService.SearchResult result = service.search("completely unrelated question", "post-9", 2);

        assertFalse(result.hits().isEmpty());
        assertTrue(result.hits().stream().allMatch(hit -> "post-9".equals(hit.postId())));
        assertTrue(result.references().size() <= 2);
    }

    private PostCard card(String postId, String title, String summary, List<String> tags) {
        return new PostCard(
                postId,
                title,
                summary,
                null,
                tags,
                null,
                0L,
                0L,
                false,
                false,
                "public",
                false,
                Instant.now()
        );
    }

    private PostDetail detail(
            String postId,
            String title,
            String summary,
            List<String> tags,
            String authorName,
            String address
    ) {
        return new PostDetail(
                postId,
                new PostAuthor("user-" + postId, authorName, null, null, null),
                "published",
                title,
                summary,
                "https://mock-oss.local/public/posts/" + postId + "/content.md",
                List.of(),
                tags,
                new PostLocation(31.0, 121.0, "wtw3sj", address),
                "public",
                "post",
                false,
                0L,
                0L,
                false,
                false,
                Instant.now(),
                Instant.now(),
                Instant.now()
        );
    }
}
