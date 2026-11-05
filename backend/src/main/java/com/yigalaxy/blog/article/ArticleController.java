package com.yigalaxy.blog.article;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class ArticleController {
    @GetMapping("/articles")
    public ArticlePage list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "12") int size,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Long categoryId,
            @RequestParam(required = false) Long tagId) {
        int normalizedPage = Math.max(1, page);
        int normalizedSize = Math.max(1, Math.min(size, 50));
        return new ArticlePage(List.of(), 0, normalizedPage, normalizedSize, keyword, categoryId, tagId);
    }

    public record ArticlePage(
            List<ArticleSummary> records,
            long total,
            int page,
            int size,
            String keyword,
            Long categoryId,
            Long tagId) { }

    public record ArticleSummary(long id, String title, String summary, String categoryName, String createTime) { }
}
