package com.yigalaxy.blog.comment;

import java.util.List;

import com.yigalaxy.blog.article.ArticleNotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/articles/{articleId}/comments")
public class CommentController {
    private final JdbcTemplate jdbc;
    private final CommentRateLimiter limiter;

    public CommentController(JdbcTemplate jdbc, CommentRateLimiter limiter) {
        this.jdbc = jdbc;
        this.limiter = limiter;
    }

    @GetMapping
    public List<PublicComment> list(@PathVariable long articleId, @RequestParam(defaultValue = "1") int page,
                                   @RequestParam(defaultValue = "30") int size) {
        requirePublishedArticle(articleId);
        int safeSize = Math.max(1, Math.min(size, 100));
        return jdbc.query("SELECT id, nickname, content, created_at FROM comment WHERE article_id = ? AND status = 1 "
                        + "ORDER BY created_at DESC, id DESC LIMIT ?, ?",
                (rs, row) -> new PublicComment(rs.getLong("id"), rs.getString("nickname"), rs.getString("content"), rs.getString("created_at")),
                articleId, (Math.max(1, page) - 1) * safeSize, safeSize);
    }

    @PostMapping
    @Transactional
    @ResponseStatus(HttpStatus.CREATED)
    public CommentReceipt create(@PathVariable long articleId, @Valid @RequestBody CommentForm form, HttpServletRequest request) {
        requirePublishedArticle(articleId);
        Boolean enabled = jdbc.queryForObject("SELECT comment_enabled FROM site_setting WHERE id = 1", Boolean.class);
        if (!Boolean.TRUE.equals(enabled)) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "评论功能已关闭");
        if (!limiter.allow(request.getRemoteAddr() + ":" + articleId)) throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "评论提交过于频繁，请稍后再试");
        jdbc.update("INSERT INTO comment (article_id, nickname, content, email, status) VALUES (?, ?, ?, ?, 0)",
                articleId, form.nickname().trim(), form.content().trim(), form.email() == null ? null : form.email().trim());
        return new CommentReceipt(jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class), "pending");
    }

    @GetMapping("/page")
    public CommentPage page(@PathVariable long articleId, @RequestParam(defaultValue = "1") int page,
                            @RequestParam(defaultValue = "10") int size) {
        List<PublicComment> records = list(articleId, page, size);
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM comment WHERE article_id = ? AND status = 1", Long.class, articleId);
        return new CommentPage(records, total == null ? 0 : total);
    }

    private void requirePublishedArticle(long id) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM article WHERE id = ? AND status = 1 AND deleted = 0", Integer.class, id);
        if (count == null || count == 0) throw new ArticleNotFoundException(id);
    }

    public record CommentForm(@NotBlank @Size(max = 50) String nickname, @NotBlank @Size(max = 1000) String content,
                              @jakarta.validation.constraints.Email @Size(max = 200) String email) { }
    public record CommentPage(List<PublicComment> records, long total) { }
    public record PublicComment(long id, String nickname, String content, String createTime) { }
    public record CommentReceipt(long id, String status) { }
}
