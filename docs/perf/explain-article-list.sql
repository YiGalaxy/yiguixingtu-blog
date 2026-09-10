-- =====================================================================
-- 前台文章列表的索引优化：可复现的实测脚本
--
-- 【怎么跑】
--   docker cp docs/perf/explain-article-list.sql yiguixingtu-mysql:/tmp/perf.sql
--   docker exec yiguixingtu-mysql sh -c "mysql -uroot -proot --table < /tmp/perf.sql"
--
-- 【为什么不在项目自己的库里跑】
--   这个脚本会造 20 万行、还会建库删库，只能在【一次性库】里跑。
--   所以它自己 DROP/CREATE 一个 ygt_bench 库，跟 yiguixingtu 库没有任何关系，
--   跑多少次都不会影响开发数据。（这也是脚本开头就 DROP DATABASE 的原因 ——
--   反复跑得到的结果才是可比的，否则第二次跑就带着上次的索引了。）
--
-- 【为什么非要造 20 万行，不能"顺手测一下"】
--   优化器是看【数据量】来决定走不走索引的：
--   3 行数据的表上，全表扫描永远比走索引快，于是它一定选全表扫描，
--   EXPLAIN 出来的结果跟索引有没有、对不对【完全没有关系】。
--   所以"在小表上看不出区别"不代表索引没用 ——
--   恰恰相反，这一类问题的正确验证方式就是造够数据量再量。
--   （本次实测的数据量：20 万行，其中 16 万已发布、2000 置顶，
--    接近一个真实博客跑了两三年的体量。）
--
-- 【结论摘要】
--   优化前：183ms，扫描 160000 行，Extra 里是 Using filesort
--   优化后：0.11ms，扫描 10 行，走 idx_status_top_create 的 Backward index scan
-- =====================================================================

DROP DATABASE IF EXISTS ygt_bench;
CREATE DATABASE ygt_bench DEFAULT CHARSET=utf8mb4;
USE ygt_bench;

-- ---------------------------------------------------------------------
-- 表结构与 V1__init.sql 完全一致（含 V1 已有的两个索引），
-- 这样"改造前"的那次 EXPLAIN 才是真实基线，而不是一个已经优化过的环境
-- ---------------------------------------------------------------------
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

-- ---------------------------------------------------------------------
-- 造 20 万行
--
-- MySQL 没有 generate_series，用 6 个 0-9 的小表做交叉连接一次产出 100 万行候选，
-- 再取前 20 万 —— 比写存储过程循环快几个数量级，也避开了 DELIMITER 换行的坑。
--
-- 数据分布是刻意设计的，要让索引"值得被使用"：
--   status   80% 已发布（真实博客里草稿是少数）
--   is_top   1% 置顶   （置顶本来就该是极少数）
--   create_time  每条差 1 秒，模拟长期累积
-- ---------------------------------------------------------------------
INSERT INTO article (title, summary, cover, category_id, status, view_count, is_top, author_id, create_time)
SELECT
  CONCAT('演示文章 ', n),
  CONCAT('这是第 ', n, ' 篇文章的摘要，用来把行撑到真实宽度。'),
  NULL,
  1 + (n % 8),
  IF(n % 5 = 0, 0, 1),
  n % 1000,
  IF(n % 100 = 0, 1, 0),
  1 + (n % 100),
  NOW() - INTERVAL n SECOND
FROM (
  SELECT a.d + b.d*10 + c.d*100 + d.d*1000 + e.d*10000 + f.d*100000 AS n
  FROM (SELECT 0 d UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4
        UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9) a,
       (SELECT 0 d UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4
        UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9) b,
       (SELECT 0 d UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4
        UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9) c,
       (SELECT 0 d UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4
        UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9) d,
       (SELECT 0 d UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4
        UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9) e,
       (SELECT 0 d UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4
        UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9) f
) nums
WHERE n < 200000;

