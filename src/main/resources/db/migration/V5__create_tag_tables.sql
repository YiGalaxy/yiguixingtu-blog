-- =====================================================================
-- V5 · 标签表 + 文章标签关联表
--
-- 【为什么两张表放同一个迁移里】
--   它们是同一个特性的完整 schema：只有 tag 表的"标签"没有意义（没人能打标签），
--   只有 article_tag 也没有意义（不知道标签叫什么）。
--   分成 V5 / V6 会让中间那个版本处于"半成品"状态 —— 万一要回滚到那一版，
--   手上的表结构就是个不能用的中间态。迁移脚本应当以【可用的功能】为单位。
--
-- =====================================================================
-- 【⚠️ 这张表刻意【没有】deleted 字段，和 user / article / category 不一样】
--
--   先看清"逻辑删除 + 唯一索引"这个坑（user 表上踩过一次，见 V1 的注释）：
--   删除用 deleted=1 的话，物理行还在，唯一索引 uk_name 仍然占着名字 ——
--   于是"删掉标签 A 再新建同名标签 A"会撞 Duplicate entry，
--   而带 @TableLogic 的查重语句根本看不见那行已删除的数据，事前又查不出来。
--   当时对 user 表的解法是"删除时把用户名改写成 原名#deleted#id"。
--
--   但标签这里【不值得用那套解法】：
--     · 改名字段会污染 name（列表、筛选、URL 里都会看到 "技术#deleted#7" 这种东西）
--     · 标签是"可重建的轻量数据"：删错了重新建一个再挂回文章即可，
--       不像用户/文章那样有"历史记录不能丢"的分量
--     · 删除这件事【不是没留痕】：审计表里有一条 DELETE_TAG，记录了
--       谁在什么时候删掉了哪个名字的标签（见 M4.2 的操作审计）
--   所以这里选择【物理删除】，换来的是：唯一索引语义干净、没有任何"幽灵行"。
--
--   同理，article_tag 也是物理删除：它只是两张表之间的关联，
--   文章取消标签、标签被删除时都必须真的把关联去掉。
-- =====================================================================

-- ============ 标签表 ============
CREATE TABLE `tag` (
  `id`          bigint      NOT NULL AUTO_INCREMENT COMMENT '标签ID',
  `name`        varchar(30) NOT NULL COMMENT '标签名（唯一）',
  `sort`        int         NOT NULL DEFAULT '0' COMMENT '排序值，越小越靠前',
  `create_time` datetime    DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime    DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  -- 唯一索引不只是"防重复"：它同时是"按名字查标签"的索引。
  -- 标签名 30 字符是刻意的上限：它会出现在 URL、标签云和文章卡片上，
  -- 太长的名字在这三个地方都不好看（UTF-8 下 30 字符最多 90 字节，索引完全够用）
  UNIQUE KEY `uk_name` (`name`),
  -- 标签云的排序用（先按 sort 再按 id，保证顺序稳定）
  KEY `idx_sort` (`sort`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='文章标签表';

-- ============ 文章-标签关联表 ============
CREATE TABLE `article_tag` (
  `article_id`  bigint   NOT NULL COMMENT '文章ID',
  `tag_id`      bigint   NOT NULL COMMENT '标签ID',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '打标签的时间',
  -- 【为什么用联合主键而不是自增 id + 两个索引】
  --   ① 它天然保证"同一篇文章不会重复挂同一个标签"（业务上这就是一条硬约束，
  --      交给数据库比在 Service 里"先查再插"可靠 —— 后者在并发下会漏）
  --   ② 联合主键本身就是一个索引 (article_id, tag_id)，
  --      正好覆盖"查这篇文章的所有标签"这个最高频的方向
  PRIMARY KEY (`article_id`, `tag_id`),
  -- 反向方向：查"某个标签下的所有文章"（标签页要用）。
  -- 少了它，标签页会变成全表扫描 —— 关联表就是这么小，多一个索引的写入代价可以忽略
  KEY `idx_tag` (`tag_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='文章标签关联表';

-- 【为什么这里没有外键约束】
--   项目里其它表也都没建外键（比如 article.category_id）。
--   原因不是"忘了"，而是：外键把"引用完整性"的检查放到了数据库层，
--   它会带来行锁竞争与级联删除的隐式行为，而删除标签时的清理逻辑
--   （删标签必须同时删掉它的关联行）在 Service 里已经有了明确的一处实现。
--   一处逻辑、一个地方读得懂，比"一半在代码一半在数据库"更好排查。
