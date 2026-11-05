package com.yigalaxy.blog.auth;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class AdminBootstrapRunner implements ApplicationRunner {
    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwords;
    private final String username;
    private final String password;

    public AdminBootstrapRunner(JdbcTemplate jdbc, PasswordEncoder passwords,
                                @Value("${blog.admin.username:admin}") String username,
                                @Value("${blog.admin.password:}") String password) {
        this.jdbc = jdbc;
        this.passwords = passwords;
        this.username = username;
        this.password = password;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!StringUtils.hasText(password)) return;
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM admin_user", Integer.class);
        if (count == null || count == 0) {
            jdbc.update("INSERT INTO admin_user (username, password_hash) VALUES (?, ?)", username, passwords.encode(password));
        }
    }
}
