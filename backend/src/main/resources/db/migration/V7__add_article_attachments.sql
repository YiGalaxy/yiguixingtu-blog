CREATE TABLE article_attachment (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    article_id BIGINT NOT NULL,
    name VARCHAR(200) NOT NULL,
    url VARCHAR(1000) NOT NULL,
    size BIGINT NOT NULL DEFAULT 0,
    sort INT NOT NULL DEFAULT 0,
    CONSTRAINT fk_attachment_article FOREIGN KEY (article_id) REFERENCES article(id) ON DELETE CASCADE,
    INDEX idx_attachment_article (article_id, sort, id)
);
