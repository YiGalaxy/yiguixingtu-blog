package com.yigalaxy.blog;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ArticleQueryContractTest {
    @Value("${local.server.port}")
    private int port;

    private final JdbcTemplate jdbc;
    private long articleId;
    private long tagId;
    private long categoryId;

    @Autowired
    ArticleQueryContractTest(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @AfterEach
    void cleanFixtures() {
        if (articleId > 0) jdbc.update("DELETE FROM article_tag WHERE article_id = ?", articleId);
        if (articleId > 0) jdbc.update("DELETE FROM article WHERE id = ?", articleId);
        if (tagId > 0) jdbc.update("DELETE FROM tag WHERE id = ?", tagId);
        if (categoryId > 0) jdbc.update("DELETE FROM category WHERE id = ?", categoryId);
    }

    @Test
    void publicSearchCombinesKeywordCategoryAndTagAndExcludesDrafts() throws Exception {
        categoryId = insertId("INSERT INTO category (name, sort) VALUES (?, 0)", "集成测试分类");
        tagId = insertId("INSERT INTO tag (name, sort) VALUES (?, 0)", "集成测试标签");
        articleId = insertId("INSERT INTO article (title, summary, content, category_id, status) VALUES (?, ?, ?, ?, 1)",
                "Nuxt 搜索验收", "公开摘要", "正文", categoryId);
        jdbc.update("INSERT INTO article_tag (article_id, tag_id) VALUES (?, ?)", articleId, tagId);
        jdbc.update("INSERT INTO article (title, content, status) VALUES (?, ?, 0)", "Nuxt 草稿", "隐藏正文");

        String query = "keyword=" + URLEncoder.encode("Nuxt", StandardCharsets.UTF_8)
                + "&categoryId=" + categoryId + "&tagId=" + tagId;
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/articles?" + query)).GET().build();
        var response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());

        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("Nuxt 搜索验收"));
        assertTrue(response.body().contains("集成测试分类"));
        assertTrue(!response.body().contains("Nuxt 草稿"));
        assertTrue(response.body().contains("\"total\":1"));
    }

    private long insertId(String sql, Object... values) {
        jdbc.update(sql, values);
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }
}
