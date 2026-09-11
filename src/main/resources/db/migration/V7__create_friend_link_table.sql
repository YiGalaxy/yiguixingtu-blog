-- =====================================================================
-- V7 · 友链表（friend_link）
--
-- 【这张表是干什么的】
--   "友情链接"是个人博客的老传统：把常看的、互相推荐的站点列在自己的页面下方。
--   在这之前它只能是前端写死的一段数组 —— 想加一个友链要改代码、重新构建、重新部署。
--   落到表里之后，加友链就变成后台点一下（和分类 / 标签是同一套后台管理形状）。
--
-- =====================================================================
-- 【三个刻意的设计决定，每个都能被追问】
--
-- ① 为什么【没有】唯一索引（name 不唯一、url 也不唯一）
--    · name（站点名）不是身份标识：两个站点完全可能都叫"某某的博客"。
--    · url 看起来很该唯一，但"唯一"会误伤正当场景：同一个站点换域名、
--      或者同时有带 www 与不带 www 的两个地址，管理员想留两条是合理需求。
--    · 更需要权衡的是【逻辑删除 + 唯一索引】这个本项目的已知坑（见 V5 注释）：
--      删掉的行物理上还在、唯一索引仍然占着那个值，
--      于是"删掉友链 A 再录一条同名 A"会直接撞 Duplicate entry，
--      而带 @TableLogic 的查重语句又看不见那行已删除的数据 ——
--      用户看到的是一个莫名其妙的 500（category / user 两张表都因为这个坑
--      额外加了"删除时改写名字"的代码）。
--      友链的数量在十几个量级、由管理员自己维护，重复录入肉眼就能看出来，
--      不值得为它背上这个坑。
--    ⇒ 结论：不加唯一索引，也不在 Service 里做查重。
--
-- ② 为什么【有】deleted（逻辑删除），而不是像 tag 那样物理删除
--    判断标准在本项目里只有一条：**这张表上有没有唯一索引**（README「数据库表」里写着）。
--      · tag 是物理删除 —— 因为它有 uk_name，逻辑删除会撞上面那个坑
--      · comment 是逻辑删除 —— 因为它没有任何唯一索引，那个坑不存在
--    友链属于后者：没有唯一索引，所以可以安心保留"删错了能恢复"的能力。
--    删除动作本身另有一条 DELETE_LINK 审计记录（谁、什么时候、删了哪个站点）。
--
-- ③ 为什么 status 的默认值是 1（显示），而 comment.status 的默认值是 0（待审核）
--    两者的"内容是谁写的"完全不同：
--      · comment 是【游客】写的 —— 默认待审核是防垃圾评论的第一道闸
--      · friend_link 是【管理员】自己录的 —— 录完就是要展示的，
--        默认藏起来只会让"加完友链前台看不到"，还得再点一次"显示"
--    status 的存在是为了"先录进来、暂时不显示"（比如对方的站点正在维护）。
-- =====================================================================

CREATE TABLE `friend_link` (
  `id`          bigint       NOT NULL AUTO_INCREMENT COMMENT '友链ID',
  `name`        varchar(50)  NOT NULL COMMENT '站点名称',
  -- 站点地址：必须是 http(s) 开头的完整地址。这条规则由 DTO 上的 @Pattern 兜住，
  -- 理由见 common/validation/UrlPatterns（存储型 XSS 的白名单做法）
  `url`         varchar(255) NOT NULL COMMENT '站点地址（http/https）',
  -- 头像/站点图标：可为空（没有图标时前端显示首字母方块）。
  -- 长度 255 与 upload 表里的封面地址一致：本地存储的路径是
  -- {前缀}/{年}/{月}/{32位uuid}.{扩展名}，最长的那个前缀也不会接近 255
  `avatar`      varchar(255) DEFAULT NULL COMMENT '头像/站点图标地址',
  -- 一句话介绍：200 字。为什么不是 255 —— 它在页面上是【一张卡片里的一行说明】，
  -- 超过 200 字就已经需要"展开"了，而友链卡片不会给展开入口
  `description` varchar(200) DEFAULT NULL COMMENT '一句话介绍',
  `sort`        int          NOT NULL DEFAULT '0' COMMENT '排序值，越小越靠前',
  `status`      tinyint      NOT NULL DEFAULT '1' COMMENT '状态：0隐藏 1显示',
  `create_time` datetime     DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted`     tinyint      NOT NULL DEFAULT '0' COMMENT '逻辑删除：0未删 1已删',
  PRIMARY KEY (`id`),
  -- 【索引：(status, sort)】
  --   前台查询的形状是 WHERE status = 1 ORDER BY sort ASC, id ASC ——
  --   等值条件在前、排序字段在后，正好是 MySQL 最能用上的顺序：
  --   status 定住范围之后，sort 在索引里本身就是有序的，省掉一次 filesort。
  --   （和 comment 的 idx_article_status、article 的 idx_status_create 是同一个思路）
  --   ⚠️ 这里【不需要】给 sort 单独建索引：这个表只有十几行，
  --   单列索引只会多一份写入代价；复合索引已经覆盖了唯一的那条查询。
  KEY `idx_status_sort` (`status`, `sort`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='友情链接表';

-- 【为什么这里没有外键约束、也没有种子数据】
--   · 外键：项目里其它表也都没建（理由写在 V5 的注释里：把完整性检查放到数据库层
--     会带来行锁竞争与隐式级联行为，而这里的删除逻辑在 Service 里只有一处）。
--   · 种子数据：不插。友链是"站长的私人收藏"，编几条假友链进迁移脚本，
--     上线之后就是要一条条删掉的垃圾。空表时前台显示"暂无友链"即可。
