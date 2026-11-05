package com.yigalaxy.blog.site;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/about")
public class AdminAboutController {
    private final JdbcTemplate jdbc;

    public AdminAboutController(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @PutMapping
    public SiteController.AboutContent update(@Valid @RequestBody AboutForm form) {
        jdbc.update("UPDATE about_page SET content = ? WHERE id = 1", form.content());
        return jdbc.queryForObject("SELECT content, updated_at FROM about_page WHERE id = 1",
                (rs, row) -> new SiteController.AboutContent(rs.getString("content"), rs.getString("updated_at")));
    }

    public record AboutForm(@NotBlank @Size(max = 100000) String content) { }
}
