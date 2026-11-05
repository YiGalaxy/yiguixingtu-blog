package com.yigalaxy.blog.article;

import java.sql.Statement;
import java.util.List;
import java.util.Objects;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/articles")
public class AdminArticleController {
    private final JdbcTemplate jdbc;

    public AdminArticleController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping
    public AdminArticlePage list(@RequestParam(defaultValue = "1") int page,
                                 @RequestParam(defaultValue = "20") int size,
                                 @RequestParam(required = false) Integer status) {
        int currentPage = Math.max(1, page);
        int pageSize = Math.max(1, Math.min(size, 100));
        String where = status == null ? "a.deleted = 0" : "a.deleted = 0 AND a.status = ?";
        Object[] countArgs = status == null ? new Object[]{} : new Object[]{status};
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM article a WHERE " + where, Long.class, countArgs);
        Object[] pageArgs = status == null
                ? new Object[]{(currentPage - 1) * pageSize, pageSize}
                : new Object[]{status, (currentPage - 1) * pageSize, pageSize};
        List<AdminArticle> records = jdbc.query(
                "SELECT a.id, a.title, a.summary, a.category_id, c.name AS category_name, a.status, a.is_top, "
                        + "a.view_count, a.created_at, a.updated_at FROM article a "
                        + "LEFT JOIN category c ON c.id = a.category_id WHERE " + where
                        + " ORDER BY a.updated_at DESC, a.id DESC LIMIT ?, ?",
                (rs, row) -> new AdminArticle(rs.getLong("id"), rs.getString("title"), rs.getString("summary"),
                        (Long) rs.getObject("category_id"), rs.getString("category_name"), rs.getInt("status"),
                        rs.getBoolean("is_top"), rs.getLong("view_count"), rs.getString("created_at"), rs.getString("updated_at")),
                pageArgs);
        return new AdminArticlePage(records, total == null ? 0 : total, currentPage, pageSize);
    }

    @GetMapping("/{id}")
    public ArticleEditForm detail(@PathVariable long id) {
        var found = jdbc.query("SELECT id, title, summary, content, cover, category_id, status, is_top FROM article WHERE id = ? AND deleted = 0",
                (rs, row) -> new ArticleEditForm(rs.getLong("id"), rs.getString("title"), rs.getString("summary"),
                        rs.getString("content"), rs.getString("cover"), (Long) rs.getObject("category_id"),
                        rs.getInt("status"), rs.getBoolean("is_top"), jdbc.query(
                        "SELECT tag_id FROM article_tag WHERE article_id = ? ORDER BY tag_id", (tagRs, tagRow) -> tagRs.getLong(1), id)), id);
        return found.stream().findFirst().orElseThrow(() -> new ArticleNotFoundException(id));
    }

    @PostMapping
    @Transactional
    public ArticleId create(@Valid @RequestBody ArticleForm form) {
        validateReferences(form);
        String summary = summary(form.summary(), form.content());
        KeyHolder key = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var statement = connection.prepareStatement(
                    "INSERT INTO article (title, summary, content, cover, category_id, status, is_top) VALUES (?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            statement.setString(1, form.title().trim());
            statement.setString(2, summary);
            statement.setString(3, form.content());
            statement.setString(4, form.cover());
            if (form.categoryId() == null) statement.setNull(5, java.sql.Types.BIGINT);
            else statement.setLong(5, form.categoryId());
            statement.setInt(6, form.status());
            statement.setBoolean(7, form.isTop());
            return statement;
        }, key);
        long articleId = Objects.requireNonNull(key.getKey()).longValue();
        replaceTags(articleId, form.tagIds());
        return new ArticleId(articleId);
    }

    @PutMapping("/{id}")
    @Transactional
    public ArticleId update(@PathVariable long id, @Valid @RequestBody ArticleForm form) {
        validateReferences(form);
        int updated = jdbc.update("UPDATE article SET title = ?, summary = ?, content = ?, cover = ?, category_id = ?, status = ?, is_top = ? "
                        + "WHERE id = ? AND deleted = 0",
                form.title().trim(), summary(form.summary(), form.content()), form.content(), form.cover(),
                form.categoryId(), form.status(), form.isTop(), id);
        if (updated == 0) throw new ArticleNotFoundException(id);
        replaceTags(id, form.tagIds());
        return new ArticleId(id);
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable long id) {
        if (jdbc.update("UPDATE article SET deleted = 1 WHERE id = ? AND deleted = 0", id) == 0) {
            throw new ArticleNotFoundException(id);
        }
    }

    private void validateReferences(ArticleForm form) {
        if (form.categoryId() != null && jdbc.queryForObject("SELECT COUNT(*) FROM category WHERE id = ?", Integer.class, form.categoryId()) == 0) {
            throw new IllegalArgumentException("文章分类不存在");
        }
        if (form.tagIds() != null && !form.tagIds().isEmpty()) {
            Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM tag WHERE id IN (" + "?,".repeat(form.tagIds().size()).replaceAll(",$", "") + ")",
                    Integer.class, form.tagIds().toArray());
            if (count == null || count != form.tagIds().stream().distinct().count()) throw new IllegalArgumentException("文章标签不存在或重复");
        }
    }

    private void replaceTags(long articleId, List<Long> tagIds) {
        jdbc.update("DELETE FROM article_tag WHERE article_id = ?", articleId);
        if (tagIds != null) tagIds.stream().distinct().forEach(tagId -> jdbc.update(
                "INSERT INTO article_tag (article_id, tag_id) VALUES (?, ?)", articleId, tagId));
    }

    private static String summary(String summary, String content) {
        if (summary != null && !summary.isBlank()) return summary.trim();
        if (content == null) return "";
        String plain = content.replaceAll("[#>*_`~\\[\\]()!]", " ").replaceAll("\\s+", " ").trim();
        return plain.length() <= 180 ? plain : plain.substring(0, 180);
    }

    public record ArticleForm(@NotBlank @Size(max = 200) String title, @Size(max = 500) String summary,
                              @NotBlank String content, @Size(max = 255) String cover, Long categoryId,
                              @Min(0) @Max(1) int status, boolean isTop, List<Long> tagIds) { }
    public record ArticleId(long id) { }
    public record ArticleEditForm(long id, String title, String summary, String content, String cover,
                                  Long categoryId, int status, boolean isTop, List<Long> tagIds) { }
    public record AdminArticle(long id, String title, String summary, Long categoryId, String categoryName,
                               int status, boolean isTop, long viewCount, String createTime, String updateTime) { }
    public record AdminArticlePage(List<AdminArticle> records, long total, int page, int size) { }
}
