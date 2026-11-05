package com.yigalaxy.blog.visit;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/stats/visits")
public class AdminVisitController {
    private final JdbcTemplate jdbc;

    public AdminVisitController(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @GetMapping
    public Report report(@RequestParam(defaultValue = "30") int days) {
        int range = normalizeDays(days);
        LocalDate start = LocalDate.now(ZoneOffset.UTC).minusDays(range - 1L);
        String since = start + " 00:00:00";
        Long pv = jdbc.queryForObject("SELECT COUNT(*) FROM visit_log WHERE visited_at >= ?", Long.class, since);
        Long uv = jdbc.queryForObject("SELECT COUNT(DISTINCT ip) FROM visit_log WHERE visited_at >= ?", Long.class, since);
        Long fresh = jdbc.queryForObject("SELECT COUNT(*) FROM (SELECT ip FROM visit_log GROUP BY ip HAVING MIN(visited_at) >= ?) first_visits", Long.class, since);
        Map<String, Daily> rows = jdbc.query("SELECT DATE(visited_at) AS day, COUNT(*) AS pv, COUNT(DISTINCT ip) AS uv FROM visit_log WHERE visited_at >= ? GROUP BY DATE(visited_at) ORDER BY day",
                (rs, row) -> new Daily(rs.getString("day"), rs.getLong("pv"), rs.getLong("uv")), since)
                .stream().collect(Collectors.toMap(Daily::date, Function.identity()));
        List<Daily> daily = new ArrayList<>();
        for (int i = 0; i < range; i++) {
            String date = start.plusDays(i).toString();
            daily.add(rows.getOrDefault(date, new Daily(date, 0, 0)));
        }
        List<Slice> paths = jdbc.query("SELECT path, COUNT(*) AS count FROM visit_log WHERE visited_at >= ? GROUP BY path ORDER BY count DESC, path LIMIT 10",
                (rs, row) -> new Slice(rs.getString("path"), rs.getLong("count")), since);
        List<ArticlePoint> articles = jdbc.query("SELECT v.article_id, a.title, COUNT(*) AS pv FROM visit_log v LEFT JOIN article a ON a.id = v.article_id AND a.deleted = 0 WHERE v.visited_at >= ? AND v.article_id IS NOT NULL GROUP BY v.article_id, a.title ORDER BY pv DESC, v.article_id LIMIT 10",
                (rs, row) -> new ArticlePoint(rs.getLong("article_id"), rs.getString("title"), rs.getLong("pv")), since);
        return new Report(range, pv == null ? 0 : pv, uv == null ? 0 : uv, fresh == null ? 0 : fresh,
                daily, slices("visitor_type", since), slices("referer_type", since), slices("device", since), paths, articles);
    }

    @GetMapping("/ips")
    public List<VisitorIp> ips(@RequestParam(defaultValue = "30") int days) {
        String since = LocalDate.now(ZoneOffset.UTC).minusDays(normalizeDays(days) - 1L) + " 00:00:00";
        return jdbc.query("SELECT ip, COUNT(*) AS visits, MAX(visited_at) AS last_visit FROM visit_log WHERE visited_at >= ? GROUP BY ip ORDER BY visits DESC, ip LIMIT 200",
                (rs, row) -> new VisitorIp(rs.getString("ip"), null, rs.getLong("visits"), rs.getString("last_visit")), since);
    }

    private List<Slice> slices(String column, String since) {
        if (!List.of("visitor_type", "referer_type", "device").contains(column)) throw new IllegalArgumentException("统计分组无效");
        return jdbc.query("SELECT " + column + " AS label, COUNT(*) AS count FROM visit_log WHERE visited_at >= ? GROUP BY " + column + " ORDER BY count DESC, label",
                (rs, row) -> new Slice(rs.getString("label"), rs.getLong("count")), since);
    }

    private int normalizeDays(int days) { return days <= 7 ? 7 : days <= 30 ? 30 : 90; }
    public record Report(int days, long totalPv, long totalUv, long newVisitors, List<Daily> daily,
                         List<Slice> visitorTypes, List<Slice> refererTypes, List<Slice> devices,
                         List<Slice> topPaths, List<ArticlePoint> topArticles) { }
    public record Daily(String date, long pv, long uv) { }
    public record Slice(String key, long value) { }
    public record ArticlePoint(long id, String title, long pv) { }
    public record VisitorIp(String ip, String region, long visits, String lastVisit) { }
}
