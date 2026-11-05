package com.yigalaxy.blog.article;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class ArticleQueryService {
    private static final RowMapper<ArticleController.ArticleSummary> ARTICLE_MAPPER = ArticleQueryService::mapArticle;

    private final JdbcTemplate jdbc;

    public ArticleQueryService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public ArticleController.ArticlePage pagePublished(int page, int size, String keyword, Long categoryId, Long tagId, Integer year, Integer month) {
        int normalizedPage = Math.max(1, page);
        int normalizedSize = Math.max(1, Math.min(size, 50));
        String cleanKeyword = StringUtils.hasText(keyword) ? keyword.trim() : null;

        StringBuilder predicates = new StringBuilder("a.status = 1 AND a.deleted = 0");
        java.util.ArrayList<Object> args = new java.util.ArrayList<>();
        if (cleanKeyword != null) {
            predicates.append(" AND (a.title LIKE ? OR a.summary LIKE ?)");
            String pattern = "%" + cleanKeyword + "%";
            args.add(pattern);
            args.add(pattern);
        }
        if (categoryId != null) {
            predicates.append(" AND a.category_id = ?");
            args.add(categoryId);
        }
        if (tagId != null) {
            predicates.append(" AND EXISTS (SELECT 1 FROM article_tag atg WHERE atg.article_id = a.id AND atg.tag_id = ?)");
            args.add(tagId);
        }
        if (year != null && year >= 2000 && year <= 2100) {
            predicates.append(" AND YEAR(a.created_at) = ?");
            args.add(year);
        }
        if (month != null && month >= 1 && month <= 12) {
            predicates.append(" AND MONTH(a.created_at) = ?");
            args.add(month);
        }

        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM article a WHERE " + predicates, Long.class, args.toArray());
        args.add((normalizedPage - 1) * normalizedSize);
        args.add(normalizedSize);
        List<ArticleController.ArticleSummary> records = jdbc.query(
                "SELECT a.id, a.title, a.summary, c.name AS category_name, a.created_at "
                        + "FROM article a LEFT JOIN category c ON c.id = a.category_id WHERE " + predicates
                        + " ORDER BY a.is_top DESC, a.created_at DESC, a.id DESC LIMIT ?, ?",
                ARTICLE_MAPPER,
                args.toArray());
        return new ArticleController.ArticlePage(records, total == null ? 0 : total, normalizedPage, normalizedSize,
                cleanKeyword, categoryId, tagId, year, month);
    }

    public ArticleController.ArticleDetail findPublished(long id) {
        List<ArticleController.ArticleDetail> found = jdbc.query(
                "SELECT a.id, a.title, a.summary, a.content, a.cover, a.category_id, c.name AS category_name, "
                        + "a.view_count, a.created_at, a.updated_at FROM article a "
                        + "LEFT JOIN category c ON c.id = a.category_id "
                        + "WHERE a.id = ? AND a.status = 1 AND a.deleted = 0",
                (rs, row) -> new ArticleController.ArticleDetail(
                        rs.getLong("id"), rs.getString("title"), rs.getString("summary"), rs.getString("content"),
                        rs.getString("cover"), (Long) rs.getObject("category_id"), rs.getString("category_name"),
                        rs.getLong("view_count"), rs.getString("created_at"), rs.getString("updated_at")), id);
        return found.stream().findFirst().orElse(null);
    }

    private static ArticleController.ArticleSummary mapArticle(ResultSet rs, int row) throws SQLException {
        return new ArticleController.ArticleSummary(rs.getLong("id"), rs.getString("title"), rs.getString("summary"),
                rs.getString("category_name"), rs.getString("created_at"));
    }
}
