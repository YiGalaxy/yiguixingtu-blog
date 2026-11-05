CREATE TABLE visit_log (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  path VARCHAR(300) NOT NULL,
  article_id BIGINT NULL,
  ip VARCHAR(45) NOT NULL,
  visitor_type VARCHAR(10) NOT NULL,
  referer_type VARCHAR(10) NOT NULL,
  device VARCHAR(10) NOT NULL,
  visited_at DATETIME NOT NULL,
  KEY idx_visit_date (visited_at),
  KEY idx_visit_ip_date (ip, visited_at),
  KEY idx_visit_article_date (article_id, visited_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
