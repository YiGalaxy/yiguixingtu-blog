package com.yigalaxy.blog.site;

import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class SiteController {
    private final JdbcTemplate jdbc;

    public SiteController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/health")
    public Health health() {
        return new Health("ok");
    }

    @GetMapping("/categories")
    public List<Option> categories() {
        return jdbc.query("SELECT id, name FROM category ORDER BY sort, name",
                (rs, row) -> new Option(rs.getLong("id"), rs.getString("name")));
    }

    @GetMapping("/tags")
    public List<Option> tags() {
        return jdbc.query("SELECT id, name FROM tag ORDER BY sort, name",
                (rs, row) -> new Option(rs.getLong("id"), rs.getString("name")));
    }

    @GetMapping("/site-settings")
    public SiteSettings settings() {
        return jdbc.queryForObject("SELECT site_name, announcement, comment_enabled, page_size FROM site_setting WHERE id = 1",
                (rs, row) -> new SiteSettings(rs.getString("site_name"), rs.getString("announcement"),
                        rs.getBoolean("comment_enabled"), rs.getInt("page_size")));
    }

    @GetMapping("/about")
    public AboutContent about() {
        return jdbc.queryForObject("SELECT content, updated_at FROM about_page WHERE id = 1",
                (rs, row) -> new AboutContent(rs.getString("content"), rs.getString("updated_at")));
    }

    @GetMapping("/stats")
    public SiteStats stats() {
        return jdbc.queryForObject("SELECT (SELECT COUNT(*) FROM article WHERE status = 1 AND deleted = 0) AS article_count, "
                        + "(SELECT COALESCE(SUM(view_count), 0) FROM article WHERE status = 1 AND deleted = 0) AS view_count, "
                        + "(SELECT COUNT(*) FROM category) AS category_count",
                (rs, row) -> new SiteStats(rs.getLong("article_count"), rs.getLong("view_count"), rs.getLong("category_count")));
    }

    public record Health(String status) { }
    public record Option(long id, String name) { }
    public record SiteSettings(String siteName, String announcement, boolean commentEnabled, int pageSize) { }
    public record AboutContent(String content, String updatedAt) { }
    public record SiteStats(long articleCount, long viewCount, long categoryCount) { }
}