-- ANALYZE 必须跑：不更新统计信息的话，优化器的行数估算还是"空表"，
-- 它就不会认真考虑索引 —— 这一步漏掉会得出"索引没用"的错误结论
ANALYZE TABLE article;

SELECT '=========== 0. 数据量 ===========' AS section;
SELECT COUNT(*) AS total_rows, SUM(status = 1) AS published_rows, SUM(is_top = 1) AS top_rows FROM article;


-- =====================================================================
-- 1. 基线：只有 V1 的 idx_status_create 时，默认列表查询怎么执行
-- =====================================================================
SELECT '=========== 1. 改造前：EXPLAIN ===========' AS section;
EXPLAIN
SELECT id, title, summary, cover, category_id, status, view_count, is_top, author_id, create_time, update_time
FROM article
WHERE deleted = 0 AND status = 1
ORDER BY is_top DESC, create_time DESC
LIMIT 0, 10;

SELECT '=========== 1. 改造前：真实耗时（EXPLAIN ANALYZE） ===========' AS section;
EXPLAIN ANALYZE
SELECT id, title, summary, cover, category_id, status, view_count, is_top, author_id, create_time, update_time
FROM article
WHERE deleted = 0 AND status = 1
ORDER BY is_top DESC, create_time DESC
LIMIT 0, 10;

-- 期望看到（实测结果）：
--   Extra: Using where; Using filesort
--   rows:  98510（预估）
--   -> Limit: 10 row(s)  (actual time=183..183 rows=10)
--       -> Sort: is_top DESC, create_time DESC ...
--           -> Filter: (deleted = 0)  (actual time=0.0677..163 rows=160000)
--               -> Index lookup using idx_status_create (status=1)  (rows=160000)
-- 关键读法：为了 10 行结果，读了 16 万行、排了一次序。
-- 这条 SQL 慢不是因为"取数据慢"，而是因为"取出来又扔掉"。


-- =====================================================================
-- 2. 改造后：加上 V2 里的索引，同一条 SQL 再量一次
-- =====================================================================
-- 这里刻意把 V2__add_article_sort_index.sql 里那句 ALTER 原样抄过来，
-- 包括 ALGORITHM / LOCK —— 脚本验证的就是真正会上线的那条语句
ALTER TABLE `article`
    ADD INDEX `idx_status_top_create` (`status`, `is_top`, `create_time`),
    ALGORITHM = INPLACE,
    LOCK = NONE;

ANALYZE TABLE article;

SELECT '=========== 2. 改造后：EXPLAIN ===========' AS section;
EXPLAIN
SELECT id, title, summary, cover, category_id, status, view_count, is_top, author_id, create_time, update_time
FROM article
WHERE deleted = 0 AND status = 1
ORDER BY is_top DESC, create_time DESC
LIMIT 0, 10;

SELECT '=========== 2. 改造后：真实耗时（EXPLAIN ANALYZE） ===========' AS section;
EXPLAIN ANALYZE
SELECT id, title, summary, cover, category_id, status, view_count, is_top, author_id, create_time, update_time
FROM article
WHERE deleted = 0 AND status = 1
ORDER BY is_top DESC, create_time DESC
LIMIT 0, 10;

-- 期望看到（实测结果）：
--   key: idx_status_top_create    Extra: Using where; Backward index scan（filesort 消失了）
--   -> Limit: 10 row(s)  (actual time=0.107..0.11 rows=10)
--       -> Filter: (deleted = 0)  (actual time=0.105..0.108 rows=10)
--           -> Index lookup using idx_status_top_create (status=1) (reverse)  (actual time=0.0835..0.0857 rows=10)
-- 183ms → 0.11ms，扫描 160000 行 → 10 行。


