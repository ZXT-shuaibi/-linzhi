package com.zhiguang.be.search;

import com.zhiguang.be.common.api.ApiResponse;
import com.zhiguang.be.auth.security.JwtSubjects;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 搜索控制器。
 */
@Validated
@RestController
@RequestMapping("/api/v1/search")
public class SearchController {

    private final SearchService searchService;

    public SearchController(SearchService searchService) {
        this.searchService = searchService;
    }

    /**
     * 搜索公开帖子。
     */
    @GetMapping("/posts")
    public ApiResponse<SearchPostsData> searchPosts(
            @RequestParam("q") @NotBlank(message = "q 不能为空") String q,
            @RequestParam(value = "page", defaultValue = "1") @Min(value = 1, message = "page 最小为 1") int page,
            @RequestParam(value = "size", defaultValue = "20") @Min(value = 1, message = "size 最小为 1") int size,
            @RequestParam(value = "searchAfter", required = false) String searchAfter,
            @RequestParam(value = "lat", required = false) Double lat,
            @RequestParam(value = "lng", required = false) Double lng,
            @RequestParam(value = "radius", required = false) Double radius,
            @RequestParam(value = "tag", required = false) String tag,
            @AuthenticationPrincipal Jwt jwt
    ) {
        return ApiResponse.success(searchService.searchPosts(q, page, size, searchAfter, JwtSubjects.optionalUserId(jwt), lat, lng, radius, tag));
    }

    /**
     * 搜索联想建议。
     */
    @GetMapping("/suggest")
    public ApiResponse<SuggestData> suggest(
            @RequestParam("q") @NotBlank(message = "q 不能为空") String q,
            @RequestParam(value = "size", defaultValue = "10") @Min(value = 1, message = "size 最小为 1") int size
    ) {
        return ApiResponse.success(searchService.suggest(q, size));
    }

}
