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
        return jdbc.query("SELECT id, name FROM category ORDER BY sort, id",
                (rs, row) -> new Option(rs.getLong("id"), rs.getString("name")));
    }

    @GetMapping("/tags")
    public List<Option> tags() {
        return jdbc.query("SELECT id, name FROM tag ORDER BY sort, id",
                (rs, row) -> new Option(rs.getLong("id"), rs.getString("name")));
    }

    @GetMapping("/site-settings")
    public SiteSettings settings() {
        return jdbc.queryForObject("SELECT * FROM site_setting WHERE id = 1", SiteController::mapSettings);
    }

    @GetMapping("/about")
    public AboutContent about() {
        return jdbc.queryForObject("SELECT * FROM about_page WHERE id = 1", SiteController::mapAbout);
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
    static SiteSettings mapSettings(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        return new SiteSettings(rs.getString("site_name"), rs.getString("announcement"),
                rs.getBoolean("comment_enabled"), rs.getInt("page_size"), rs.getString("notice_text"),
                rs.getString("copyright"), rs.getString("icp_number"), rs.getString("police_number"));
    }
    static AboutContent mapAbout(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        return new AboutContent(rs.getString("content"), rs.getString("updated_at"), rs.getString("nickname"),
                rs.getString("avatar"), rs.getString("email"), rs.getString("github"), rs.getString("wechat"), rs.getString("qq"));
    }
    public record SiteSettings(String siteName, String announcement, boolean commentEnabled, int pageSize,
                               String noticeText, String copyright, String icpNumber, String policeNumber) { }
    public record AboutContent(String content, String updatedAt, String nickname, String avatar,
                               String email, String github, String wechat, String qq) { }
    public record SiteStats(long articleCount, long viewCount, long categoryCount) { }
}
