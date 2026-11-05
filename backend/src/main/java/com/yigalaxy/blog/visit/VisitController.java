package com.yigalaxy.blog.visit;

import java.net.URI;
import java.util.Locale;

import com.yigalaxy.blog.auth.AdminSessionService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/visits")
public class VisitController {
    private final JdbcTemplate jdbc;
    private final AdminSessionService sessions;

    public VisitController(JdbcTemplate jdbc, AdminSessionService sessions) {
        this.jdbc = jdbc;
        this.sessions = sessions;
    }

    @PostMapping
    public Receipt record(@Valid @RequestBody VisitForm form, HttpServletRequest request) {
        Long articleId = form.path().startsWith("/article/") ? Long.valueOf(form.path().substring(9)) : null;
        if (articleId != null && jdbc.queryForObject("SELECT COUNT(*) FROM article WHERE id = ? AND status = 1 AND deleted = 0", Integer.class, articleId) == 0)
            return new Receipt(false);
        String ip = request.getRemoteAddr();
        if (ip.length() > 45) return new Receipt(false);
        Long recent = jdbc.queryForObject("SELECT COUNT(*) FROM visit_log WHERE ip = ? AND visited_at >= UTC_TIMESTAMP() - INTERVAL 1 MINUTE", Long.class, ip);
        if (recent != null && recent >= 120) return new Receipt(false);
        Integer repeated = jdbc.queryForObject("SELECT COUNT(*) FROM visit_log WHERE ip = ? AND path = ? AND visited_at >= UTC_TIMESTAMP() - INTERVAL 5 SECOND", Integer.class, ip, form.path());
        if (repeated != null && repeated > 0) return new Receipt(false);
        String auth = request.getHeader("Authorization");
        String token = auth != null && auth.startsWith("Bearer ") ? auth.substring(7) : null;
        String type = sessions.authenticate(token) == null ? "GUEST" : "ADMIN";
        String agent = String.valueOf(request.getHeader("User-Agent")).toLowerCase(Locale.ROOT);
        String device = agent.matches(".*(mobile|android|iphone|ipad|tablet).*" ) ? "MOBILE" : "PC";
        String source = refererType(form.referrer(), request.getHeader("Origin"));
        jdbc.update("INSERT INTO visit_log (path, article_id, ip, visitor_type, referer_type, device, visited_at) VALUES (?, ?, ?, ?, ?, ?, UTC_TIMESTAMP())",
                form.path(), articleId, ip, type, source, device);
        return new Receipt(true);
    }

    private String refererType(String referrer, String origin) {
        if (referrer == null || referrer.isBlank()) return "DIRECT";
        if (referrer.startsWith("/") && !referrer.startsWith("//")) return "INTERNAL";
        try {
            URI source = URI.create(referrer);
            URI own = origin == null ? null : URI.create(origin);
            return own != null && own.getHost() != null && own.getHost().equalsIgnoreCase(source.getHost()) ? "INTERNAL" : "EXTERNAL";
        } catch (IllegalArgumentException ignored) { return "EXTERNAL"; }
    }

    @Scheduled(fixedDelay = 86_400_000, initialDelay = 60_000)
    public void cleanExpiredVisits() {
        jdbc.update("DELETE FROM visit_log WHERE visited_at < UTC_TIMESTAMP() - INTERVAL 90 DAY");
    }

    public record VisitForm(@NotBlank @Size(max = 300)
                            @Pattern(regexp = "^/(?:articles|article/[1-9][0-9]{0,17}|projects|favorites|links|about)?$") String path,
                            @Size(max = 1000) String referrer) { }
    public record Receipt(boolean recorded) { }
}
