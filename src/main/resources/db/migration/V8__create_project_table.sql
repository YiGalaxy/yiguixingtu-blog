-- =====================================================================
-- V8 · 项目表（project）
--
-- 【这张表是干什么的】
--   "我的项目"是个人博客里最实用的一页：做过的东西 + 在线地址 + 仓库地址 + 用了什么技术栈。
--   和友链（V7）一样，它此前也只能写死在前端 —— 加一个项目要改代码、重新部署。
--
-- =====================================================================
-- 【几个刻意的设计决定】
--
-- ① url（在线地址）与 repo（仓库地址）都是【可空】，但不能同时为空
--    这两个字段回答的是两个不同的问题："能点开看"和"能读代码"。
--    有些项目只有仓库（还没部署），有些只有演示（内部项目不给源码）——
--    所以单独看每一个都允许为空。
--    但两个都空的后果很具体：项目卡片是一个点不出任何东西的"死卡片"，
--    访客会以为它坏了。所以"至少填一个"这条规则写在 Service 里
--    （它跨两个字段，Bean Validation 的单字段注解表达不了，硬要表达就得
--      自己写一个类级校验器 —— 为一条规则加一个类不值当）。
--
-- ② cover（封面图）可空：没有封面时前端用一张渐变底 + 项目名首字母兜底，
--    这是很常见的做法，不该逼着每个项目都先准备一张图。
--
-- ③ tech（技术栈）是【一个逗号分隔的字符串】，不是关联表
--    它是纯展示字段："Spring Boot / MySQL / Redis"这样一行小标签。
--    不做 project_tech 关联表 + tech 字典表的原因：
--      · 没有"按技术栈筛选项目"这个需求（那一页本来就只有几个项目）
--      · 没有"统计某个技术栈用在几个项目上"这个需求
--      · 关联表要连带维护"改项目时覆盖式重写关联"，是文章标签那套的复杂度；
--        文章标签值得，因为标签页是文章的主要导航方式之一 —— 项目不是
--    以后真要按技术栈聚合，再加关联表并从这一列迁移数据即可（那时才付那份代价）。
--    长度 200：按"每项 10 字 + 逗号"算大约能放 15 项，个人项目远用不到
--
-- ④ status 默认 1（显示）：和友链同理 —— 管理员自己录的内容，录完就是要展示的；
--    它的作用是"先录进来、暂时不显示"（比如项目还在重构，先别让人点进去）
--
-- ⑤ 逻辑删除（deleted）：这张表同样没有任何唯一索引，
--    所以不会踩"逻辑删除 + 唯一索引"那个坑（判断标准见 README「数据库表」一节），
--    可以保留"删错了能恢复"的能力
-- =====================================================================

CREATE TABLE `project` (
  `id`          bigint       NOT NULL AUTO_INCREMENT COMMENT '项目ID',
  `name`        varchar(100) NOT NULL COMMENT '项目名称',
  -- 一句话/一段话介绍：500 字。比友链的 200 长，因为项目卡片通常有"展开简介"的位置，
  -- 也常常要写清"做了什么、为什么做"。再长就该写成一篇文章了
  `description` varchar(500) DEFAULT NULL COMMENT '项目简介',
  -- 在线地址与仓库地址：格式由 DTO 上的 @Pattern 白名单兜住（只放行 http(s)），
  -- "至少填一个"由 Service 校验（理由见上面 ①）
  `url`         varchar(255) DEFAULT NULL COMMENT '在线演示地址',
  `repo`        varchar(255) DEFAULT NULL COMMENT '代码仓库地址',
  `cover`       varchar(255) DEFAULT NULL COMMENT '封面图地址',
  -- 逗号分隔的技术栈（纯展示，理由见上面 ③）
  `tech`        varchar(200) DEFAULT NULL COMMENT '技术栈（逗号分隔）',
  `sort`        int          NOT NULL DEFAULT '0' COMMENT '排序值，越小越靠前',
  `status`      tinyint      NOT NULL DEFAULT '1' COMMENT '状态：0隐藏 1显示',
  `create_time` datetime     DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted`     tinyint      NOT NULL DEFAULT '0' COMMENT '逻辑删除：0未删 1已删',
  PRIMARY KEY (`id`),
  -- 前台查询是 WHERE status = 1 ORDER BY sort ASC, id ASC ——
  -- 等值条件在前、排序字段在后：status 定住范围后 sort 在索引里天然有序，省掉 filesort
  -- （和 friend_link 的 idx_status_sort、comment 的 idx_article_status 同一个思路）
  KEY `idx_status_sort` (`status`, `sort`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='项目展示表';

-- 【同样不插种子数据】项目列表是"我做过什么"，编几条假项目进迁移脚本，
-- 上线之后就是要一条条删掉的垃圾。空表时前台显示"暂无项目"即可。
