package com.yigalaxy.blog.comment;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/comments")
public class AdminCommentController {
    private final JdbcTemplate jdbc;

    public AdminCommentController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping
    public List<AdminComment> list(@RequestParam(defaultValue = "0") int status) {
        return jdbc.query("SELECT c.id, c.article_id, a.title AS article_title, c.nickname, c.email, c.content, c.status, c.created_at "
                        + "FROM comment c JOIN article a ON a.id = c.article_id WHERE c.status = ? ORDER BY c.created_at ASC, c.id ASC LIMIT 200",
                (rs, row) -> new AdminComment(rs.getLong("id"), rs.getLong("article_id"), rs.getString("article_title"),
                        rs.getString("nickname"), rs.getString("email"), rs.getString("content"), rs.getInt("status"), rs.getString("created_at")), status);
    }

    @PutMapping("/{id}")
    public CommentStatus review(@PathVariable long id, @Valid @RequestBody ReviewForm form) {
        if (jdbc.update("UPDATE comment SET status = ? WHERE id = ?", form.status(), id) == 0) throw new CommentNotFoundException(id);
        return new CommentStatus(id, form.status());
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable long id) {
        if (jdbc.update("DELETE FROM comment WHERE id = ?", id) == 0) throw new CommentNotFoundException(id);
    }

    public record ReviewForm(@Min(1) @Max(2) int status) { }
    public record AdminComment(long id, long articleId, String articleTitle, String nickname, String email,
                              String content, int status, String createTime) { }
    public record CommentStatus(long id, int status) { }
}
