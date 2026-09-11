-- =====================================================================
-- V11 · 音乐表（music）
--
-- 【这张表是干什么的】
--   前台的「音乐」页此前只有一个写死的曲目（曲名、歌手、封面、歌词全在前端代码里，
--   音源是 static-media/bg-music.mp3）。要换一首歌就得改前端、重新构建部署。
--   现在改成后台录入：上传 mp3 + 填曲名 / 歌手 / 封面 / 歌词，存进这张表，
--   前台播放器读 GET /music/list 渲染。
--
-- =====================================================================
-- 【⚠️ 这个迁移【故意不插任何种子数据】—— 库里一首都没有是正常状态】
--   前端在"列表为空"时会【回落成内置的那一首】（static-media/bg-music.mp3，
--   见前端 app/utils/media.ts 的 MEDIA_FILES 与音乐页的兜底逻辑）：
--   那是它本来就有的行为，不是"等后端把数据补上"。
--   所以：
--     · 迁移里【不】插一首"示例曲目"来占位 —— 编一首不存在的歌进数据库，
--       上线之后第一件事就是删掉它，而且前台的兜底分支反而永远测不到
--     · 空表启动之后前台音乐页照常能打开（显示内置那一首），不是错误页
--   ⇒ 看到这张表是空的时候，不要去"补数据"，也不要把空列表当成接口坏了。
--
-- =====================================================================
-- 【四个刻意的设计决定】
--
-- ① url（音频地址）是 NOT NULL，但【没有】任何默认值
--    一首没有音源的音乐没有任何意义 —— 它是这张表的"主体"，
--    其余字段（歌手 / 封面 / 歌词）都只是它的补充说明。
--    所以 DTO 上它是 @NotBlank，这里也是 NOT NULL，两处一致。
--
-- ② lyrics 存的是【LRC 原文文本】，不是文件路径
--    列类型 text（64KB）：一份几百行的 LRC 也就几 KB，用不着 longtext
--    （文章正文用 longtext 是因为它可能真的长；这里用不上那个容量）。
--    为什么不存"歌词文件的地址"：那等于把一份文本塞进 URL 字段，
--    前端还得再发一次请求去取，而且多一条"文件丢了但数据库里看着正常"的路径。
--    DTO 上限制 20000 字（见 MusicForm），比 text 容量小得多 —— 那是刻意的：
--    再长的"歌词"已经不是歌词了，该挡住而不是先存进去再想办法。
--
-- ③ artist 可为空，cover 可为空
--    纯音乐没有歌手；封面读不到时前端用自带的兜底图。
--    这两个字段都只是展示信息，缺了不影响播放 —— 所以允许为空，
--    而不是"必须凑齐四个字段才能存一首歌"。
--
-- ④ 其余与友链 / 项目 / 收藏同形：status 默认 1（显示）、逻辑删除、
--    sort 越小越靠前
--
-- 【为什么索引是 (status, sort, id) 而 F5 那几个模块是 (status, sort)】
--   因为前台那条查询的 ORDER BY 是【两段】的：sort ASC, id ASC
--   （契约：sort 相同的两首按 id 升序 —— 顺序必须稳定，否则同一个 sort 的两首歌
--     在两次请求里的先后可能不一样，用户会看到列表"自己在跳"）。
--   把 id 也放进索引，这条 ORDER BY 就完全由索引顺序满足，连 filesort 都不需要；
--   而 (status, sort) 还要在 sort 相同的组内回表按主键排一次。
--   ⚠️ 数字列 status(1 字节) + sort(4) + id(8) = 13 字节/行，几十条数据的表上，
--      这点空间换掉一次排序非常划算。F5 那几个模块同样可以这么加，
--      但它们的写入已经上线、索引改动要单独的迁移，所以这里只在新表上做。
-- =====================================================================

CREATE TABLE `music` (
  `id`          bigint       NOT NULL AUTO_INCREMENT COMMENT '音乐ID',
  `title`       varchar(100) NOT NULL COMMENT '曲名',
  -- 歌手：可为空（纯音乐没有歌手）。只卡长度，不做格式校验 —— 它只被当文字渲染
  `artist`      varchar(100) DEFAULT NULL COMMENT '歌手',
  -- 音频地址：必填。既允许上传接口返回的 /uploads/music/... ，也允许 http(s) 外链
  -- （格式由 DTO 上的 @Pattern 白名单保证，见 common/validation/UrlPatterns.MEDIA_URL）。
  -- 长度 500：比 cover 的 255 长 —— URL 里带签名参数的对象存储地址很容易超过 255
  `url`         varchar(500) NOT NULL COMMENT '音频地址',
  -- 封面：可为空，走图片白名单（会进 <img src>）
  `cover`       varchar(255) DEFAULT NULL COMMENT '封面图地址',
  -- 歌词：LRC 原文文本（不是文件路径），理由见上面 ②
  `lyrics`      text         COMMENT '歌词（LRC 原文）',
  `sort`        int          NOT NULL DEFAULT '0' COMMENT '排序值，越小越靠前',
  `status`      tinyint      NOT NULL DEFAULT '1' COMMENT '状态：0隐藏 1显示',
  `create_time` datetime     DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted`     tinyint      NOT NULL DEFAULT '0' COMMENT '逻辑删除：0未删 1已删',
  PRIMARY KEY (`id`),
  -- 覆盖前台那条查询：WHERE status = 1 ORDER BY sort ASC, id ASC
  -- （等值条件在前、两个排序字段在后，见上面第四条注释里为什么 id 也要进来）
  KEY `idx_status_sort_id` (`status`, `sort`, `id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='音乐表';

-- 【再次强调：这里没有 INSERT】—— 空表是正常状态，理由见文件开头那段 ⚠️。
-- 前端列表为空时会回落成内置的 static-media/bg-music.mp3，不是"缺数据"。
