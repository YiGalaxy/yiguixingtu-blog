package com.yigalaxy.blog.article;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class ArticleController {
    private final ArticleQueryService articles;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;

    public ArticleController(ArticleQueryService articles, org.springframework.jdbc.core.JdbcTemplate jdbc) {
        this.articles = articles;
        this.jdbc = jdbc;
    }

    @GetMapping("/articles")
    public ArticlePage list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "12") int size,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Long categoryId,
            @RequestParam(required = false) Long tagId,
            @RequestParam(required = false) Integer year,
            @RequestParam(required = false) Integer month) {
        return articles.pagePublished(page, size, keyword, categoryId, tagId, year, month);
    }

    @GetMapping("/articles/{id}")
    public ArticleDetail detail(@PathVariable long id) {
        ArticleDetail article = articles.findPublished(id);
        if (article == null) throw new ArticleNotFoundException(id);
        return article;
    }

    @PostMapping("/articles/{id}/view")
    public ViewReceipt addView(@PathVariable long id) {
        if (jdbc.update("UPDATE article SET view_count = view_count + 1 WHERE id = ? AND status = 1 AND deleted = 0", id) == 0)
            throw new ArticleNotFoundException(id);
        return new ViewReceipt(true);
    }

    public record ArticlePage(
            List<ArticleSummary> records,
            long total,
            int page,
            int size,
            String keyword,
            Long categoryId,
            Long tagId,
            Integer year,
            Integer month) { }

    public record ArticleSummary(long id, String title, String summary, String categoryName, String createTime) { }
    public record ArticleDetail(long id, String title, String summary, String content, String cover,
                                Long categoryId, String categoryName, long viewCount,
                                String createTime, String updateTime) { }
    public record ViewReceipt(boolean recorded) { }
}
