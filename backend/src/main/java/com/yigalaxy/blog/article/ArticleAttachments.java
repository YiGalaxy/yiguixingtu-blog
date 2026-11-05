package com.yigalaxy.blog.article;

import java.util.List;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class ArticleAttachments {
    private final JdbcTemplate jdbc;

    public ArticleAttachments(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public List<Attachment> list(long articleId) {
        return jdbc.query("SELECT name, url, size FROM article_attachment WHERE article_id = ? ORDER BY sort, id",
                (rs, row) -> new Attachment(rs.getString("name"), rs.getString("url"), rs.getLong("size")), articleId);
    }

    public void replace(long articleId, List<Attachment> attachments) {
        jdbc.update("DELETE FROM article_attachment WHERE article_id = ?", articleId);
        if (attachments == null) return;
        for (int index = 0; index < attachments.size(); index++) {
            var item = attachments.get(index);
            jdbc.update("INSERT INTO article_attachment (article_id, name, url, size, sort) VALUES (?, ?, ?, ?, ?)",
                    articleId, item.name().trim(), item.url(), item.size(), index);
        }
    }

    public record Attachment(@NotBlank @Size(max = 200) String name,
                             @NotBlank @Size(max = 1000) @Pattern(regexp = "https?://[^\\s]+|/(?!/)[^\\s]+") String url,
                             @Min(0) long size) { }
}
