package com.yigalaxy.blog.article;

import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class ArticleQueryService {

    private final JdbcTemplate jdbc;
    private final ArticleAttachments attachments;

    public ArticleQueryService(JdbcTemplate jdbc, ArticleAttachments attachments) {
        this.jdbc = jdbc;
        this.attachments = attachments;
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
                "SELECT a.id, a.title, a.summary, a.cover, a.view_count, c.name AS category_name, a.created_at "
                        + "FROM article a LEFT JOIN category c ON c.id = a.category_id WHERE " + predicates
                        + " ORDER BY a.is_top DESC, a.created_at DESC, a.id DESC LIMIT ?, ?",
                (rs, row) -> new ArticleController.ArticleSummary(rs.getLong("id"), rs.getString("title"),
                        rs.getString("summary"), rs.getString("category_name"), rs.getString("created_at"),
                        rs.getString("cover"), rs.getLong("view_count"), List.of()),
                args.toArray());
        if (!records.isEmpty()) {
            String placeholders = String.join(",", java.util.Collections.nCopies(records.size(), "?"));
            java.util.Map<Long, List<ArticleController.ArticleTag>> tags = new java.util.HashMap<>();
            jdbc.query("SELECT atg.article_id, t.id, t.name FROM article_tag atg JOIN tag t ON t.id = atg.tag_id "
                            + "WHERE atg.article_id IN (" + placeholders + ") ORDER BY t.sort, t.id",
                    (org.springframework.jdbc.core.RowCallbackHandler) rs -> tags
                            .computeIfAbsent(rs.getLong("article_id"), key -> new java.util.ArrayList<>())
                            .add(new ArticleController.ArticleTag(rs.getLong("id"), rs.getString("name"))),
                    records.stream().map(ArticleController.ArticleSummary::id).toArray());
            records = records.stream().map(a -> new ArticleController.ArticleSummary(a.id(), a.title(), a.summary(),
                    a.categoryName(), a.createTime(), a.cover(), a.viewCount(), tags.getOrDefault(a.id(), List.of()))).toList();
        }
        return new ArticleController.ArticlePage(records, total == null ? 0 : total, normalizedPage, normalizedSize,
                cleanKeyword, categoryId, tagId, year, month);
    }

    public ArticleController.ArticleDetail findPublished(long id) {
        List<ArticleController.ArticleDetail> found = jdbc.query(
                "SELECT a.id, a.title, a.summary, a.content, a.cover, a.category_id, c.name AS category_name, "
                        + "a.view_count, a.created_at, a.updated_at, a.is_top FROM article a "
                        + "LEFT JOIN category c ON c.id = a.category_id "
                        + "WHERE a.id = ? AND a.status = 1 AND a.deleted = 0",
                (rs, row) -> new ArticleController.ArticleDetail(
                        rs.getLong("id"), rs.getString("title"), rs.getString("summary"), rs.getString("content"),
                        rs.getString("cover"), (Long) rs.getObject("category_id"), rs.getString("category_name"),
                        rs.getLong("view_count"), rs.getString("created_at"), rs.getString("updated_at"), List.of(), rs.getInt("is_top"), List.of()), id);
        if (!found.isEmpty()) {
            var a = found.get(0);
            var tags = jdbc.query("SELECT t.id, t.name FROM article_tag atg JOIN tag t ON t.id = atg.tag_id "
                            + "WHERE atg.article_id = ? ORDER BY t.sort, t.id",
                    (rs, row) -> new ArticleController.ArticleTag(rs.getLong("id"), rs.getString("name")), id);
            return new ArticleController.ArticleDetail(a.id(), a.title(), a.summary(), a.content(), a.cover(),
                    a.categoryId(), a.categoryName(), a.viewCount(), a.createTime(), a.updateTime(), tags, a.isTop(), attachments.list(id));
        }
        return found.stream().findFirst().orElse(null);
    }

}