-- =====================================================================
-- 3. 顺带验证：老索引 idx_status_create 确实不能被替代
-- =====================================================================
-- 用户点"按发布时间排序"时 ORDER BY 变成 create_time DESC，is_top 不参与。
-- 这种形状用不上 (status, is_top, create_time) —— 中间隔了 is_top，
-- 最左前缀断掉了。所以老索引必须留着。
SELECT '=========== 3. 按发布时间排序（走老索引 idx_status_create） ===========' AS section;
EXPLAIN ANALYZE
SELECT id, title, summary, cover, category_id, status, view_count, is_top, author_id, create_time, update_time
FROM article
WHERE deleted = 0 AND status = 1
ORDER BY create_time DESC
LIMIT 0, 10;


-- =====================================================================
-- 4. 被否掉的方案：把 deleted 也塞进索引（量过之后才决定不加）
-- =====================================================================
-- 想法：deleted = 0 是等值条件，塞进索引就能在索引内判掉、少一次回表。
-- 实测：确实也能消掉 filesort，但优化器给的估算代价反而更高
--       （type 从 ref 退化成 range，cost 3417 → 122555，实测 0.11ms → 0.30ms）。
-- 原因：deleted 只有 0/1 两个值，选择性极差，放在等值条件里帮不上忙，只让索引变宽。
SELECT '=========== 4. 候选（已否）：(status, deleted, is_top, create_time) ===========' AS section;
ALTER TABLE article DROP INDEX idx_status_top_create;
ALTER TABLE article ADD INDEX idx_status_deleted_top_create (status, deleted, is_top, create_time);
ANALYZE TABLE article;

EXPLAIN
SELECT id, title, summary, cover, category_id, status, view_count, is_top, author_id, create_time, update_time
FROM article
WHERE deleted = 0 AND status = 1
ORDER BY is_top DESC, create_time DESC
LIMIT 0, 10;

EXPLAIN ANALYZE
SELECT id, title, summary, cover, category_id, status, view_count, is_top, author_id, create_time, update_time
FROM article
WHERE deleted = 0 AND status = 1
ORDER BY is_top DESC, create_time DESC
LIMIT 0, 10;

-- 恢复成最终采用的方案，方便和上面第 2 节对比
ALTER TABLE article DROP INDEX idx_status_deleted_top_create;
ALTER TABLE article ADD INDEX idx_status_top_create (status, is_top, create_time);
ANALYZE TABLE article;


-- =====================================================================
-- 5. 被否掉的方案：为分类筛选再加一个索引（量过之后才决定不加）
-- =====================================================================
-- 想法：前端按分类筛选时多一个 category_id = ?，(status, category_id, is_top, create_time)
--       看起来能同时管住筛选和排序。
-- 实测：这条查询从 0.12ms 变成 0.072ms —— 两者都在 1 毫秒以内，用户感知不到。
--       而每多一个二级索引，每次 INSERT / UPDATE 都要多维护一棵 B+ 树，
--       本表的写操作并不少（浏览量落库、状态流转、后台编辑都会触发）。
--       用"每次写都付代价"换"读快 0.05 毫秒"不划算，因此不加。
-- 关键：这个结论是【两个方案都量过】之后得出的，不是"感觉不用加"。
SELECT '=========== 5. 分类筛选：不加专用索引时的执行计划 ===========' AS section;
EXPLAIN ANALYZE
SELECT id, title, summary, cover, category_id, status, view_count, is_top, author_id, create_time, update_time
FROM article
WHERE deleted = 0 AND status = 1 AND category_id = 3
ORDER BY is_top DESC, create_time DESC
LIMIT 0, 10;


-- =====================================================================
-- 6. 清理：这是个一次性的量测脚本，跑完把库删掉
-- =====================================================================
SELECT '=========== 6. 最终索引清单 ===========' AS section;
SELECT INDEX_NAME, SEQ_IN_INDEX, COLUMN_NAME
FROM information_schema.STATISTICS
WHERE TABLE_SCHEMA = 'ygt_bench' AND TABLE_NAME = 'article'
ORDER BY INDEX_NAME, SEQ_IN_INDEX;

DROP DATABASE IF EXISTS ygt_bench;
