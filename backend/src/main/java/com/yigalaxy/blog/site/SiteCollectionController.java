package com.yigalaxy.blog.site;

import java.util.List;
import java.util.Map;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class SiteCollectionController {
    private static final Map<String, String> TABLES = Map.of(
            "projects", "project", "favorites", "favorite", "links", "friend_link");
    private final JdbcTemplate jdbc;

    public SiteCollectionController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/{kind:projects|favorites|links}")
    public List<SiteItem> list(@PathVariable String kind) {
        String table = table(kind);
        return jdbc.query("SELECT id, title, description, primary_url, secondary_url, metadata, sort, " + (table.equals("project") ? "cover" : "NULL AS cover") + " FROM " + table + " ORDER BY sort, id",
                (rs, row) -> new SiteItem(rs.getLong("id"), rs.getString("title"), rs.getString("description"),
                        rs.getString("primary_url"), rs.getString("secondary_url"), rs.getString("metadata"), rs.getInt("sort"), rs.getString("cover")));
    }

    @GetMapping("/admin/collections/{kind:projects|favorites|links}")
    public List<SiteItem> adminList(@PathVariable String kind) { return list(kind); }

    @PostMapping("/admin/collections/{kind:projects|favorites|links}")
    @Transactional
    public SiteItem create(@PathVariable String kind, @Valid @RequestBody SiteItemForm form) {
        validateUrls(kind, form);
        String table = table(kind);
        jdbc.update("INSERT INTO " + table + " (title, description, primary_url, secondary_url, metadata, sort) VALUES (?, ?, ?, ?, ?, ?)",
                form.title().trim(), clean(form.description()), clean(form.primaryUrl()), clean(form.secondaryUrl()), clean(form.metadata()), form.sort());
        if (kind.equals("projects")) jdbc.update("UPDATE project SET cover = ? WHERE id = LAST_INSERT_ID()", clean(form.cover()));
        Long id = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        return find(table, id);
    }

    @PutMapping("/admin/collections/{kind:projects|favorites|links}/{id}")
    @Transactional
    public SiteItem update(@PathVariable String kind, @PathVariable long id, @Valid @RequestBody SiteItemForm form) {
        validateUrls(kind, form);
        String table = table(kind);
        if (jdbc.update("UPDATE " + table + " SET title = ?, description = ?, primary_url = ?, secondary_url = ?, metadata = ?, sort = ? WHERE id = ?",
                form.title().trim(), clean(form.description()), clean(form.primaryUrl()), clean(form.secondaryUrl()), clean(form.metadata()), form.sort(), id) == 0)
            throw new SiteItemNotFoundException(id);
        if (kind.equals("projects")) jdbc.update("UPDATE project SET cover = ? WHERE id = ?", clean(form.cover()), id);
        return find(table, id);
    }

    @DeleteMapping("/admin/collections/{kind:projects|favorites|links}/{id}")
    public void delete(@PathVariable String kind, @PathVariable long id) {
        if (jdbc.update("DELETE FROM " + table(kind) + " WHERE id = ?", id) == 0) throw new SiteItemNotFoundException(id);
    }

    private SiteItem find(String table, long id) {
        return jdbc.queryForObject("SELECT id, title, description, primary_url, secondary_url, metadata, sort, " + (table.equals("project") ? "cover" : "NULL AS cover") + " FROM " + table + " WHERE id = ?",
                (rs, row) -> new SiteItem(rs.getLong("id"), rs.getString("title"), rs.getString("description"),
                        rs.getString("primary_url"), rs.getString("secondary_url"), rs.getString("metadata"), rs.getInt("sort"), rs.getString("cover")), id);
    }

    private static String table(String kind) {
        String table = TABLES.get(kind);
        if (table == null) throw new IllegalArgumentException("未知内容类型");
        return table;
    }

    private static void validateUrls(String kind, SiteItemForm form) {
        validateUrl(form.primaryUrl());
        if (!kind.equals("favorites")) validateUrl(form.secondaryUrl());
        validateUrl(form.cover());
    }

    private static void validateUrl(String value) {
        if (value == null || value.isBlank()) return;
        String url = value.trim().toLowerCase(java.util.Locale.ROOT);
        if (!url.startsWith("https://") && !url.startsWith("http://")) throw new IllegalArgumentException("链接仅支持 http 或 https");
    }

    private static String clean(String value) { return value == null || value.isBlank() ? null : value.trim(); }

    public record SiteItem(long id, String title, String description, String primaryUrl,
                           String secondaryUrl, String metadata, int sort, String cover) { }
    public record SiteItemForm(@NotBlank @Size(max = 120) String title, @Size(max = 1000) String description,
                               @Size(max = 500) String primaryUrl, @Size(max = 500) String secondaryUrl,
                               @Size(max = 1000) String metadata, int sort, @Size(max = 500) String cover) { }
}
