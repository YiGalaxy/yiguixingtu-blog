-- =====================================================================
-- V1 · 初始化表结构
--
-- 【这个文件是干什么的】
--   在 Flyway 之前，建表 SQL 是散在 README 和聊天记录里的，
--   换一台机器就要照着抄一遍，而且抄错了没人发现（比如少一个索引）。
--   现在建表变成"应用启动时自动完成"，开发库 / 测试库 / 生产库
--   用的是同一份定义 —— 这是这份文件存在的全部意义。
--
-- 【为什么是 V1 而不是 V0】
--   命名规则：V{版本}__{描述}.sql（两个下划线）。Flyway 靠文件名排序，
--   执行过的版本会记在 flyway_schema_history 表里，不会重复执行。
--   以后改表结构不是改这个文件，而是新增 V2__xxx.sql —— 已上线的
--   迁移脚本是历史，改了会导致校验失败（validate 会比对校验和）。
--
-- 【为什么加了 baseline-on-migrate】
--   本地的库是先手工建好表、Flyway 才接进来的，属于"已经存在的库"。
--   baseline-on-migrate=true 会把这种库打个基线标记，认为 V1 已应用，
--   于是它不会去重复建表。而全新的空库（比如 Testcontainers 起的容器、
--   或者部署时的生产库）没有这个标记，V1 会正常执行。
--   两种情形都用同一份配置，不用分环境改。
--
-- 【表结构来源】
--   照本地数据库 SHOW CREATE TABLE 实录，不是凭记忆写的。
--   包含三张表：user / category / article。
--   tag / article_tag / comment / operation_log 属于尚未实现的模块，
--   等做到那一批再加 V2、V3，不提前建空表。
-- =====================================================================


-- ============ 用户表 ============
-- 注意：user 是 MySQL 保留字，表名必须用反引号包住，否则语法错误。
CREATE TABLE `user` (
  `id`          bigint       NOT NULL AUTO_INCREMENT COMMENT '主键',
  `username`    varchar(64)  NOT NULL COMMENT '登录账号',
  `password`    varchar(100) NOT NULL COMMENT '密码(BCrypt哈希)',
  `nickname`    varchar(64)  DEFAULT NULL COMMENT '昵称',
  `role`        varchar(20)  NOT NULL DEFAULT 'GUEST' COMMENT '角色：ADMIN/GUEST',
  `status`      tinyint      NOT NULL DEFAULT '1' COMMENT '状态：1正常 0禁用',
  `create_time` datetime     DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted`     tinyint      NOT NULL DEFAULT '0' COMMENT '逻辑删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_username` (`username`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户表';

-- ⚠️ 关于 uk_username 与逻辑删除的一个坑（代码里已经处理，这里说明背景）：
--   username 是唯一索引，但删除用户用的是逻辑删除（deleted=1，物理行还在）。
--   于是"删掉 zhangsan 之后再注册 zhangsan"会撞唯一索引。
--   处理办法在 UserServiceImpl 里：删除时把用户名改写成 `原名#deleted#{id}`，
--   既保住历史记录，又把用户名释放出来给新用户用。


-- ============ 文章分类表 ============
CREATE TABLE `category` (
  `id`          bigint       NOT NULL AUTO_INCREMENT COMMENT '分类ID',
  `name`        varchar(50)  NOT NULL COMMENT '分类名称',
  `description` varchar(255) DEFAULT NULL COMMENT '分类描述',
  `sort`        int          NOT NULL DEFAULT '0' COMMENT '排序值，越小越靠前',
  `create_time` datetime     DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted`     tinyint      NOT NULL DEFAULT '0' COMMENT '逻辑删除：0未删 1已删',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_name` (`name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='文章分类表';


-- ============ 文章表 ============
CREATE TABLE `article` (
  `id`          bigint       NOT NULL AUTO_INCREMENT COMMENT '文章ID',
  `title`       varchar(200) NOT NULL COMMENT '标题',
  `summary`     varchar(500) DEFAULT NULL COMMENT '摘要（列表页展示）',
  `content`     longtext     COMMENT '正文（Markdown 源码）',
  `cover`       varchar(255) DEFAULT NULL COMMENT '封面图URL',
  `category_id` bigint       DEFAULT NULL COMMENT '分类ID',
  `status`      tinyint      NOT NULL DEFAULT '0' COMMENT '状态：0草稿 1已发布',
  `view_count`  int          NOT NULL DEFAULT '0' COMMENT '浏览量',
  `is_top`      tinyint      NOT NULL DEFAULT '0' COMMENT '是否置顶：0否 1是',
  `author_id`   bigint       DEFAULT NULL COMMENT '作者用户ID',
  `create_time` datetime     DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted`     tinyint      NOT NULL DEFAULT '0' COMMENT '逻辑删除：0未删 1已删',
  PRIMARY KEY (`id`),
  KEY `idx_status_create` (`status`, `create_time`),
  KEY `idx_category` (`category_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='文章表';

-- 【为什么是 idx_status_create(status, create_time) 这个顺序？】
--   前台列表的查询是：WHERE status = 1 ORDER BY create_time DESC
--   复合索引遵循"最左前缀"，把 status 放前面才能：
--     ① 用等值条件 status = 1 快速缩小扫描范围；
--     ② 剩下的 create_time 在索引里本来就是有序的，可以直接顺序读出，
--        省掉一次 ORDER BY 的文件排序（EXPLAIN 里 Extra 不再出现 Using filesort）。
--   反过来写成 (create_time, status) 就用不上了 —— 范围/排序字段必须放在等值字段后面。
--
-- 【为什么没有为 title 建索引？】
--   搜索用的是 LIKE '%关键词%'（两边都有通配符），前置通配符让 B+ 树索引完全失效。
--   真要解决得靠全文索引或 Elasticsearch，属于后面批次的事；
--   现在加一个用不上的索引，只会拖慢写入、并在 EXPLAIN 里给人"已经优化过"的错觉。
