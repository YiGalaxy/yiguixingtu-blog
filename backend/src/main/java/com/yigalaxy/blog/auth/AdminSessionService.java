package com.yigalaxy.blog.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdminSessionService {
    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwords;
    private final String adminUsername;
    private final SecureRandom random = new SecureRandom();
    private final int sessionHours;

    public AdminSessionService(JdbcTemplate jdbc, PasswordEncoder passwords,
                               @Value("${blog.admin.username:admin}") String adminUsername,
                               @Value("${blog.admin.session-hours:12}") int sessionHours) {
        this.jdbc = jdbc;
        this.passwords = passwords;
        this.adminUsername = adminUsername;
        this.sessionHours = Math.max(1, Math.min(sessionHours, 72));
    }

    @Transactional
    public Session login(String username, String password) {
        if (!adminUsername.equals(username)) return null;
        var users = jdbc.query("SELECT password_hash FROM admin_user WHERE username = ?",
                (rs, row) -> rs.getString("password_hash"), username);
        if (users.isEmpty() || !passwords.matches(password, users.get(0))) return null;

        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        String tokenHash = sha256(token);
        jdbc.update("INSERT INTO admin_session (token_hash, username, expires_at) VALUES (?, ?, DATE_ADD(UTC_TIMESTAMP(), INTERVAL ? HOUR))",
                tokenHash, username, sessionHours);
        return new Session(token, username, sessionHours * 3600L);
    }

    public String authenticate(String token) {
        if (token == null || token.length() > 100) return null;
        var users = jdbc.query("SELECT username FROM admin_session WHERE token_hash = ? AND username = ? AND expires_at > UTC_TIMESTAMP()",
                (rs, row) -> rs.getString("username"), sha256(token), adminUsername);
        return users.stream().findFirst().orElse(null);
    }

    public void logout(String token) {
        if (token != null && token.length() <= 100) jdbc.update("DELETE FROM admin_session WHERE token_hash = ?", sha256(token));
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException("无法创建会话摘要", ex);
        }
    }

    public record Session(String token, String username, long expiresIn) { }
}
