-- =====================================================================
-- V10 · 关于表（about）—— 全站唯一一张【只有一行】的表
--
-- 【它存什么】
--   关于页要展示的"站长是谁"：昵称 / 头像 / 自我介绍 / 邮箱 / GitHub / 微信 / QQ。
--   此前这一页的内容写死在 Vue 模板里，改一句话要重新构建部署。
--
-- =====================================================================
-- 【这张表和别的表有四个根本区别，每个都要说清楚】
--
-- ① 它永远只有一行，而且 id 固定为 1
--    所以没有"列表"这个概念，接口也就没有分页、没有筛选、没有 id 参数。
--    主键在代码里用 IdType.INPUT 显式指定成 1（不是 AUTO）——
--    语义是"主键由我给"，而不是"依赖数据库自增再回填"。
--
-- ② 只有 update_time，没有 create_time
--    单条记录没有"创建"这个语义：它是站点的一部分，从建站起就在那里、只会被改。
--    留一个永远是建站时间的 create_time，只会让每次读都要判断"这个字段到底有没有意义"。
--
-- ③ 没有 deleted
--    它是【全站唯一一张没有逻辑删除的业务表】（operation_log 也没有，但那是审计，
--    理由完全相反：审计是"不许抹掉痕迹"）。这里是因为"删掉关于页"这个需求不存在 ——
--    真不要了，把字段清空即可，不需要一个"行还在但被标记删除"的状态让每个查询去过滤。
--    ⚠️ 代价与兜底：万一有人手工把这一行删了，前台的 GET 会返回一个字段全空的对象
--    （页面照常打开，只是没有内容），后台点一次"保存"就会把这行重新写回来 ——
--    见 AboutServiceImpl 里的自愈逻辑与对应用例。
--
-- ④ bio 用 text 而不是 longtext
--    自我介绍 64KB（约几万个汉字）绰绰有余。文章正文用 longtext 是因为它可能真的很长；
--    这里用不上那个容量，选小一号的类型能少一分"有人往里塞大对象"的风险。
--
-- 【迁移里直接插入那一行】
--   因为"单条记录"这个不变量最好由 schema 层保证：空库启动之后，
--   前台第一次访问 /about 拿到的就是一个结构完整的对象，而不是一个 404。
--   初始值只填 nickname（NOT NULL），其余留空 —— 【不编造内容】：
--   编一段假的自我介绍进迁移脚本，上线之后第一件事就是删掉它。
-- =====================================================================

CREATE TABLE `about` (
  -- 固定为 1：这张表永远只有这一行（见上面 ①）
  `id`          bigint       NOT NULL COMMENT '固定为 1（这张表只有一行）',
  `nickname`    varchar(50)  NOT NULL COMMENT '昵称',
  -- 头像：可为空（没有头像时前端显示昵称首字母的方块）。
  -- 允许上传接口返回的绝对地址，也允许 / 开头的站内路径（见 common/validation/UrlPatterns）
  `avatar`      varchar(255) DEFAULT NULL COMMENT '头像地址',
  -- 自我介绍：长文本（理由见上面 ④）。DTO 上限制 5000 字：
  -- 再多就该单独写一篇文章，而不是塞在关于页里
  `bio`         text         COMMENT '自我介绍（长文本，可写 Markdown）',
  -- 邮箱：给访客发信用的（前端渲染成 mailto:）。长度与 comment.email 一致
  `email`       varchar(100) DEFAULT NULL COMMENT '邮箱',
  -- GitHub：只放行 http(s) 的完整地址（它会被渲染成 <a href>）
  `github`      varchar(255) DEFAULT NULL COMMENT 'GitHub 地址',
  -- 其它联系方式：微信 / QQ。⚠️ 它们【不做格式校验】——
  -- 微信号可以是字母数字下划线，QQ 也可能被填成"QQ 邮箱"或昵称，
  -- 加格式规则只会挡住正当输入；这两个字段也不会被渲染成链接（只是文字）
  `wechat`      varchar(50)  DEFAULT NULL COMMENT '微信',
  `qq`          varchar(30)  DEFAULT NULL COMMENT 'QQ',
  -- 只有 update_time，没有 create_time / deleted（理由见上面 ② ③）
  `update_time` datetime     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='关于页信息（全表只有一行）';

-- 建表的同时把那一行插进去：让"单条记录"这个不变量由 schema 保证（见上面最后一段）。
-- 用 INSERT ... 而不是"靠代码在启动时补"——启动期写库的任务很容易在并发/多实例下写出第二行。
INSERT INTO `about` (`id`, `nickname`) VALUES (1, '站长');
