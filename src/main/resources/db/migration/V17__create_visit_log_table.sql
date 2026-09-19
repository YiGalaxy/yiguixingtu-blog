-- =====================================================================
-- V17：访问日志表 —— 后台"访问统计"页的数据来源
--
-- 【它和 article.view_count 是什么关系】
--   view_count 是**每篇文章一个累计数字**（Redis 计数 + 定时落库），
--   它回答"这篇文章总共被看了多少次"。这张表回答的是完全不同的一类问题：
--     · 今天/这一周每天的访问量曲线（要按时间分组，累计数字给不出）
--     · 访客构成（游客 / 注册用户 / 管理员各多少）
--     · 有多少是**不同的人**（按 IP 去重 —— PV 1000 也可能是同一个人刷的）
--     · 从哪来的（站内 / 外链 / 直接输入地址）
--     · 电脑还是手机
--   所以两者并存、各管一摊，**不合并**：把 view_count 拆成明细会丢掉
--   "总量"这个一眼就能看的数，而把明细塞进 view_count 是不可能的。
--
-- 【为什么存原始记录，而不是只存每天聚合好的计数】
--   聚合表磁盘几乎不涨，但它只能回答"提前想好要问的问题"——
--   比如现在没存 IP，将来想做"今天有多少不同的访客"就得从头再来一遍。
--   原始记录保留【90 天】后自动清理（见 VisitLogCleanupTask），
--   到时行数是有界的：日 PV 1000 的话大约 9 万行，对 MySQL 毫无压力。
--
-- ⚠️ 【IP 是个人信息】
--   这张表存的是访客的完整 IP。它对这个站有正当用途（看来源、识别刷量），
--   但**必须配保留期**（否则等于无限期留存个人信息）—— 清理任务就是为此存在的。
--   站长自己要知道这件事：README 里「访问统计」那一节写了存什么、存多久。
--
-- 【为什么存完整 IP 而不是脱敏成 1.2.3.*】
--   脱敏之后同一个小区/公司的人会变成一个 IP，"有多少不同访客"这个数会明显偏低，
--   而且出问题时无法据此定位来源。取的是"保留期 + 用途明确"这条路，
--   而不是"存得越少越好"—— 前提是保留期真的会执行（有单测钉着）。
-- =====================================================================

CREATE TABLE `visit_log`
(
    `id`           bigint       NOT NULL AUTO_INCREMENT,
    `path`         varchar(255) NOT NULL COMMENT '访问的路径（不含查询串；查询串里可能有搜索词，属于另一类隐私）',
    `article_id`   bigint                DEFAULT NULL COMMENT '访问的是文章页时记下文章 id（用于"哪几篇最热"），其它页面为 NULL',
    `visitor_type` varchar(16)  NOT NULL COMMENT '访客身份：GUEST 游客 / USER 注册用户 / ADMIN 管理员',
    `user_id`      bigint                DEFAULT NULL COMMENT '登录用户的账号 id（游客为 NULL）',
    `ip`           varchar(64)           DEFAULT NULL COMMENT '来源 IP（保留 90 天后随记录一起删除）',
    `referer_type` varchar(16)  NOT NULL COMMENT '来源类型：INTERNAL 站内跳转 / EXTERNAL 外链 / DIRECT 直接访问',
    `referer_host` varchar(255)          DEFAULT NULL COMMENT '外链时的来源主机名（只存主机名：完整地址可能带别人的查询参数，没必要存）',
    `device`       varchar(16)  NOT NULL COMMENT '设备：PC / MOBILE（只分两档，够回答"手机上看着方不方便"）',
    `create_time`  datetime     NOT NULL COMMENT '访问时间（应用显式赋值，不靠 DEFAULT —— 插入后要拿它算聚合）',
    PRIMARY KEY (`id`),
    -- 曲线按天聚合、清理按时间删，两者都靠这个索引
    KEY `idx_create_time` (`create_time`),
    -- "今天有多少不同访客"（COUNT DISTINCT ip）与"这个 IP 以前来过没有"都靠它
    KEY `idx_ip_create_time` (`ip`, `create_time`),
    -- "哪几篇文章最热"靠它；article_id 为 NULL 的行不进这个索引
    KEY `idx_article` (`article_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='访问日志（后台统计用；保留 90 天后自动清理）';

-- 【为什么没有存 User-Agent 原文】
--   表里只留了从它推导出来的 device（PC / MOBILE）——
--   原始 UA 串很长、隐私颗粒度更细（能指纹到具体机型与版本），
--   而这个站要回答的问题只是"手机还是电脑"。真要排查"为什么这条被算成手机"时，
--   把 UA 存下来的收益不足以抵消它带来的留存风险。
--
-- 【为什么 path 不含查询串】查询串里往往带着用户的输入（搜索词、来源标记），
--   存下来等于顺带记录用户搜过什么 —— 那不是这个功能要回答的问题。
--   应用层在写库前会把 ? 之后的部分去掉。
