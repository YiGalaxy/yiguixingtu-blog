-- =====================================================================
-- V9 · 收藏表（favorite）
--
-- 【这张表是干什么的】
--   前台的"收藏"页：站长自己攒的一批好东西 —— 常用的在线工具、读过的好文章、
--   值得反复看的视频…… 和友链 / 项目一样，此前只能写死在前端。
--
-- =====================================================================
-- 【三个刻意的设计决定】
--
-- ① category（分组）是【自由文本】，不是关联到 category 表的外键
--    这是最容易被误解的一处，所以写清楚：
--      · category 表是【博客文章的栏目】（技术笔记 / 项目复盘 / 生活随笔），
--        它有唯一索引 uk_name，而且删除前要检查"还有没有文章在用"
--      · 收藏的分组是【个人书签的分类】（工具 / 文章 / 视频 / 学习资料），
--        它是私人用途、随手就改、数量不定
--    两者同名但完全不是一回事。如果让收藏去引用 category.category_id，会出两个问题：
--      ① 删分类时的"还有没有文章在用"检查会数不到收藏 —— 分类被删掉之后，
--         收藏里的分组就变成一串查不到的 id（页面上显示空白或报错）
--      ② 想加一个"工具"这种只用于收藏的分组，就得往【文章栏目】里塞一条
--         永远不会有文章的记录 —— 文章的分类筛选条上会多出一个空分类
--    所以：不建关联、也不做校验（想填什么分组就填什么，空着就是"未分组"）。
--    长度 50：和 category 表的分类名一样长，够用了
--
-- ② url 是【必填】且必须是 http(s)（格式由 DTO 的 @Pattern 白名单兜住）
--    一条没有地址的"收藏"没有任何意义 —— 它不是一个可以"稍后再看"的待办，
--    而是"我以后还要再来一次"的入口。所以它是这几个内容模块里唯一一个
--    URL 必填的表（项目允许只填仓库、友链虽然必填但字段语义不同）。
--
-- ③ title 是【展示用的名字】，不是"从目标网页抓的标题"
--    后端不做抓取：那要发外部请求（SSRF 风险 + 慢 + 目标站点随时可能改版），
--    而这张表是站长自己维护的，填标题时顺手写一个更准确。
--    长度 100：它出现在收藏卡片的一行里，超过 100 字必然要折行或截断
--
-- ④ 其余与友链 / 项目同形：status 默认 1（显示）、逻辑删除（本表无唯一索引）、
--    索引 (status, sort) 支撑"WHERE status = 1 ORDER BY sort, id"
-- =====================================================================

CREATE TABLE `favorite` (
  `id`          bigint       NOT NULL AUTO_INCREMENT COMMENT '收藏ID',
  `title`       varchar(100) NOT NULL COMMENT '标题',
  -- 目标地址：必填，且必须是 http(s) 开头的完整地址（白名单见 common/validation/UrlPatterns）
  `url`         varchar(255) NOT NULL COMMENT '目标地址（http/https）',
  -- 备注：500 字。为什么比友链的 200 长 —— 收藏常常要记"为什么收藏它"、
  -- "重点看第几节"，这类话比一句友链介绍长
  `description` varchar(500) DEFAULT NULL COMMENT '备注/说明',
  -- 分组：自由文本，可为空（空 = 未分组）。理由见上面 ①
  `category`    varchar(50)  DEFAULT NULL COMMENT '分组名（自由文本，可为空）',
  `sort`        int          NOT NULL DEFAULT '0' COMMENT '排序值，越小越靠前',
  `status`      tinyint      NOT NULL DEFAULT '1' COMMENT '状态：0隐藏 1显示',
  `create_time` datetime     DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted`     tinyint      NOT NULL DEFAULT '0' COMMENT '逻辑删除：0未删 1已删',
  PRIMARY KEY (`id`),
  -- 前台查询：WHERE status = 1 ORDER BY sort ASC, id ASC ——
  -- 等值条件在前、排序字段在后，sort 在索引里天然有序，省掉 filesort
  KEY `idx_status_sort` (`status`, `sort`)
  -- 【为什么没有给 category 建索引】
  --   收藏的总量是几十条量级，而这个表上唯一的查询就是"取全部显示的按 sort 排" ——
  --   分组是前端拿到列表之后自己分（同组内保持后端给的顺序）。
  --   给一个"只用于展示"的列建索引，只是多一份写入代价而没有任何查询会用它。
  --   哪天真要做"只拉某个分组的收藏"，再加 (status, category, sort) 也不迟。
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='收藏表';
