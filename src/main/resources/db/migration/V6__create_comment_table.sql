-- =====================================================================
-- V6 · 评论表
--
-- 【为什么评论要"先审核"】
--   这是全站唯一一个【任何人都能往数据库里写内容】的入口。
--   不审核的话，任何人可以立刻在文章下面贴广告、贴违法内容 ——
--   而这些内容会以"站长的站点"的名义被搜索引擎收录。
--   所以默认状态是 0（待审核），只有管理员点过"通过"才对外可见。
--
-- 【这条规则在代码里的落点】
--   前台的查询【写死】 status = 1（和文章前台查询写死 status = 1 是同一个套路）：
--   不管前端传什么参数，都拿不到未审核的评论。
--   见 CommentServiceImpl.pagePublished 的注释。
--
-- 【评论用逻辑删除，和标签不一样】
--   标签当初用物理删除，是因为它撞上了"逻辑删除 + 唯一索引"那个坑
--   （见 V5 的注释）。评论表【没有任何唯一索引】——
--   同一个人的两条一模一样的评论是合法的（比如误点两次），
--   所以那个坑在这里不存在，可以安心用项目统一的逻辑删除：
--   删错了还能人工恢复，且审计表里也有一条 DELETE_COMMENT。
-- =====================================================================

CREATE TABLE `comment` (
  `id`          bigint        NOT NULL AUTO_INCREMENT COMMENT '评论ID',
  `article_id`  bigint        NOT NULL COMMENT '所属文章ID',
  -- 游客也能评论（博客的评论区不该逼人注册），所以昵称是评论者自己填的，不是外键
  `nickname`    varchar(50)   NOT NULL COMMENT '评论者昵称（游客自填）',
  -- 邮箱可选：只在管理员回复/通知时有用，不做展示（也不做头像，避免又多一个外部依赖）
  `email`       varchar(100)  DEFAULT NULL COMMENT '邮箱（可选，不对外展示）',
  -- 正文上限 1000 字：截图里常见的长评论也就几百字，而 varchat(1000) 在 utf8mb4 下
  -- 最多占 4KB，一页 20 条也只有几十 KB，响应体完全可控
  `content`     varchar(1000) NOT NULL COMMENT '评论内容（已做 HTML 转义）',
  `status`      tinyint       NOT NULL DEFAULT '0' COMMENT '状态：0待审核 1已通过 2已拒绝',
  -- 来源 IP：被刷评论时用来判断"是不是同一个人刷的"。
  -- 不对外返回（VO 里没有这个字段），只在后台看得到
  `ip`          varchar(64)   DEFAULT NULL COMMENT '来源IP',
  `create_time` datetime      DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime      DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted`     tinyint       NOT NULL DEFAULT '0' COMMENT '逻辑删除：0未删 1已删',
  PRIMARY KEY (`id`),
  -- 【索引一：(article_id, status, create_time)】
  --   前台查"某篇文章的已通过评论"用的正是这三个条件，
  --   顺序也刻意是"等值条件在前、时间在后"：article_id 和 status 定住范围，
  --   create_time 在索引里本身就是有序的，可以直接顺序读出来、省掉文件排序。
  --   （和 article 表 idx_status_create 是同一个思路）
  KEY `idx_article_status` (`article_id`, `status`, `create_time`),
  -- 【索引二：(status, create_time)】
  --   后台的"待审核列表"是【不带文章条件】的全局查询：WHERE status = 0 ORDER BY create_time。
  --   少了它，后台每打开一次待审核页面就要扫全表 —— 而评论是全站增长最快的一张表。
  KEY `idx_status_create` (`status`, `create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='文章评论表';
