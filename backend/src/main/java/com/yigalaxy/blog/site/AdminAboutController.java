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
        jdbc.update("UPDATE about_page SET content = ?, nickname = ?, avatar = ?, email = ?, github = ?, wechat = ?, qq = ? WHERE id = 1",
                form.content(), form.nickname(), form.avatar(), form.email(), form.github(), form.wechat(), form.qq());
        return jdbc.queryForObject("SELECT * FROM about_page WHERE id = 1", SiteController::mapAbout);
    }

    public record AboutForm(@NotBlank @Size(max = 100000) String content,
                            @Size(max = 100) String nickname, @Size(max = 500) String avatar,
                            @Size(max = 200) String email, @Size(max = 500) String github,
                            @Size(max = 100) String wechat, @Size(max = 100) String qq) { }
}
