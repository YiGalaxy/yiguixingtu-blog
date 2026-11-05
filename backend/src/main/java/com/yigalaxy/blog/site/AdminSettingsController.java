package com.yigalaxy.blog.site;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/site-settings")
public class AdminSettingsController {
    private final JdbcTemplate jdbc;

    public AdminSettingsController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @PutMapping
    public SiteController.SiteSettings update(@Valid @RequestBody SettingsForm form) {
        jdbc.update("UPDATE site_setting SET site_name = ?, announcement = ?, comment_enabled = ?, page_size = ? WHERE id = 1",
                form.siteName().trim(), clean(form.announcement()), form.commentEnabled(), form.pageSize());
        return jdbc.queryForObject("SELECT site_name, announcement, comment_enabled, page_size FROM site_setting WHERE id = 1",
                (rs, row) -> new SiteController.SiteSettings(rs.getString("site_name"), rs.getString("announcement"),
                        rs.getBoolean("comment_enabled"), rs.getInt("page_size")));
    }

    private static String clean(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    public record SettingsForm(@NotBlank @Size(max = 100) String siteName,
                               @Size(max = 500) String announcement,
                               boolean commentEnabled,
                               @Min(1) @Max(50) int pageSize) { }
}
