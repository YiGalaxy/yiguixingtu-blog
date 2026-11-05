package com.yigalaxy.blog;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"blog.admin.username=contract-admin", "blog.admin.password=test-only-password"})
class AdminPublishingContractTest {
    private static final String USERNAME = "contract-admin";
    private static final String TITLE = "发布闭环集成测试文章";

    @org.springframework.beans.factory.annotation.Value("${local.server.port}")
    private int port;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PasswordEncoder passwords;

    private final ObjectMapper mapper = new ObjectMapper();

    private long articleId;
    private String uploadedFilename;

    @BeforeEach
    void createContractAdministrator() {
        jdbc.update("DELETE FROM admin_session WHERE username = ?", USERNAME);
        jdbc.update("DELETE FROM admin_user WHERE username = ?", USERNAME);
        jdbc.update("INSERT INTO admin_user (username, password_hash) VALUES (?, ?)", USERNAME, passwords.encode("test-only-password"));
        jdbc.update("DELETE FROM admin_user WHERE username = ?", "extra-admin");
        jdbc.update("INSERT INTO admin_user (username, password_hash) VALUES (?, ?)", "extra-admin", passwords.encode("extra-password"));
    }

    @AfterEach
    void removeFixtures() {
        if (articleId > 0) {
            jdbc.update("DELETE FROM comment WHERE article_id = ?", articleId);
            jdbc.update("DELETE FROM article_tag WHERE article_id = ?", articleId);
            jdbc.update("DELETE FROM article WHERE id = ?", articleId);
        }
        if (uploadedFilename != null) {
            try { Files.deleteIfExists(Path.of("uploads").resolve(uploadedFilename)); } catch (Exception ignored) { }
        }
        jdbc.update("DELETE FROM admin_session WHERE username = ?", USERNAME);
        jdbc.update("DELETE FROM admin_user WHERE username = ?", USERNAME);
        jdbc.update("DELETE FROM admin_session WHERE username = ?", "extra-admin");
        jdbc.update("DELETE FROM admin_user WHERE username = ?", "extra-admin");
    }

    @Test
    void administratorCanSaveDraftPublishAndRevokeSession() throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        HttpResponse<String> login = send(client, "/api/v1/auth/login",
                "{\"username\":\"" + USERNAME + "\",\"password\":\"test-only-password\"}", null);
        assertEquals(200, login.statusCode());
        String token = mapper.readTree(login.body()).get("token").asText();
        assertEquals(401, send(client, "/api/v1/auth/login",
                "{\"username\":\"extra-admin\",\"password\":\"extra-password\"}", null).statusCode());

        var unauthorized = send(client, "/api/v1/admin/articles", null, null);
        assertEquals(401, unauthorized.statusCode());

        String draft = "{\"title\":\"" + TITLE + "\",\"content\":\"# 草稿正文\",\"status\":0,\"isTop\":false}";
        var created = send(client, "/api/v1/admin/articles", draft, token);
        assertEquals(200, created.statusCode());
        JsonNode createdBody = mapper.readTree(created.body());
        articleId = createdBody.get("id").asLong();

        var draftDetail = send(client, "/api/v1/admin/articles/" + articleId, null, token);
        assertEquals(200, draftDetail.statusCode());
        assertEquals(0, mapper.readTree(draftDetail.body()).get("status").asInt());

        String published = "{\"title\":\"" + TITLE + "\",\"content\":\"# 正文\",\"status\":1,\"isTop\":false}";
        assertEquals(200, send(client, "/api/v1/admin/articles/" + articleId, published, token, "PUT").statusCode());
        var publicPage = send(client, "/api/v1/articles?keyword=" + java.net.URLEncoder.encode(TITLE, StandardCharsets.UTF_8), null, null);
        assertEquals(200, publicPage.statusCode());
        assertTrue(publicPage.body().contains(TITLE));

        var submittedComment = send(client, "/api/v1/articles/" + articleId + "/comments",
                "{\"nickname\":\"访客验收\",\"content\":\"待审核留言\"}", null);
        assertEquals(201, submittedComment.statusCode());
        long commentId = mapper.readTree(submittedComment.body()).get("id").asLong();
        var hiddenComment = send(client, "/api/v1/articles/" + articleId + "/comments", null, null);
        assertTrue(!hiddenComment.body().contains("待审核留言"));

        var reviewList = send(client, "/api/v1/admin/comments?status=0", null, token);
        assertTrue(reviewList.body().contains("待审核留言"));
        assertEquals(200, send(client, "/api/v1/admin/comments/" + commentId, "{\"status\":1}", token, "PUT").statusCode());
        var visibleComment = send(client, "/api/v1/articles/" + articleId + "/comments", null, null);
        assertTrue(visibleComment.body().contains("待审核留言"));

        String boundary = "blog-upload-boundary";
        String multipart = "--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"cover.png\"\r\n"
                + "Content-Type: image/png\r\n\r\npixel\r\n--" + boundary + "--\r\n";
        var upload = sendMultipart(client, "/api/v1/admin/uploads", multipart, boundary, token);
        assertEquals(200, upload.statusCode());
        JsonNode media = mapper.readTree(upload.body());
        uploadedFilename = media.get("filename").asText();
        String mediaUrl = media.get("url").asText();
        var mediaResponse = send(client, mediaUrl.replace("http://localhost:" + port, ""), null, null);
        assertEquals(200, mediaResponse.statusCode());
        jdbc.update("UPDATE article SET content = ? WHERE id = ?", mediaUrl, articleId);
        var protectedFile = send(client, "/api/v1/admin/uploads/" + uploadedFilename, null, token, "DELETE");
        assertEquals(409, protectedFile.statusCode());
        jdbc.update("UPDATE article SET content = '' WHERE id = ?", articleId);
        assertEquals(200, send(client, "/api/v1/admin/uploads/" + uploadedFilename, null, token, "DELETE").statusCode());
        uploadedFilename = null;

        assertEquals(200, send(client, "/api/v1/auth/logout", "{}", token, "POST").statusCode());
        assertEquals(401, send(client, "/api/v1/admin/articles", null, token).statusCode());
    }

    private HttpResponse<String> send(HttpClient client, String path, String body, String bearer) throws Exception {
        return send(client, path, body, bearer, body == null ? "GET" : "POST");
    }

    private HttpResponse<String> send(HttpClient client, String path, String body, String bearer, String method) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
        if (bearer != null) builder.header("Authorization", "Bearer " + bearer);
        if (body != null) builder.header("Content-Type", "application/json");
        var request = builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body)).build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> sendMultipart(HttpClient client, String path, String body, String boundary, String bearer) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Authorization", "Bearer " + bearer)
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
