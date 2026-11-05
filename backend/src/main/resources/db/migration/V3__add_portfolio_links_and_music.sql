CREATE TABLE project (
  id BIGINT NOT NULL AUTO_INCREMENT,
  title VARCHAR(120) NOT NULL,
  description VARCHAR(1000) NULL,
  primary_url VARCHAR(500) NULL,
  secondary_url VARCHAR(500) NULL,
  metadata VARCHAR(1000) NULL,
  sort INT NOT NULL DEFAULT 0,
  PRIMARY KEY (id), KEY idx_project_sort (sort, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE favorite (
  id BIGINT NOT NULL AUTO_INCREMENT,
  title VARCHAR(120) NOT NULL,
  description VARCHAR(1000) NULL,
  primary_url VARCHAR(500) NOT NULL,
  secondary_url VARCHAR(200) NULL,
  metadata VARCHAR(100) NULL,
  sort INT NOT NULL DEFAULT 0,
  PRIMARY KEY (id), KEY idx_favorite_sort (sort, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE friend_link (
  id BIGINT NOT NULL AUTO_INCREMENT,
  title VARCHAR(120) NOT NULL,
  description VARCHAR(500) NULL,
  primary_url VARCHAR(500) NOT NULL,
  secondary_url VARCHAR(500) NULL,
  metadata VARCHAR(100) NULL,
  sort INT NOT NULL DEFAULT 0,
  PRIMARY KEY (id), KEY idx_friend_link_sort (sort, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE music_track (
  id BIGINT NOT NULL AUTO_INCREMENT,
  title VARCHAR(160) NOT NULL,
  artist VARCHAR(120) NULL,
  audio_url VARCHAR(500) NOT NULL,
  cover_url VARCHAR(500) NULL,
  lyrics MEDIUMTEXT NULL,
  sort INT NOT NULL DEFAULT 0,
  enabled TINYINT NOT NULL DEFAULT 1,
  PRIMARY KEY (id), KEY idx_music_enabled_sort (enabled, sort, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE about_page (
  id TINYINT NOT NULL,
  content MEDIUMTEXT NOT NULL,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT INTO about_page (id, content) VALUES (1, '在代码与星轨之间，慢慢画自己的图。');
