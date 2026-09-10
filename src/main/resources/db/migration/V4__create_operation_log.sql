-- =====================================================================
-- V4 · 操作审计表
--
-- 【为什么要单独一张表，而不是只写日志文件】
--   日志文件谁都能改、会被轮转删掉，而且没有结构 —— 想查"上周谁改过文章"
--   只能 grep。审计记录必须是【结构化、可查询、能长期保留】的，
--   所以它是一张表。日志文件继续负责排查问题，这张表负责"谁在什么时候
--   做了什么"。
--
-- 【⚠️ 这张表刻意【没有】deleted 字段】
--   本项目其它表都有逻辑删除，但这张表不该有：
--   审计记录的价值就在于"发生过的事不能被抹掉"。
--   给它加逻辑删除等于给了"把痕迹藏起来"的操作空间 ——
--   真要清理历史数据，应该是"按时间归档/删除过期分区"这种明确的运维动作，
--   而不是某个业务接口顺手删一下。
--
-- 【字段怎么定的】
--   user_id / username   谁做的。两个都存是有意的：
--     · user_id 用来关联查询（性能好）
--     · username 是【快照】—— 用户改名或被删之后，仍然要知道当时是谁。
--       只存 id 的话，用户被删掉（逻辑删除、用户名被改写成 xxx#deleted#1）
--       之后这条记录就追不回人名了
--   action               做了什么（枚举值，见 OperationAction）
--   target_type/target_id  对哪个对象做的
--   detail               补充说明（比如改了标题，可以记下新旧值）
--   ip                   从哪来的
--   trace_id             ⚠️ 这一列是整套日志的"钥匙"：
--     把它和日志系统里的 traceId 对上，就能从"谁改了文章"
--     一路查到"这次请求里每一条 SQL、每一次报错"。
--     没有它，审计表只是一个孤立的记录。
--   create_time          什么时候
-- =====================================================================

CREATE TABLE `operation_log` (
  `id`          bigint       NOT NULL AUTO_INCREMENT COMMENT '主键',
  `user_id`     bigint       DEFAULT NULL COMMENT '操作人ID',
  `username`    varchar(64)  DEFAULT NULL COMMENT '操作人用户名（快照，用户改名后仍可追溯）',
  `action`      varchar(50)  NOT NULL COMMENT '操作类型，如 CREATE_ARTICLE',
  `target_type` varchar(50)  DEFAULT NULL COMMENT '操作对象类型，如 ARTICLE / USER',
  `target_id`   bigint       DEFAULT NULL COMMENT '操作对象ID',
  `detail`      varchar(500) DEFAULT NULL COMMENT '补充说明',
  `ip`          varchar(64)  DEFAULT NULL COMMENT '来源IP',
  `trace_id`    varchar(64)  DEFAULT NULL COMMENT '链路追踪ID，用来和日志系统对上',
  `create_time` datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '操作时间',
  PRIMARY KEY (`id`),
  -- 【索引为什么是这两个】
  --   (user_id, create_time)：查"某个人做过什么"是最常见的需求，
  --     而且几乎总是按时间倒序看，所以时间跟在后面、可以直接顺序读
  --   (create_time)：查"最近发生了什么"（不带人），比如出事之后看那几分钟
  --   两个索引都刻意带了时间列：审计查询【永远是带时间范围的】，
  --   只按 user_id 建的索引查起来还要回表排序，等于白建
  KEY `idx_user_time` (`user_id`, `create_time`),
  KEY `idx_create_time` (`create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='操作审计日志';
