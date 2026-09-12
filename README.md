# yiguixingtu — 个人博客系统（后端）

[![CI](https://github.com/YiGalaxy/yigalaxy-blog-new/actions/workflows/ci.yml/badge.svg)](https://github.com/YiGalaxy/yigalaxy-blog-new/actions/workflows/ci.yml)
![Java](https://img.shields.io/badge/Java-17-blue)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-4.1.1-brightgreen)
![Tests](https://img.shields.io/badge/tests-463%20passing-success)
![Coverage](https://img.shields.io/badge/coverage-91%25-brightgreen)

> 基于 Spring Boot 4 + MyBatis-Plus + JWT 的个人博客后端服务
> Spring Boot 4.1.1 / Java 17 / MySQL 8 / Redis 7
>
> **463 个集成测试全部通过**（覆盖行 91.1%），测试自带 MySQL / Redis 容器，clone 下来即可验证。

## 项目简介

**yiguixingtu** 是一个个人博客系统的后端服务，已实现 **认证与用户管理**、**文章管理**、
**分类**、**标签**、**评论与审核**、**友情链接 / 项目 / 收藏 / 关于页 / 音乐**、
**站点设置**（站点名 / 首页公告 / 评论总开关 / 页脚版权与两个备案号（ICP + 公安网安）/ 每页条数）、**操作审计** 等模块，
采用 **JWT 无状态认证**，**MySQL** 存储数据、**Redis** 缓存认证信息与文章缓存，接口文档由 **springdoc** 自动生成。

前端为独立仓库 `yiguixingtu-web`（Nuxt 4 + Vue 3 + Element Plus），通过 HTTP 调用本服务。

数据库设计上所有业务表统一带 `create_time` / `update_time` / `deleted` 三个字段：
时间字段交给 MyBatis-Plus 与数据库默认值处理，`deleted` 配合 `@TableLogic` 实现**逻辑删除**——
删掉的文章/用户不会真的消失，只在查询时被自动过滤掉。

## 技术栈

| 类别 | 技术 |
|------|------|
| 框架 | Spring Boot 4.1.1 |
| 语言 | Java 17 |
| 构建 | Maven |
| ORM | MyBatis-Plus 3.5.17（含 `mybatis-plus-jsqlparser` 分页/条件构造） |
| 数据库 | MySQL 8 |
| 数据库迁移 | Flyway 12（`spring-boot-flyway` + `flyway-core` + `flyway-mysql`） |
| 缓存 | Redis 7（缓存认证信息 / Token 黑名单 / 文章列表 / 文章详情，另用作浏览量计数器，见「认证与鉴权」「文章缓存」） |
| 安全 | Spring Security 7 + JWT（jjwt 0.12.6）+ BCrypt |
| 参数校验 | Spring Validation（`spring-boot-starter-validation`） |
| 接口文档 | springdoc-openapi 3.0.0（OpenAPI / Swagger UI） |
| 运维监控 | Spring Boot Actuator + Micrometer（Prometheus registry），见「可观测」 |
| 限流 | Resilience4j 2.4.0（`resilience4j-spring-boot4`）—— 注解式限流，**不写自研切面**；与 Nginx `limit_req` 组成两层，见「部署」章节 |
| 链路追踪 | Micrometer Tracing + Brave（traceId 进日志 + 响应头 `X-Trace-Id`），见「可观测」 |
| 操作审计 | Spring 事件机制（`@TransactionalEventListener` + `@Async`）+ 自建 `operation_log` 表，**不写自研切面**，见「操作审计」 |
| 文件存储 | 图片、音频与**文章附件**都存服务器本地磁盘 + 具名卷持久化（上传目录有 5GB 总量护栏）；**不接对象存储**（理由与"将来怎么换"见「文件上传」章节） |
| 工具库 | Lombok |
| 测试 | JUnit 5 + MockMvc（`spring-boot-starter-webmvc-test`）+ **Testcontainers 2.0.5** |
| 代码覆盖率 | JaCoCo 0.8.13 |
| 持续集成 | GitHub Actions（`.github/workflows/ci.yml`） |

## 功能特性

### ✅ 已实现

**认证与用户**
- 用户注册（BCrypt 密码加密，用户名唯一）
- 用户登录（签发 JWT）、退出登录（**旧 token 立即失效**）、获取当前登录用户
- JWT 无状态鉴权（请求头 `Authorization: Bearer <token>`）
- 角色支持（`ADMIN` 管理员 / `GUEST` 游客），接口级权限用 `@PreAuthorize`
- 用户管理（管理员）：分页查询、启用/禁用、改角色、重置密码、删除
- **禁用 / 删除 / 降级用户后，其已签发的 token 立即失效**（见下文「缓存与 token 即时撤销」）

**文章**
- 文章新增 / 编辑 / 删除（逻辑删除）/ 发布与下架
- 后台文章分页（可按状态、分类、关键词筛选，支持排序白名单）
- 前台已发布文章分页（游客可访问，支持关键词搜索）
- 文章详情（Markdown 源码正文，游客可访问）
- **草稿隔离**：草稿只有管理员能看到，游客直接访问返回 **404**（而不是 403，避免泄露"这里有一篇草稿"）
- **浏览量异步计数**：详情页只写 Redis，定时任务每 5 分钟批量落库（见下文「浏览量为什么是异步的」）
- **文章列表缓存**：前台列表走 Redis 缓存，写操作用**版本号**同步失效；
  带**防穿透**（空结果也缓存 30 秒）与**防雪崩**（TTL 随机抖动）—— 见下文「文章列表缓存」
- **文章详情缓存**：同样走 Redis，写操作后立刻更新；带**防击穿**
  （`sync = true` 互斥重建，实测 12 线程并发只查库 1 次）——
  注意**浏览量不能被缓存冻住**，见下文「文章详情缓存」
- **站点统计**：`GET /article/stats` 返回已发布文章数 / 总浏览量 / 分类数，
  只算已发布、逻辑删除不计入，走 Redis 缓存 60 秒（写操作后失效）
- **归档**：`GET /article/archive` 把已发布文章**按年月分组**（最新月份在前、
  月内也按时间倒序），只返回 id / 标题 / 时间三个字段 ——
  归档页只用来"找到那篇文章"，多传摘要和封面纯属浪费
  （几百篇的话差别就是几十 KB）。分组在后端做：前端拿到的列表是分页的，
  自己分组会得到"每月只有前几条"的错误结果。带 500 篇上限兜底，同样走缓存
- **RSS 数据**：`GET /article/rss` 给最近 **20 篇已发布文章的正文**（一条查询取回，
  而不是让前端拿 id 逐篇去查——那样是 20 次请求）。**XML 由前端拼**（站点域名、
  feed 标题只有前端知道，和 sitemap.xml 同一个做法）；正文给的是 Markdown 源码，
  转 HTML 交给前端的渲染器，避免"同一份 Markdown 两套渲染规则"
- **文章附件**（`attachments`）：每篇文章可以挂最多 **20 个**可下载文件
  （PDF / 压缩包 / Office 文档 / 文本 / mp3 / mp4，单个 ≤ 100MB，
  走 `POST /upload?type=attachment`）。⚠️ 白名单里**绝不能有 html / svg / xml / js**，
  且附件响应强制下载 —— 这两件事合起来挡的是"在自己域名下执行脚本"的存储型 XSS，
  详见「文件上传」章节
  - ⚠️ **附件不单独落库**：它随表单提交、保存文章时**整体替换**
    （与 `tagIds` 完全一致的语义：前端提交什么，库里就是什么）。
    不在上传时就插一行，是因为**新建文章那一刻还没有 article_id**，
    那样会产生一堆没人认领的悬空记录；用户传完不保存也一样。
    代价是"传了没保存"的文件留在磁盘上，只能按目录清理 —— 比库里出现
    "指向不存在文章的附件行"要好处理得多（判断过程见 V14 迁移脚本的注释）
  - **删文章时级联清理文件**：附件行 + 物理文件、正文引用的图片、封面图都会清掉，
    但**删之前先查"还有没有别处还在用这个文件"**（其余文章的正文/封面、其余文章的附件行、
    还在的曲目 —— 三张表都查），共用的**一律不动** —— 同一张图完全可能被两篇文章用，
    音频也可能既在音乐列表里、又被某篇文章正文嵌着，无脑删会把对方删成裂图/哑巴，
    而文件删除不可逆。判定逻辑与删除时机见 `ArticleServiceImpl.remove` 的长注释，
    用例见 `ArticleAttachmentTest` ⑬⑭（★ 共用图不误删 + 独占图确实被删，两个方向都断言）
    与 `MusicFileCleanupTest` ⑤（★ 反向：歌还在时删文章也不会误删）

**分类**
- 分类列表（游客可访问，走 Redis 缓存 —— 首页 SSR 每次都要用它，而它只在分类被改时才变）
- 后台增删改（仅管理员）：改名 / 改描述 / 改排序
- **分类下还有文章时拒绝删除**（返回 400 + "还有 N 篇在用"）：删掉分类后那些文章
  仍然引用着它的 id，而分类已经查不到了 —— 文章会变成"没有分类名"且看不出原因。
  宁可拒绝并让用户先调整文章，也不要留下这种数据不一致
- ⚠️ 分类是**逻辑删除**，而 `category` 上有唯一索引 `uk_name`：所以删除时会像
  `user` 表那样把名字改写成 `原名#deleted#id`，把名字释放出来 ——
  否则"删掉分类 A 再建同名 A"会直接撞 `Duplicate entry`，
  而带 `@TableLogic` 的查重语句又看不见那行已删除的数据（**同一个坑、同一套解法**；
  标签当初选了物理删除，所以它没有这个问题，两处的取舍分别写在 V5 与实体注释里）

**标签**
- 标签的增删改查（后台，仅管理员），标签名唯一、带排序值
- **文章与标签是多对多**：一篇文章可挂多个标签，靠 `article_tag` 关联表；
  文章的新建 / 编辑用**覆盖式**语义（提交什么就是什么，不是增量）——
  编辑界面里少勾一个标签，保存之后它就真的没了
- 前台标签列表 `GET /tag/list`（游客可访问）：**每个标签带「已发布文章数」**，
  一条 `JOIN + GROUP BY` 一次算完（否则前端要为每个标签各发一次请求去数 —— 典型的 N+1）
- 口径与文章统计一致：**草稿不计入**（否则访客会看到"计数 5、点进去只有 2 篇"）
- **按标签筛选文章**：`GET /article/page?tagId=` 只返回该标签下的已发布文章，
  可与关键词、分类条件叠加（AND 语义）；标签不存在时返回**空页**而不是报错
  （用户可能停在旧页面上，标签刚被管理员删掉）
- 文章列表与详情都带 `tags`（**整页一次 IN 查询**取回后内存分组，避免 N+1）；
  没有标签时是**空数组**而不是 `null`，前端可以直接遍历
- **标签是物理删除**（项目里唯一这样做的业务表）：逻辑删除会和 `uk_name` 唯一索引打架
  （删掉的名字仍然占着索引，导致"删了再建同名标签"直接报 Duplicate entry），
  而"删除时改写名字"那套绕过办法的代价（名字被污染成 `技术#deleted#7`）标签不值得付 ——
  删除这件事本身由审计表留痕，见 V5 迁移脚本里的完整说明；删标签会连带清理它的文章关联
- 标签的**增 / 改 / 删都会进操作审计**（`CREATE_TAG` / `UPDATE_TAG` / `DELETE_TAG`）
- 标签列表走 Redis 缓存，**和文章缓存共用同一个版本号**：
  任何标签写操作或文章写操作（含"给文章打标签"）都会推进版本号，一次 bump 让两边同时失效 ——
  所以"打上标签之后，标签云里的文章数会立刻 +1"

**评论**
- **游客就能评论**（不要求注册）：博客的评论区是读者说话的地方，
  要求注册等于把绝大多数读者挡在门外，而本站并没有要运营的用户体系
- **默认待审核，审核通过才对外可见**（`status`：0 待审核 / 1 已通过 / 2 已拒绝）——
  这是防垃圾评论最主要的一道闸：提交成功不等于别人能看见。
  前台的查询**写死 `status = 1`**（和文章前台写死 `status = 1` 同一个套路），
  前端传 `?status=0` 也拿不到未审核的评论 —— 安全规则不能写成"默认值"
- **三重防滥用**：① 默认待审核 ② 发表接口限流（20 次/分钟，**整站**配额）
  ③ 昵称/内容长度上限 + **入库前 HTML 转义**（评论是全站唯一一个
  "任何人都能往数据库写内容"的入口，转义做在入库这一层，
  将来不管是前端、RSS 还是导出脚本渲染都不会执行到脚本）
  - ⚠️ 转义**只针对 `& < > " '` 这 5 个危险字符**。这里有个踩过的坑：
    第一版用的 Spring `HtmlUtils.htmlEscape` 会把"有 HTML 具名实体的字符"一起转掉
    （`→` 变 `&rarr;`、`—` 变 `&mdash;`、`…` 变 `&hellip;`），
    而前端渲染时还会再转义一次 —— 用户在页面上看到的就是字面的 `&rarr;`。
    中文破折号「——」和省略号「……」都中招，一眼可见，是**联调时被前端发现的**。
  - ⚠️ 顺序是**先转义、再按列长度截断**（反过来会让"符号特别多"的内容
    转义后撑爆 `varchar(1000)`，用户收到 500），且截断不切断实体
  - 限流**按请求次数计**（不是按成功的评论数）：用必然失败的请求连打也会耗配额 ——
    这是刻意的，滥用者发的大多是无效请求，只统计成功等于给攻击者留了无限刷的口子；
    要按人限制就该按 IP 记账，那是 Nginx 那层的事
- **草稿文章不能评论**（草稿对外根本不存在，能对它评论说明调用方拿到了不该拿的 id）
- **公开接口不返回 email 与 ip**：后台用的是另一个 VO（`AdminCommentVO`），
  让"前台不可能拿到读者隐私"这件事由**类型**保证，而不是靠"记得别加那个字段"
- 前台评论按时间**正序**（对话读起来自然），后台**倒序**（管理员关心最新的待审核）；
  后台列表带上文章标题（一页评论只多一次 IN 查询，避免 N+1）
- 评论用**逻辑删除**（和标签不同）：它没有任何唯一索引，
  所以不会撞上"逻辑删除 + 唯一索引"那个坑，可以安心保留"删错了能恢复"的能力
- 评论的**审核与删除**进操作审计（`UPDATE_COMMENT_STATUS` / `DELETE_COMMENT`），
  但**发表评论本身不记** —— 评论是内容、数量会持续增长，
  把每条公开评论都塞进审计表只会让真正要追溯的管理动作被淹没

**友链**
- 友链列表 `GET /link/list`（游客可访问）：只返回 **`status = 1`（显示）** 的那些，
  按 `sort` 升序、`sort` 相同再按 id 升序（少了第二段，同 `sort` 的两条刷新一次就可能换位置）。
  过滤写在 SQL 里、**不是交给前端** —— 后台把一条友链设成隐藏，
  它就该立刻从前台消失，而不是"接口还返回着、只是页面没渲染"
- 后台增删改（仅管理员）：`/admin/link/**` 的 list / create / update / delete。
  后台列表**含隐藏的那些、且不走缓存** —— 管理员刚点完"隐藏"就该看到效果，
  走缓存只会带来"是不是没保存成功"的疑惑（和标签后台列表同一个决定）
- **地址与图片都做格式白名单**（`common/validation/UrlPatterns`）：站点地址只接受
  `http(s)://` 开头的完整地址。这些字段最终会进前台的 `<a href>` 与 `<img src>`，
  一个 `javascript:` 开头的"友链地址"就是一条**存储型 XSS**（白名单只有两条规则，
  黑名单永远列不全）；图片额外允许 `/` 开头的**站内路径**（图放在前端 `public/` 下
  是常见做法），清空输入框提交的 `""` 按"清空"归一化成 `NULL`
- **默认显示，而不是默认隐藏**（`status` 默认 1）：和评论"默认待审核"刚好相反 ——
  评论是游客写的，友链是管理员自己录的，录完就是要展示的
- **逻辑删除**（`deleted`）：`friend_link` 上没有任何唯一索引，所以不会撞上
  "逻辑删除 + 唯一索引"那个坑，可以保留"删错了能恢复"的能力
  （判断标准只有一条：这张表上有没有唯一索引 —— `comment` / 友链是一类，`tag` 是另一类）
- `sort` / `status` **不传时的行为与 `tag` / `category` 完全一致**：新建时按 0 / 显示处理，
  编辑时保持原值（"不传"和"想改成 0"必须能区分开）
- 增 / 改 / 删都进操作审计（`CREATE_LINK` / `UPDATE_LINK` / `DELETE_LINK`，对象类型 `LINK`）
- 前台列表走 Redis 缓存，key 里带**内容缓存版本号**（`content:list:version`）——
  站点内容模块（友链 / 项目 / 收藏 / 关于 / 音乐）共用这一个计数器：
  它们和文章毫无关系，若沿用文章那个版本号，"加一条友链"会把文章列表 / 详情 /
  归档 / RSS / 分类 / 标签六份缓存一起作废（功能不错，但纯属无谓的重新查库）

**项目**
- 项目列表 `GET /project/list`（游客可访问）：只返回 `status = 1` 的那些，
  按 `sort` 升序、`sort` 相同再按 id 升序；过滤同样写在 SQL 里（同友链）
- 后台增删改（仅管理员）：`/admin/project/**` 的 list / create / update / delete；
  后台列表含隐藏的、不走缓存
- **一条本项目特有的业务规则**：在线地址 `url` 与仓库地址 `repo` **可以各为空、
  但不能同时为空** —— 两个都空的项目卡片点不出任何东西，访客只会以为它坏了。
  这条规则跨两个字段，Bean Validation 的单字段注解表达不了，
  所以写在 Service 里（所有调用路径的必经之地）；编辑时同样校验，
  且**校验发生在写库之前** —— 被拒绝的请求不会把原有的地址清掉
- `tech`（技术栈）是**一个逗号分隔的字符串**，不是关联表：它只用于展示
  （前端 `split(',')` 渲染成一行小标签），我们没有"按技术栈筛选项目"
  或"统计某个技术栈用在几个项目上"的需求，不值得为它背上
  "覆盖式重写关联"那套复杂度（文章标签值得，因为标签页是文章的主要导航方式之一 —— 项目不是）
- 其余与友链同形：地址与封面走同一套 URL 白名单、清空输入框的 `""` 归一化成 `NULL`、
  逻辑删除、增 / 改 / 删进审计（`CREATE_PROJECT` / `UPDATE_PROJECT` / `DELETE_PROJECT`，
  对象类型 `PROJECT`）、前台列表走 Redis 缓存并共用内容缓存版本号

**收藏**
- 收藏列表 `GET /favorite/list`（游客可访问）：只返回 `status = 1` 的那些，
  按 `sort` 升序、`sort` 相同再按 id 升序；后台 `/admin/favorite/**` 是同一套增删改查
- ⚠️ **`category`（分组）是自由文本，不是那张 `category` 表** —— 这是最容易搞混的一处：
  `category` 表是**博客文章的栏目**（有唯一索引、删之前还要数"还有几篇文章在用"），
  而收藏的分组是**个人书签的分类**（工具 / 文章 / 视频 / 学习资料）。
  如果让收藏去引用 `category.id`，会立刻撞上两个问题：① 删分类时的"还有没有文章在用"
  检查数不到收藏 —— 分类删掉之后收藏的分组名就变成查不到的 id；
  ② 想加一个只用于收藏的"工具"分组，就得往**文章栏目**里塞一条永远没有文章的记录。
  所以它不建关联、不做校验（"面试题/八股"这种带斜杠的写法也合法），只卡长度；
  空值就是**未分组**
- **`url` 是几个内容模块里唯一必填的地址**：一条没有地址的收藏没有意义
  （它不是待办，是"以后还要再来一次"的入口）。格式同样走 http(s) 白名单
- **`title` 不做抓取**：后端不会去访问目标网页读它的 `<title>` ——
  那要发外部请求（SSRF 风险、慢、对方改版或页面没有标题时还拿不到），
  而这张表由站长自己维护，填标题时顺手写一个更准
- 分组由**前端**做（后端一次返回全部、按 `sort` 排好）：后端不写 `GROUP BY`，
  因为分组名是自由文本，SQL 的分组与前端的分组在"首尾空格 / 大小写"上会得出不同结果
- 其余与友链 / 项目同形：`""` 归一化成 `NULL`、逻辑删除、增 / 改 / 删进审计
  （`CREATE_FAVORITE` / `UPDATE_FAVORITE` / `DELETE_FAVORITE`，对象类型 `FAVORITE`）、
  前台列表走 Redis 缓存并共用内容缓存版本号

**关于**
- 关于页信息 `GET /about`（游客可访问）：昵称 / 头像 / 自我介绍（Markdown 原文）/
  邮箱 / GitHub / 微信 / QQ，走 Redis 缓存
- 后台只有**一个**接口 `PUT /admin/about`（类级管理员）：它是全站唯一一份单条数据，
  所以**没有新建、没有删除、路径上也没有 `{id}`** —— 只有一个能改的对象，
  留一个 id 参数只会多一处"传错 id"的可能。**POST /admin/about 是真 405**，
  说明"只有一份数据"这件事在接口形状上是真的（不是靠文档约定）
- **全站唯一一张只有一行、且没有 `deleted` 的业务表**（`operation_log` 也没有逻辑删除，
  但理由正相反：审计是"不许抹掉痕迹"，这里是"删掉关于页"这个需求不存在）。
  只有 `update_time`、没有 `create_time`：单条记录没有"创建"的语义
- **那一行由迁移脚本插好**（`id = 1`）：空库启动之后前台第一次访问就能拿到结构完整的对象。
  迁移里不编造内容（只填了 NOT NULL 的昵称），编一段假的自我介绍上线后第一件事就是删掉它
- ⚠️ **万一那一行被人手工删了，系统会自己撑住**：`GET /about` 仍然返回 **200 +
  一个内容为空的壳**（前台关于页照常打开，而不是变成一个错误页），
  后台点一次"保存"会把它**写回来**（不会出现第二行）。这是对"单条只改不建"的
  一处**有意放宽**，两半都有用例守着（`AboutTest` ⑫）——
  公开页面的可用性优先于"把数据缺失变成显式错误"，而"保存成功却什么都没发生"
  是最糟的一种失败
- 校验按"字段会不会被浏览器解释"分档：`avatar` / `github` 走 URL 白名单
  （它们会进 `<img src>` / `<a href>`）、`email` 校验格式（它会进 `mailto:`），
  而 `nickname` / `wechat` / `qq` **只卡长度不做格式校验** ——
  微信号可以带下划线、QQ 栏里有人填邮箱或昵称，加规则只会挡住正当输入
- 保存进操作审计（`UPDATE_ABOUT`，对象类型 `ABOUT`，`target_id` 恒为 1）：
  按 `target_type = 'ABOUT'` 查就能得到"关于页被谁改过几次"的完整历史

**音乐**
- 音乐列表 `GET /music/list`（游客可访问）：只返回 `status = 1` 的那些，
  按 `sort` 升序、`sort` 相同再按 `id` 升序（**两段排序是前台的契约** ——
  只按 `sort` 排的话，同一个 `sort` 的两首在两次请求里先后不定，列表会"自己跳"；
  V11 的索引 `(status, sort, id)` 就是照这条查询建的）；
  后台 `/admin/music/**` 是同一套增删改查
- **曲目由后台录入**：先 `POST /upload?type=audio` 传 mp3（返回的地址形如
  `/uploads/music/2026/09/{uuid}.mp3`），再填曲名 / 歌手 / 封面 / 歌词存进 `music` 表。
  在此之前曲目是写死在前端的，换一首歌要改代码重新部署
- ⚠️ **`lyrics` 存的是 LRC 原文文本（TEXT），不是文件路径**：
  时间戳由前端自己解析（`app/utils/lrc.ts`）。后端不解析 ——
  解析结果（什么时间点显示哪一行）是纯展示逻辑，放后端只会多一份要跟着前端改的东西。
  上限 20000 字：一份带逐句时间戳的 LRC 很容易超过 2000 字，卡在那里只会挡住正当输入
- ⚠️ **`url` 走的是 `MEDIA_URL` 白名单，不是 `IMAGE_URL`**：两者规则相同
  （http(s) 外链 / `/` 开头的站内路径 / 空串放行），但语义不同 ——
  用 `IMAGE_URL` 会让后来的人以为"音频地址被限制成了图片"。
  为什么不合并成一个常量，见 `common/validation/UrlPatterns` 的注释
- ⚠️ **删除会连带清掉磁盘上的 mp3 —— 但先确认没有别人在用**（数据库行仍是逻辑删除）：
  删歌时按顺序问三处：① 还有别的曲目用同一个文件吗（`music.url` 没有唯一约束，
  同一份音源被两条记录共用是可能的）② 有文章在正文/封面里引用了这个地址吗
  （音频与图片同在 `/uploads/` 下，正文里嵌一段 `<audio src="...">` 是同源可达的）
  ③ 有文章的附件行指向这个地址吗。三处都为零才删文件。
  ⚠️ `url` 是 http(s) 外链时（音源放在自己的对象存储/CDN 上）不属于本站上传目录，
  **一个字节都不碰**。判错的代价不对称：多留一个文件只是浪费磁盘，
  漏认一次就是删掉别人正在用的资源且不可恢复 —— 所以宁可保守。
  用例见 `MusicFileCleanupTest`（每一条都带反向断言：该留的留、该删的真的删了）
- ⚠️ **迁移里刻意不插任何种子数据**：库里一首都没有是**正常状态** ——
  前端在列表为空时会回落成内置的那一首（`static-media/bg-music.mp3`）。
  看到 `music` 表是空的时候不要去"补数据"，也不要把空列表当成接口坏了
  （`GET /music/list` 此时返回 `code 200` + 空数组，不是 404）
- 其余与友链 / 项目 / 收藏同形：`""` 归一化成 `NULL`、增 / 改 / 删进审计
  （`CREATE_MUSIC` / `UPDATE_MUSIC` / `DELETE_MUSIC`，对象类型 `MUSIC`）、
  前台列表走 Redis 缓存并共用内容缓存版本号

**站点设置**
- `GET /setting`（公开，走 Redis 缓存）+ `PUT /admin/setting`（仅管理员，**单条更新**，没有新建 / 删除）
- 七项：站点名 / 首页公告 / 评论总开关 / 页脚版权 / ICP 备案号 / **公安网安备案号** / 每页文章条数。
  在这之前它们全都写死在前端代码里 —— 改一个站点名要改五处 `.vue` 再重新构建部署，
  而备案号这种东西又必须能随时改（换域名、换主体）
- ⚠️ **两个备案号是两列、两套备案体系**（`icp_number` / `police_number`，V13 加的后者）：
  主管机关不同（工信部 vs 公安机关）、编号形态不同，**链接规则也不同** ——
  ICP 统一链到 `beian.miit.gov.cn`，公安那一行要链到"公安部全国互联网安全管理服务平台"
  （`beian.mps.gov.cn`）的备案查询页并挂它的图标。所以两串号各自独立存：
  挤进一列（`蜀ICP备…；川公网安备…`）之后，前端只能解析字符串猜"哪一段属于哪个平台"，
  多一个空格就会把合规链接拼错 —— 页脚备案链接拼错的后果等同于没备案。
  字段里**只存号本身、不存链接**（链接与图标是平台规定的固定物，由前端按规则拼）
- ⚠️ **它与 `about` 是同一类（全表只有一行）但刻意分成两张表**：`about` 是
  "站长是谁"这类对外展示内容（几个月不动），站点设置是"站点怎么运行"的配置
  （上线当天就可能改好几轮）。分表的收益是接口形状与缓存范围都清楚 ——
  改一个"每页条数"不该让关于页的缓存重建
- ⚠️ **它驱动的是"整站的外壳"**：页眉的站点名、页脚的版权与备案号、首页的公告与
  每页条数、文章页的评论开关 —— 也就是**每一次页面渲染都要读它**（含错误页与 404 页）。
  所以它必须能被**匿名**读到：页脚里的备案号是合规要求，不能让未登录访客看不到
- ⚠️ **迁移的种子值等于前端原本写死的那些值**（站点名 `亿轨星途`、评论开着、每页 **12** 条，
  公告 / 两个备案号 / 版权留空）：目的是让这个功能上线**本身不改变站点外观** ——
  部署完打开首页应当是"什么都没变，只是以后可以在后台改"。
  那个 **12** 是前端首页此刻在用的每页条数（3 列瀑布流 4 行），
  **不是** `ArticleQuery.DEFAULT_PAGE_SIZE`（10：那是"接口没收到 size 时用什么"）。
  备案号刻意**不预填**：它属于站点的备案材料，该由站长在后台自己填。
  V13 加公安网安备案号那一列时**同样没有补任何种子值**（没有 `UPDATE`）：
  真实备案号绑定的是站点主体，编不出来也不该进公开仓库的迁移脚本
- ⚠️ **`pageSize` 的上限直接引用 `ArticleQuery.MAX_PAGE_SIZE` 这个常量**（不是另写一个 50）：
  文章接口会把超出的 `size` **静默夹到 50**，所以设置里若允许更大的值，
  站长会得到一个"保存成功但首页还是只列 50 篇"的开关，而且不会有任何报错
- `comment_enabled` 库里存 `0/1`（便于用 SQL 排查：`WHERE comment_enabled = 0`）、
  接口上是布尔（前端那个控件就是 `el-switch`）—— 转换只在 Service 一处，两个方向都有断言
- ⚠️ **这不是一个"只藏前端表单"的开关**：关掉之后 `POST /comment` 会由**后端**拒绝
  （`ResultCode.COMMENT_DISABLED`，code 403）。本项目对可见性 / 可写性规则的一贯做法是
  "写在 Service 里，不是由前端决定"（对照 `/music/list` 只返回 `status = 1`、
  前台评论列表把状态写死成"已通过"）。用例见 `SiteSettingTest` ⑰ ——
  它除了断言返回码，还断言**库里没有多出记录**（假开关光看返回码看不出来）

**基础设施**
- 统一返回 `{code, message, data}`
- 全局异常处理（业务异常 / 参数校验 / 认证失败 / 账号禁用 / 权限不足 / 兜底）
- 分页与排序参数安全处理（见「接口安全约定」）
- 自动生成 OpenAPI 接口文档
- **接口限流（两层）**：应用层用 Resilience4j 注解式限流（登录 5 次/分钟、
  前台列表 300 次/分钟、发表评论 20 次/分钟），Nginx 那层按客户端 IP 限流；
  被限流返回 **429** —— 两层的分工与数值理由见「部署」章节
- **操作审计**：文章的增 / 改 / 发布下架 / 删除，用户的启用禁用 / 改角色 /
  重置密码 / 删除，标签的增 / 改 / 删，评论的审核 / 删除，分类的增 / 改 / 删，
  友链的增 / 改 / 删，项目、收藏的增 / 改 / 删，关于页的保存，音乐的增 / 改 / 删，
  站点设置的保存
  —— 共 30 类管理动作全部留痕（操作人、来源 IP、traceId、改动内容快照）；
  **业务提交之后才异步落库**，回滚掉的操作不会被记下来 —— 见「操作审计」
- **Flyway 数据库版本化迁移**：空库启动自动建表，表结构只有一份定义
- **Testcontainers 容器化集成测试**：测试自带数据库与 Redis，clone 下来就能验证
- **GitHub Actions 持续集成**：每次 push / PR 自动构建、跑测试、出覆盖率报告
- **文件上传（图片 + 音频 + 附件三套规则）**：扩展名白名单 + 大小限制 + UUID 重命名 + 按日期分目录；
  图片走 `type=image`（默认，**10MB**，存 `uploads/cover/`），音频走 `type=audio`
  （mp3，20MB，存 `uploads/music/`），附件走 `type=attachment`
  （16 种文档与媒体格式，**100MB**，存 `uploads/attachment/`）—— 三套规则**互不放宽**，
  各有用例钉住；上传目录另有 **5GB 总容量护栏**（超过就拒绝并提示清理）；
  文件存服务器本地磁盘，并用**具名卷**持久化。
  ⚠️ 附件响应强制 `Content-Disposition: attachment` + `nosniff`，白名单里也没有
  html / svg / xml / js —— 两道防线挡的是"在自家域名下执行脚本"（见「文件上传」章节）
- 集成测试 **41 个类 463 个用例**，行覆盖率 **91.1%**

### 🚧 规划中

- **评论的楼中楼回复**（现在是一条条平铺的评论，没有父子关系）——
  先把"能评论 + 能审核"这条主流程做扎实；回复要牵动前端渲染与分页语义，
  等真有需求时加一列 `parent_id` 即可
- **评论的敏感词过滤**（现在靠"默认待审核 + 人工看"）——
  要自动化应当用成熟的敏感词库（DFA 前缀树不自己写），见路线图 C5
- **审计记录的查询接口**：审计目前只负责"记下来"，还没有后台查询页面
- 全文搜索（目前是 `LIKE '%关键词%'`，用不上索引；要快要上 ES）

> 详细的开发计划、技术选型取舍与分阶段提交清单见仓库根目录 `TECH_ROADMAP.md`。

## 目录结构

```
com.yigalaxy.yiguixingtu
├── YiguixingtuApplication          # 启动类
├── common
│   ├── Result                      # 统一返回包装 {code, message, data}
│   ├── ResultCode                  # 结果码枚举
│   ├── cache/ContentCacheVersion    # 站点内容模块（友链/项目/收藏/关于/音乐）共用的缓存版本号
│   ├── validation/UrlPatterns       # URL 入参白名单正则（http(s) 外链 / 站内路径）
│   ├── metrics/BusinessMetrics     # 自定义业务指标（浏览量落库 / 登录 / token 拉黑）
│   ├── idempotency/IdempotencyService   # 接口幂等：SET NX 占位 + 结果缓存（见「接口幂等」章节）
│   └── exception
│       ├── BusinessException       # 自定义业务异常
│       └── GlobalExceptionHandler  # 全局异常处理器（6 类异常）
├── config
│   ├── RedisConfig                 # @EnableCaching + Jackson（注册 JavaTimeModule，否则 LocalDateTime 序列化报错）
│   ├── MybatisPlusConfig           # 分页插件（全局上限 100 兜底）
│   ├── SecurityConfig              # Spring Security 过滤链 + JWT + CORS + 401/403 JSON + 安全响应头
│   ├── WebMvcConfig                # /uploads/** 映射到本地存储目录 + 注册附件下载头过滤器
│   ├── UploadResponseHeaderFilter  # /uploads/** 的响应头：附件强制下载（防 XSS/钓鱼）、图片音频仍内联
│   ├── SchedulingConfig            # @EnableScheduling（浏览量定时落库要用）
│   ├── TraceResponseHeaderFilter   # 把当前请求的 traceId 写进响应头 X-Trace-Id
│   ├── AsyncConfig                 # @EnableAsync + 审计落库线程池的拒绝策略（CallerRunsPolicy）
│   └── AdminBootstrapRunner        # 空库启动时引导创建第一个管理员
├── auth
│   ├── controller/AuthController   # 登录 / 注册 / 登出 / 当前用户
│   ├── service/UserDetailsServiceImpl
│   ├── util/JwtUtil                # JWT 签发与解析（含 jti，用于登出作废）
│   ├── filter/JwtAuthenticationFilter   # 验签 + 查黑名单 + 查认证缓存
│   ├── cache/UserAuthCache         # 用户认证信息 Redis 缓存 + token 即时撤销
│   ├── cache/TokenBlacklist        # 按 jti 的 token 黑名单（登出用）
│   ├── metrics/AuthenticationMetricsListener  # 监听认证事件统计登录成败
│   ├── dto/                        # LoginRequest / RegisterRequest / LoginVO
│   ├── JwtProperties               # JWT 配置绑定
│   └── LoginUser                   # 认证用户包装（含 status/role）
├── audit
│   ├── OperationLog                # 审计记录实体（对应 operation_log 表，刻意没有逻辑删除）
│   ├── OperationAction             # 操作类型枚举（CREATE_ARTICLE / DELETE_USER …）
│   ├── AuditTarget                 # 操作对象类型（ARTICLE / USER / TAG / COMMENT / CATEGORY / LINK / PROJECT / FAVORITE / ABOUT / MUSIC）
│   ├── OperationLogEvent           # 事件对象（带上用户 / IP / traceId 的快照）
│   ├── OperationLogRecorder        # 业务代码只调它一行：抄上下文 + 发事件
│   ├── OperationLogListener        # @Async + AFTER_COMMIT：业务提交后才落库
│   └── mapper/OperationLogMapper
├── user
│   ├── controller/UserController   # 用户管理（类级 @PreAuthorize ADMIN）
│   ├── service/UserService(+Impl)  # 分页 / 状态 / 角色 / 重置密码 / 逻辑删除
│   ├── entity/User                 # @TableLogic 逻辑删除
│   ├── mapper/UserMapper
│   └── dto/                        # UserQuery / UserVO / ResetPasswordRequest
├── article
│   ├── controller/ArticleController       # 前台：列表 / 详情（公开）
│   ├── controller/AdminArticleController  # 后台：增删改查（ADMIN）
│   ├── service/ArticleService(+Impl)      # ⚠️ 附件的整体替换 + 删文章时的级联清理（判断"文件还有没有别人在用"）
│   ├── entity/Article
│   ├── entity/ArticleAttachment           # 文章附件（⚠️ 没有 @TableLogic：整表物理删除，见 V14）
│   ├── mapper/ArticleMapper
│   ├── mapper/ArticleAttachmentMapper     # 含一条手写 COUNT（"这个文件还有没有别的附件在用"）
│   ├── cache/ArticleViewCounter    # 浏览量 Redis 计数器（详情页只 INCR，不写库）
│   ├── cache/PublishedArticleCache # 详情页那份库数据的可缓存读取（防击穿；详情带 tags + attachments）
│   ├── cache/ArticleCacheVersion   # 缓存版本号（列表与详情各一个，写操作一起推进）
│   ├── task/ViewCountSyncTask      # 定时把 Redis 增量批量落库
│   └── dto/                        # ArticleForm / ArticleQuery / ArticleVO / ArticleStatsVO / ArticleArchiveVO / ArticleRssVO / ArticleAttachmentVO / ArticleAttachmentForm
├── upload
│   ├── controller/UploadController # POST /upload（ADMIN；返回 {url, name, size}）
│   ├── service/UploadService       # 三种类型各自的白名单与上限 + 总容量护栏 + UUID 重命名 + 按日期分目录
│   ├── UploadType                  # 上传种类（image / audio / attachment，规则互不放宽）
│   ├── FileStorage                 # 存储接口（存 / 删 / 统计占用；将来换对象存储时上传逻辑不用改）
│   ├── LocalFileStorage            # 本地磁盘（唯一实现，见「文件上传」章节）
│   ├── UploadedFileCleaner         # URL ↔ 对象 key、从正文里找出引用的文件、按 key 删除
│   ├── UploadResult                # 上传返回体 {url, name, size}
│   └── UploadProperties            # 上传配置绑定（含附件那一套与 maxTotalSize）
├── category
│   ├── CategoryController                 # 分类列表（公开）
│   ├── AdminCategoryController            # 后台：增删改（删时会检查是否还有文章在用）
│   ├── service/CategoryService(+Impl)     # ⚠️ 删除时把名字改写成 原名#deleted#id
│   ├── entity/Category
│   ├── mapper/CategoryMapper              # 含一条手写 COUNT（要自己写 deleted = 0）
│   └── dto/                               # CategoryVO / CategoryForm
├── tag
│   ├── TagController                      # 前台：标签列表（公开，带文章数）
│   ├── AdminTagController                 # 后台：标签增删改查（ADMIN）
│   ├── service/TagService(+Impl)          # 含"给文章打标签"与缓存失效
│   ├── entity/Tag                         # ⚠️ 没有 @TableLogic：标签是物理删除
│   ├── mapper/TagMapper                   # 含两个手写聚合查询（文章数 / 批量查标签）
│   ├── mapper/ArticleTagMapper            # article_tag 关联表（纯关联，没有实体）
│   └── dto/                               # TagForm / TagVO
├── comment
│   ├── CommentController                # 前台：看评论 + 发评论（**游客可用**，发表有限流）
│   ├── AdminCommentController           # 后台：审核 / 删除（ADMIN）
│   ├── service/CommentService(+Impl)    # 状态写死、HTML 转义、来源 IP
│   ├── entity/Comment                   # 三态：0待审核 1已通过 2已拒绝
│   ├── mapper/CommentMapper
│   └── dto/                             # CommentForm / CommentQuery / CommentVO / AdminCommentVO
├── link
│   ├── LinkController                   # 前台：友链列表（公开，只含显示中的）
│   ├── AdminLinkController              # 后台：友链增删改查（ADMIN）
│   ├── service/FriendLinkService(+Impl) # 缓存失效（内容版本号）+ 审计 + 空串归一化成 NULL
│   ├── entity/FriendLink                # @TableLogic 逻辑删除；status 0隐藏 1显示
│   ├── mapper/FriendLinkMapper          # 没有任何手写 SQL（对比 CategoryMapper 要自己写 deleted = 0）
│   └── dto/                             # FriendLinkForm / FriendLinkVO
└── project
    ├── ProjectController                # 前台：项目列表（公开，只含显示中的）
    ├── AdminProjectController           # 后台：项目增删改查（ADMIN）
    ├── service/ProjectService(+Impl)    # ⚠️ 特有规则：url 与 repo 至少填一个（跨字段，只能写在 Service）
    ├── entity/Project
    ├── mapper/ProjectMapper
    └── dto/                             # ProjectForm / ProjectVO
└── favorite
    ├── FavoriteController               # 前台：收藏列表（公开，只含显示中的；分组交给前端）
    ├── AdminFavoriteController          # 后台：收藏增删改查（ADMIN）
    ├── service/FavoriteService(+Impl)
    ├── entity/Favorite                  # ⚠️ category 是自由文本分组名，不是 category 表的 id
    ├── mapper/FavoriteMapper
    └── dto/                             # FavoriteForm / FavoriteVO
└── about
    ├── AboutController                  # 前台：关于页信息（公开，单条对象、不是数组）
    ├── AdminAboutController             # 后台：只有 PUT /admin/about（没有新建/删除）
    ├── service/AboutService(+Impl)      # 单条记录：缺行时读返回空壳、保存时自愈写回
    ├── entity/About                     # 全站唯一没有 @TableLogic / create_time 的业务表
    ├── mapper/AboutMapper
    └── dto/                             # AboutForm / AboutVO
└── music
    ├── MusicController                  # 前台：音乐列表（公开，只含显示中的；列表为空返回空数组）
    ├── AdminMusicController             # 后台：音乐增删改查（ADMIN）
    ├── service/MusicService(+Impl)      # ⚠️ 删歌会清音频文件，但先查三处引用（别的曲目 / 文章正文封面 / 文章附件）；外链不碰
    ├── entity/Music                     # lyrics 是 LRC 原文（TEXT），不是文件路径
    ├── mapper/MusicMapper                # 含一条手写 COUNT（删歌时问"还有没有别的曲目用同一个文件"，要自己写 deleted = 0）
    └── dto/                             # MusicForm / MusicVO

src/main/resources
├── application.properties
├── logback-spring.xml            # 日志：按天滚动 + 保留 15 天 + traceId 槽位
└── db/migration
    ├── V1__init.sql                     # user / category / article 建表
    ├── V2__add_article_sort_index.sql   # 列表排序的复合索引（附实测依据）
    ├── V3__add_count_covering_index.sql # 分页 COUNT 的覆盖索引（压测压出来的）
    ├── V4__create_operation_log.sql     # 操作审计表（刻意没有逻辑删除，见脚本内说明）
    ├── V5__create_tag_tables.sql        # tag + article_tag（标签刻意用物理删除，见脚本内说明）
    ├── V6__create_comment_table.sql     # comment（回到逻辑删除：它没有唯一索引）
    ├── V7__create_friend_link_table.sql # friend_link（友链；同样是逻辑删除）
    ├── V8__create_project_table.sql     # project（项目；url 与 repo 至少填一个，见脚本内说明）
    ├── V9__create_favorite_table.sql    # favorite（收藏；category 是自由文本分组名，见脚本内说明）
    ├── V10__create_about_table.sql      # about（关于页；只有一行，插入语句就在脚本里）
    ├── V11__create_music_table.sql      # music（音乐；**刻意不插种子数据**，见脚本内说明）
    ├── V12__create_site_setting_table.sql # site_setting（站点设置；同样只有一行，种子值等于前端原本写死的值）
    ├── V13__add_police_number_to_site_setting.sql # site_setting 加公安网安备案号列（**刻意不写种子值**，见脚本内说明）
    └── V14__create_article_attachment_table.sql # article_attachment（文章附件；**物理删除、不做逻辑删除**，为什么不在上传时插行见脚本内说明）

src/test/java/com/yigalaxy/yiguixingtu
├── AbstractIntegrationTest         # 集成测试基类：起 MySQL/Redis 容器 + 注入连接信息
├── AuthLoginTest                   # 登录链路
├── UserRegisterTest                # 注册
├── JwtSecurityTest                 # JWT 与 Security 过滤链
├── GlobalExceptionHandlerTest      # 全局异常处理（含 404 / 405 的真实状态码）
├── UserAdminTest                   # 用户管理（含 token 即时失效）
├── ArticleAdminTest                # 后台文章管理
├── ArticlePublicTest               # 前台公开接口（草稿隔离）
├── ArticleCacheTest                # 列表缓存：命中/失效/TTL/防穿透
├── RateLimitTest                   # 接口限流（配额用完 -> 429）
├── ArticleDetailCacheTest          # 详情缓存与防击穿
├── ArticleStatsTest                # 站点统计接口（只算已发布）
├── ArticleViewCountTest            # 浏览量：Redis 计数 + 定时批量落库
├── ArticleIdempotencyTest          # 接口幂等（Idempotency-Key）
├── ArticleIndexTest                # 索引契约：迁移已执行 + 列顺序 + 对真实查询可用（含分页 COUNT 的覆盖索引）
├── ArticleArchiveTest              # 归档接口：只含已发布 + 按年月分组 + 月份与月内都倒序 + 走缓存
├── ArticleRssTest                  # RSS 数据：带正文 + 上限 20 篇（挤掉最早那篇）+ 走缓存
├── TagTest                         # 标签：前台标签云（带已发布文章数）+ 后台增删改 + 物理删除
├── ArticleTagTest                  # 打标签与按标签筛选：覆盖式语义 + 校验先于写入 + 草稿不泄漏
├── CommentTest                     # 评论：游客可发但默认待审核 + XSS 转义 + 公开响应不含邮箱/IP + 限流
├── CategoryAdminTest               # 分类增删改：有文章时拒绝删除 + 删掉后同名可重建 + 改名前台立刻生效
├── FriendLinkTest                  # 友链：前台只含显示中的 + 排序稳定 + 后台增删改（逻辑删除）+ 缓存命中与失效
├── ProjectTest                     # 项目：同上 + ⚠️ url 与 repo 至少填一个（两个都空被拒、只填一个合法、编辑时清空也拒且不动旧值）
├── FavoriteTest                    # 收藏：同上 + ⚠️ category 是自由文本分组（斜杠合法、可重复、空串归一化成 NULL、只卡长度）
├── AboutTest                       # 关于页：单条记录的不变量（永远一行 / 缺行时读给空壳且保存能写回 / POST 是真 405）+ 校验边界 + 权限 + 缓存
├── MusicTest                       # 音乐：排序两段稳定（sort 同则按 id）/ 音频地址白名单 / 长歌词原样存取 / 空列表返回空数组 / 增删改与权限 / 缓存命中与失效
├── MusicFileCleanupTest            # 删歌时的音频文件清理：外链不碰 / 独占的删掉 / ★ 共用的留在最后一个引用者离开 / ★ 文章引用时两侧都不误删（每条都带反向断言）
├── UploadAdminTest                 # 上传（图片 + 音频两套规则、大小按 type 分流、权限）
├── ArticleAttachmentTest           # 文章附件：白名单（拒 html/svg/js）、单文件与总容量两道闸、整体替换、详情带 attachments、级联删文件、★ 共用的图不误删、附件强制下载
├── LogoutTokenTest                 # 登出后旧 token 立即失效（jti 黑名单）
├── SecurityHeadersTest             # 四个安全响应头
├── MetricsEndpointTest             # 指标端点与自定义业务指标
├── TracingTest                     # 链路追踪：traceId 进日志 + 进响应头
├── OperationLogTest                # 操作审计：30 类写操作都留痕 / IP 取真实客户端 / 回滚与失败不记账
├── PaginationLimitTest             # 分页全局上限（从 Mapper 层验证插件兜底）
├── ProfileDevConfigTest            # dev 环境行为：Swagger 开着 / SQL 日志 / 跨域白名单
├── ProfileProdConfigTest           # prod 环境行为：Swagger 关闭 / 凭据必须来自环境变量
├── AdminBootstrapInitTest          # 管理员初始化引导（空库也能进后台）
├── DeploymentMemoryBudgetTest      # 部署配置契约：容器内存上限 / JVM 参数的位置 / MySQL 与 Redis 的内存参数（读 compose 与 Dockerfile，不起 Spring 上下文）
├── ActuatorExposureContractTest    # 管理端点的暴露面契约：Nginx 必须挡掉 /api/actuator/ + 数据库端口不发布 + 应用端口只绑回环（读 README 与 compose）
├── SiteSettingTest                 # 站点设置：单条记录的不变量 + 七个字段逐个能改能清空 + 每页条数上限跟文章接口走 + 评论开关的 0/1↔布尔转换 + V13 公安网安备案号列
└── YiguixingtuApplicationTests     # 冒烟

docs
├── demo-data
│   └── demo_users.sql              # 本地演示数据（100 个用户），切勿在生产执行
└── perf
    └── explain-article-list.sql    # 列表索引优化的可复现实测脚本（造 20 万行再量）

Dockerfile                          # 后端镜像（多阶段构建，非 root 运行）
.dockerignore                       # 构建上下文排除清单（含个人材料，见文件内说明）
docker-compose.yaml                 # 本地开发：只有 mysql + redis
docker-compose.prod.yaml            # 生产：backend + frontend + mysql + redis 四容器（详见「部署」章节）
```

## 快速开始

### 1. 环境要求

- JDK 17+
- Maven 3.6+（也可直接用仓库自带的 `mvnw.cmd` / `mvnw`，无需本地装 Maven）
- **Docker**：两种用途 ——
  - **跑测试**：Testcontainers 会自己拉起 MySQL 与 Redis 容器，跑完自动销毁。
    **只要能起容器就行，不需要事先启动任何服务。**
  - **本地调试**：起一套常驻的 MySQL / Redis（见下一步）

### 2. 启动本地依赖（仅本地调试需要，跑测试不需要）

```bash
docker compose up -d
```

启动后：

- MySQL：`localhost:3310`（库 `yiguixingtu`，用户 `root/root`）
- Redis：`localhost:6380`

> 💡 **这一步和测试无关。** `mvn test` 用的是 Testcontainers 临时拉起的容器，
> 就算这一步没执行、甚至把 `docker compose down` 掉，测试照样能跑。
> 这里起来的两套服务只是给「本地起后端 + 前端联调」用的。

### 3. 初始化表结构（Flyway 自动完成）

**不需要手工建表，也不需要执行任何 SQL 脚本。**

应用启动时由 **Flyway** 自动执行 `src/main/resources/db/migration/` 下的迁移脚本，
建好 `user` / `category` / `article` 三张表，并把执行记录写进 `flyway_schema_history` 表。
**空库直接启动就能用。**

表结构的定义**只有一份**（就是那些 SQL 文件），开发库、测试库、生产库共用它——
这也是引入 Flyway 的原因：以前 DDL 散在文档和聊天记录里，换台机器就要照抄一遍，
抄漏一个索引也没人会发现。

```
src/main/resources/db/migration/
├── V1__init.sql                     # user / category / article 三张表
└── V2__add_article_sort_index.sql   # 列表排序的复合索引（附实测依据，见「性能」章节）
```

命名规则是 `V{版本}__{描述}.sql`（**两个下划线**）。Flyway 靠文件名排序，
执行过的版本记在 `flyway_schema_history` 里，不会重复执行。

> ⚠️ **已经执行过的迁移脚本不要再改。** Flyway 会比对校验和，
> 改动已应用的 `V1__init.sql` 会让下次启动直接报错。
> 改表结构的正确做法是**新增** `V2__xxx.sql`，已上线的迁移脚本是历史。

加索引这类操作还额外写了 `ALGORITHM=INPLACE, LOCK=NONE`。MySQL 8 加二级索引本来就支持
在线执行、不阻塞读写，但那是"默认行为"而不是"保证行为"——条件不满足时它会**悄悄退回**
到锁表的 COPY 方式。显式写出来之后，做不到在线就**直接报错**，宁可迁移失败被人发现，
也不要在生产库上偷偷把写操作阻塞几分钟。

**关于 `baseline-on-migrate`（一个容易被误解的配置）**

本地的库是先手工建好表、Flyway 才接进来的，属于"已存在的非空库"。
不做处理的话，Flyway 会去执行 `V1__init.sql` 并因为"表已存在"直接失败。
所以配置了 `baseline-on-migrate=true`，两种库状态的行为是：

| 库的状态 | Flyway 的行为 |
|---|---|
| **空库**（新部署、Testcontainers 容器） | 正常执行 `V1__init.sql`：建表 + 写历史记录 |
| **已有表的库**（本地开发库） | 打一个"基线 = 版本 1"的标记，**不执行 V1**，当作它已经应用过 |

两种情形共用同一份配置，不需要分环境改。后续的 `V2` 在两种库上都会正常执行
（实测：本地已有的开发库上执行耗时 60ms，无锁表）。

**两个建表细节，说明一下为什么这么设计：**

1. **`user` 是 MySQL 保留字**，建表和查询时都要用反引号包住表名（`` `user` ``）。
2. **`idx_status_create(status, create_time)` 是为首页列表专门建的复合索引**。
   前台列表的查询条件是 `status = 1 ORDER BY create_time DESC`，
   字段顺序不能反——`status` 在前才能先用等值条件把范围缩小，
   再用 `create_time` 有序取出，避免 `ORDER BY` 触发额外排序。

> **为什么没有给 `title` 建索引？** 搜索用的是 `LIKE '%关键词%'`，前置通配符会让 B+ 树索引完全失效，
> 真要解决得靠全文索引或 Elasticsearch。加一个用不上的索引只会拖慢写入，
> 还容易在 `EXPLAIN` 里造成"已经优化过"的错觉。

#### 可选：导入演示数据

后台「用户管理」页面只有一两条数据是看不出分页、筛选、排序效果的，
所以提供了一份演示数据脚本：`docs/demo-data/demo_users.sql`

它会插入 **100 个演示用户**（用户名 `demo_001` ~ `demo_100`，中文昵称，全部 `GUEST` 角色，
注册时间按天散开约 100 天，每 7 个里有 1 个处于禁用状态）。

导入方式（Windows）：

```powershell
docker cp docs\demo-data\demo_users.sql yiguixingtu-mysql:/tmp/demo_users.sql
docker exec yiguixingtu-mysql sh -c "mysql -uroot -proot --default-character-set=utf8mb4 yiguixingtu < /tmp/demo_users.sql"
```

Linux / macOS：

```bash
docker exec -i yiguixingtu-mysql mysql -uroot -proot --default-character-set=utf8mb4 yiguixingtu < docs/demo-data/demo_users.sql
```

导入后 `GET /user/page?page=1&size=100` 应返回 100 条记录、`total` 为 101（含管理员）。

**⚠️ 三条注意事项：**

1. **只能用于本地开发与演示，切勿在生产环境执行。** 这些账号共用密码 `demo@123456`，
   一个已知密码的账号池上线就是漏洞。
2. **这个文件不能放进 `src/main/resources/db/migration/`。** 那个目录是给 Flyway 用的，
   一旦放进去，生产环境启动时会**自动执行**。它的位置是 `docs/demo-data/`，这是刻意的。
3. **一键清理**：
   ```sql
   DELETE FROM `user` WHERE username LIKE 'demo%';
   ```

> 密码哈希不是手工编的，是通过真实的 `POST /auth/register` 接口注册一个账号后取回来的，
> 所以它能真的通过 BCrypt 校验，可以用来登录（`demo_001` / `demo@123456`）。
>
> 顺带说明：`demo_001` 是用注册接口真实创建的，脚本里从 `demo_002` 开始批量插入，
> 所以文件里看不到 `demo_001` 这一行——一共 100 个（1 + 99）。

#### 怎么得到第一个管理员账号？

**背景（一个"鸡生蛋"问题）**：
- 注册接口**只会创建 GUEST**（`UserServiceImpl` 里写死 `user.setRole("GUEST")`）
- 而把用户提升为 ADMIN 的接口 `PUT /user/{id}/role`，**本身要求调用者已经是 ADMIN**

所以一个全新的空库没有任何管理员，也就没有任何接口能造出一个——后台进不去。

**✅ 正式做法：用启动引导（生产环境推荐）**

应用启动时会检查"库里有没有可用的管理员"，没有就按配置建一个：

```properties
app.bootstrap-admin.username=${BOOTSTRAP_ADMIN_USERNAME:}
app.bootstrap-admin.password=${BOOTSTRAP_ADMIN_PASSWORD:}
app.bootstrap-admin.nickname=${BOOTSTRAP_ADMIN_NICKNAME:站长}
```

也就是说，部署时只要在环境变量里给上这两个值，容器第一次起来就自动有了管理员：

```bash
BOOTSTRAP_ADMIN_USERNAME=your_admin
BOOTSTRAP_ADMIN_PASSWORD=一个强密码
```

**它是幂等的，各种情形都考虑到了：**

| 库里已有可用管理员 | 配置了引导账号 | 结果 |
|:---:|:---:|---|
| 有 | —— | **什么都不做**（不是每次重启都建账号） |
| 没有 | 否 | 什么都不做，但打一条 **WARN** 提示"后台将无法登录" |
| 没有 | 是，且用户名不存在 | 新建一个 ADMIN（密码存 **BCrypt 哈希**） |
| 没有 | 是，且该用户名已存在但是 GUEST | **提升为 ADMIN，不改它的密码** |
| 没有 | 是，且该用户名已是 ADMIN | 视同"已有管理员"，跳过 |

> **两个刻意的设计：**
> 1. **提升已有账号时不改密码**。改密码是破坏性动作——万一你把 `BOOTSTRAP_ADMIN_USERNAME`
>    填成了某个正在使用的账号，重置密码会把那个人直接挡在门外。所以只提升角色、不动凭据。
> 2. **引导失败不会导致应用起不来**。它只是"方便部署"的功能，不该因为一个配置写错
>    就把整个站点弄挂；失败会打错误日志，然后用下面的 SQL 兜底。
>
> ⚠️ 另外注意："禁用的 ADMIN"**不算**可用管理员（他登录会被拒），所以这种情况下仍会触发引导。

**兜底做法：用 SQL 手工提升（连不上引导配置时）**

```sql
-- 1) 先通过 POST /auth/register 正常注册一个账号（下面假设它叫 myadmin）
-- 2) 把它提升为管理员
UPDATE `user` SET role = 'ADMIN' WHERE username = 'myadmin';
-- 3) 查出它的 id，下一步要用
SELECT id, username, role FROM `user` WHERE username = 'myadmin';
```

```bash
# 4) 清掉这个账号的认证缓存，否则角色变更不会立即生效
docker exec -it yiguixingtu-redis redis-cli DEL auth:user:<上一步查到的 id>
```

**为什么第 4 步是必须的？** 用户的角色/状态被缓存进 Redis（key 是 `auth:user:{id}`，TTL 30 分钟），
用来支持"禁用/改角色后旧 token 立即失效"。直接改库等于绕过了那套缓存清理逻辑，
缓存里还是旧角色，最长要等 30 分钟才自然过期。
走 `PUT /user/{id}/role` 接口改角色就不会有这个坑——**这也是"别绕过 API 直接改库"的一个具体例子。**
（上面那条启动引导【不需要】手动清缓存：它是在应用启动、还没人登录时执行的，缓存里不可能有旧数据。）

### 4. 配置

#### 多环境配置怎么分工

配置按**「公共 + 各环境」**拆成三份，原则是**打包一次、处处运行**：

| 文件 | 放什么 | 激活方式 |
|------|--------|---------|
| `application.properties` | **所有环境都一样**的东西：应用名、MyBatis-Plus、JWT、Flyway、跨域默认值 | 总是加载 |
| `application-dev.properties` | 本地数据库/Redis 地址、**打印 SQL**、Swagger 开着 | 默认（`spring.profiles.active=dev`） |
| `application-prod.properties` | 全部凭据走环境变量、**关 SQL 日志**、**关 Swagger** | 环境变量 `SPRING_PROFILES_ACTIVE=prod` |

> **为什么线上不用改文件？** 操作系统环境变量的优先级高于配置文件，
> 所以同一个 jar，本地直接 `java -jar` 就是 dev，服务器上设一个
> `SPRING_PROFILES_ACTIVE=prod` 就是 prod —— 不打第二份包、也不用改代码。

#### dev（本地开发）

| 项 | 值 |
|----|-----|
| 服务端口 | `8082` |
| MySQL | `localhost:3310`，库 `yiguixingtu`，用户 `root/root` |
| Redis | `localhost:6380` |
| SQL 日志 | 开着（`StdOutImpl`）—— 本地查"为什么查不到数据"最有用 |
| Swagger | 开着 —— 本地要能点开 `/swagger-ui.html` 自己调接口 |

> 本地把 `root/root` 明文写在仓库里是**刻意的**：这个库由 compose 起、只监听本机、
> 也没有真实数据，换来的是别人 clone 下来 `docker compose up -d` 就能直接跑。

#### prod（生产）

**这个文件里所有凭据都【不给默认值】**，例如：

```properties
spring.datasource.url=${DB_URL}
spring.datasource.password=${DB_PASSWORD}
spring.data.redis.password=${REDIS_PASSWORD}
springdoc.api-docs.enabled=false
mybatis-plus.configuration.log-impl=org.apache.ibatis.logging.nologging.NoLoggingImpl
```

**为什么故意不给默认值**：一旦写了 `:root` 这样的兜底值，忘记配环境变量的后果就是
"服务带着一个公开的默认凭据悄悄跑起来"——这比启动失败危险得多。
现在忘配会直接启动报 `Could not resolve placeholder 'DB_PASSWORD'`，
**部署当场就发现**，而不是上线一周后被人拖库。

生产环境需要的环境变量：

| 变量 | 必填 | 说明 |
|------|:---:|------|
| `SPRING_PROFILES_ACTIVE` | ✅ | 固定填 `prod` |
| `JWT_SECRET` | ✅ | ≥32 字节，签发 token 用。**必须换掉仓库里的默认值** |
| `DB_URL` | ✅ | 例如 `jdbc:mysql://mysql:3306/yiguixingtu?useUnicode=true&characterEncoding=utf-8&useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true` |
| `DB_USERNAME` / `DB_PASSWORD` | ✅ | 数据库账号（建议单独建低权限账号，别用 root） |
| `REDIS_HOST` / `REDIS_PASSWORD` | ✅ | 内网也建议设密码 |
| `REDIS_PORT` | ⬜ | 默认 `6379` |
| `CORS_ALLOWED_ORIGINS` | ✅ | **前端域名**，例如 `https://你的域名`；多个用英文逗号分隔 |
| `SERVER_PORT` | ⬜ | 默认 `8082` |
| `UPLOAD_LOCAL_DIR` | ⬜ | 上传目录，compose 里已设成 `/app/uploads`（必须是挂载点路径） |
| `UPLOAD_BASE_URL` | ⬜ | 图片对外地址前缀；compose 里复用 `PUBLIC_API_BASE`，保证与浏览器看到的地址一致 |
| `UPLOAD_KEY_PREFIX` | ⬜ | 存储路径前缀，默认 `cover`（形如 `cover/2026/09/{uuid}.png`） |
| `LOG_DIR` | ⬜ | 应用日志目录，compose 里已设成 `/app/logs`（必须是挂载点路径，否则"保留 15 天"是空话） |

#### 跨域白名单（**上线必改这一项**）

```properties
app.cors.allowed-origins=${CORS_ALLOWED_ORIGINS:http://localhost:3000}
```

原来这一项**写死在 `SecurityConfig` 里**（`http://localhost:3000`），后果是：
前端换个端口就跨域失败，一部署换成域名必然跨域失败，而且改完还得重新打包。
现在它从配置读，本地不用管（默认值就是 3000），线上填自己的域名即可。

#### ⚠️ Swagger 在 prod 必须真的关掉（这里踩过一个坑）

`application-prod.properties` 里设了 `springdoc.api-docs.enabled=false`，
但**光设这一项是不够的**：实测发现属性确实是 `false`，可请求 `/v3/api-docs`
**依然返回 200** —— 文档照旧对外可见。原因是 `SecurityConfig` 里
`/v3/api-docs/**`、`/swagger-ui/**` 这些路径是**无条件放行**的。

对一个要上线的站点来说，Swagger 等于一份写好的接口说明书
（有哪些接口、参数叫什么、哪些要管理员权限），不该公开。
所以现在把"是否生成文档"和"是否放行文档路径"**绑在同一个开关上**：

```java
if (apiDocsEnabled) {
    auth.requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll();
}
```

于是 prod 下这些路径不再被放行，未登录访问 `/v3/api-docs` 得到 **401**。
两条路径都有用例盯着（`ProfileDevConfigTest` 断言 dev 下可访问、
`ProfileProdConfigTest` 断言 prod 下不可访问）。

#### 链路追踪（traceId）

```properties
management.tracing.sampling.probability=1.0
management.tracing.propagation.type=b3
```

一个 traceId 对应**一次请求**，这次请求产生的所有日志行都会带上它。
线上排查"用户说报错了但我对不上是哪次请求"就靠它：前端把响应头里的
`X-Trace-Id` 显示给用户，用户报给你，你在日志里搜这个号，这次请求的全貌就出来了。

依赖是 `spring-boot-micrometer-tracing-brave` + `micrometer-tracing-bridge-brave`
**两个都要** —— 这是 Boot 4 拆模块的老坑：只加前者，编译通过、启动正常、
`Tracer` Bean 也能注入，但 traceId 永远不出现，一点报错都没有。

> 📌 **一个"以为做好了、其实没有"的插曲**：一开始以为设 `propagation.type=b3`
> 之后响应头会自动带 `X-B3-TraceId`，实测没有。原因是 Boot 4 把 tracing 的
> `receiver / sender / default` 三个 observation handler 包进了
> `FirstMatchingCompositeObservationHandler`，入站请求永远先被 receiver 匹配，
> 负责写响应头的 sender 轮不到执行。所以给前端的 traceId 是用一个
> 十几行的 `TraceResponseHeaderFilter` 写的自定义头 `X-Trace-Id`
> （这在 servlet 过滤器里读 `tracer.currentSpan()` 是几十年来最标准的做法，
> 见该类的注释）。`propagation.type` 真正管的是"收到上游 B3 头时继续那条 trace"。

#### 异步执行线程池（`@Async` 用，目前只有操作审计）

```properties
spring.task.execution.pool.core-size=1
spring.task.execution.pool.max-size=4
spring.task.execution.pool.queue-capacity=200
spring.task.execution.pool.keep-alive=60s
spring.task.execution.pool.allow-core-thread-timeout=true
spring.task.execution.thread-name-prefix=audit-async-
spring.task.execution.shutdown.await-termination=true
spring.task.execution.shutdown.await-termination-period=10s
```

操作审计的落库是**异步**的（见「操作审计」），用的是 Spring 自带的
`ThreadPoolTaskExecutor`（配 `spring.task.execution.*` 即可，不需要自己 `new` 一个线程池）。

| 参数 | 值 | 为什么是这个值 |
|------|----|---------------|
| `queue-capacity` | 200（**有界**） | 队列无界（默认是 `Integer.MAX_VALUE`）时，数据库一慢任务就无限堆积，最终把内存吃光 —— 表现是"应用莫名 OOM"，而且看不出跟审计有关。有界队列的意义是**压力大时明确拒绝，而不是悄悄攒** |
| `core-size` | 1 | 审计是低频、低优先级的写入，一个线程足够；多开线程只会跟业务请求抢数据库连接 |
| `max-size` | 4 | 只在队列快满时临时扩容，属于"应急"而不是常态 |
| `keep-alive` + `allow-core-thread-timeout` | 60s + true | 低峰期不必一直占着线程 |
| `thread-name-prefix` | `audit-async-` | 日志里一眼能分辨"这行不在处理请求的线程上" |
| `await-termination` | true / 10s | 关停时把已排队的审计任务做完，不因为一次重启丢掉记录 |

⚠️ **拒绝策略配不了**：Boot 没有暴露这个配置项，所以在 `config/AsyncConfig`
里用 `ThreadPoolTaskExecutorCustomizer` 设成了 **`CallerRunsPolicy`**
（队列满时由提交任务的线程自己执行）。为什么必须是它：另外三种默认策略里，
`AbortPolicy` 会**抛异常**——那等于让"审计写不进去"把一个已经成功的用户请求搞成 500，
本末倒置；`DiscardPolicy` / `DiscardOldestPolicy` 会**静默丢记录**。
`CallerRunsPolicy` 唯一的代价是让请求多等一次插入，换的是"既不失败也不丢"。

#### 数据库连接池（HikariCP）

```properties
spring.datasource.hikari.maximum-pool-size=${DB_POOL_SIZE:10}
spring.datasource.hikari.minimum-idle=${DB_POOL_SIZE:10}
spring.datasource.hikari.connection-timeout=3000
spring.datasource.hikari.max-lifetime=1800000
```

| 参数 | 值 | 为什么是这个值 |
|------|----|---------------|
| `maximum-pool-size` | 10（可用 `DB_POOL_SIZE` 覆盖） | HikariCP 官方公式 `连接数 ≈ 核数 × 2 + 磁盘数`，2~4 核的 ECS 算下来是 5~9，取 10 偏宽松。本机 32 核实测 20 更好（见「性能」章节的压测表），所以做成可配置而不是写死 |
| `minimum-idle` | 与 maximum 相同 | 让连接一直是热的。若小于 maximum，空闲连接会被回收、来请求时再新建，而建一条 MySQL 连接要握手 + 认证（毫秒级）。博客流量是"一阵一阵"的，频繁建连比多留几个空闲连接代价大 |
| `connection-timeout` | 3000 ms | Hikari 默认 30 秒 —— 数据库出问题时请求要挂 30 秒才报错（用户早关页面了），这期间还一直占着 Tomcat 工作线程。实测 P99 才 135ms，3 秒足够覆盖正常排队，超过就是不正常，应当立刻失败 |
| `max-lifetime` | 1800000 ms（30 分钟） | MySQL 的 `wait_timeout` 默认 8 小时，超时会被服务端单方面关连接。池里的连接若活得比它久，就会拿到一条"其实已经死了"的连接，报出 `Communications link failure` 这种与真实原因无关的错。30 分钟 ≪ 8 小时；**将来调小 MySQL 的 `wait_timeout`，这个值必须跟着调小** |

#### 文件上传（封面图、音频与文章附件都存在服务器磁盘上）

```properties
# ---- 图片：POST /upload（不传 type，或 type=image）----
app.upload.local-dir=${UPLOAD_LOCAL_DIR:./uploads}
app.upload.base-url=${UPLOAD_BASE_URL:http://localhost:8082}
app.upload.allowed-extensions=jpg,jpeg,png,gif,webp
app.upload.key-prefix=${UPLOAD_KEY_PREFIX:cover}
app.upload.max-size=10MB
# ---- 音频：POST /upload?type=audio ----
app.upload.audio-allowed-extensions=mp3
app.upload.audio-max-size=20MB
app.upload.audio-key-prefix=${UPLOAD_AUDIO_KEY_PREFIX:music}
# ---- 附件：POST /upload?type=attachment（文章附件用它）----
app.upload.attachment-allowed-extensions=pdf,zip,7z,rar,doc,docx,xls,xlsx,ppt,pptx,txt,md,csv,json,mp3,mp4
app.upload.attachment-max-size=100MB
app.upload.attachment-key-prefix=${UPLOAD_ATTACHMENT_KEY_PREFIX:attachment}
# ---- 整个上传目录的总量护栏（单文件上限之外的第二道闸）----
app.upload.max-total-size=5GB
# ---- multipart 的框架层闸门：按【最大的那一类】设，业务层再按 type 细分 ----
spring.servlet.multipart.max-file-size=105MB
spring.servlet.multipart.max-request-size=110MB
```

**三种上传各自一套规则，互不放宽**（完整推导见 `upload/UploadType` 的类注释）：

| | 图片（默认 / `type=image`） | 音频（`type=audio`） | 附件（`type=attachment`） |
|---|---|---|---|
| 扩展名 | `jpg` `jpeg` `png` `gif` `webp` | `mp3` | `pdf` `zip` `7z` `rar` `doc` `docx` `xls` `xlsx` `ppt` `pptx` `txt` `md` `csv` `json` `mp3` `mp4`（共 16 项） |
| 单文件上限 | **10MB** | 20MB | **100MB** |
| 存储目录 | `uploads/cover/yyyy/MM/` | `uploads/music/yyyy/MM/` | `uploads/attachment/yyyy/MM/` |
| 访问行为 | 内联展示（前台卡片直接渲染） | 内联播放（`<audio>`） | **强制下载**（`Content-Disposition: attachment`） |
| 返回 | `{"url", "name", "size"}`（**三者结构完全一样**） | 同左 | 同左 |

> **图片上限 5MB → 10MB 的依据（2026-09 调整）**：原来那个 5MB 是"网页时代的压缩
> 封面图"量级，而现在封面图的来源大多是**手机直出照片（一张 3~8MB）**或
> **设计稿导出的 PNG（2~6MB）** —— 5MB 会开始频繁拒绝正常尺寸的图，而站长能收到的
> 只是一句"不能超过 5MB"，只能自己先找工具压一遍。10MB 覆盖这两类来源并留了余量，
> 同时仍然远小于附件那一套的 100MB：封面图是**要在列表里直接渲染**的资源，
> 不该是几 MB 的巨物。
>
> **附件 100MB 的依据**：论文 PDF / PPT / 数据包几十 MB，1 小时录音约 60MB，
> 几分钟的屏幕录制就在 50~100MB。再往上就该走网盘/对象存储，而不是博客附件。
>
> **音频 20MB 的依据（没有变）**：主流音乐平台高质量档约 320kbps（≈40KB/s），
> 一首 5 分钟的歌 = 320kbps × 300s ÷ 8 ≈ **12MB**，取 20MB 足以覆盖 320kbps 下
> 8 分钟以内的曲目。音频天然比图片大一个量级（它有时间维度），
> 所以三套上限分开写、各自有依据，而不是"统一调大到某个数"。
>
> **为什么另起几项配置，而不是把 `mp3`/`pdf` 加进图片白名单、把上限统一调大**：
> 那是几行就能改完的做法，但会**悄悄放宽图片的规则** —— 图片从此能传 100MB、
> 也能传 pdf，而且不会有任何报错。所以按 `type` 分流，并且**三个方向都有用例钉住**
> （`UploadAdminTest` ⑭ = 音频接口拒 png、⑮ = 默认接口拒 mp3、⑰ = 同一份 1.5KB
> 内容作为图片被拒、作为音频被接受；`ArticleAttachmentTest` ⑥ = 附件接口拒图片、
> 图片与音频接口都拒 pdf、未知 type 仍然报错）。
>
> **两层限额的分工**：`spring.servlet.multipart.max-file-size` 管"请求能不能进来"
> （超了根本走不到业务代码，报的是框架异常），`UploadService` 管"这一类允许多大"。
> 所以框架层按最大的那一类（附件 100MB，取 105MB 留余量）设，
> 图片的 10MB 与音频的 20MB 由业务层按 `type` 拦下。
>
> ⚠️ **反向代理那一层还有第三道体积闸门**：Nginx 的 `client_max_body_size`
> **默认只有 1MB**。不显式放开的话，三种上传都会在**边缘**被 413 拒掉，
> 而后端日志里一个字都不会有（请求根本没进来）—— 这属于"上传失败，
> 但服务端什么都没记"那类最难查的故障。站点配置里已写成 `110m`
> （与 `max-request-size` 对齐），见「部署」章节与上线核对清单第 23 条。

##### 附件白名单里为什么**绝不能**出现 html / svg / xml / js / css

附件由后端 `/uploads/**` 静态提供（见 `config/WebMvcConfig`），而这个前缀与前台**同源**。
于是：

- 一个 `.html` / `.svg` / `.xml` 附件被**就地打开**时，浏览器把它当**本站的页面**解析，
  里面的 `<script>` 会真的执行 —— 它读得到 localStorage 里的 token、能带着 cookie
  发请求、能改写这个页面上的一切。这就是**存储型 XSS**，前端做过的所有转义防护
  在这一步全部作废（攻击者的用法很省事：把文件传上去，再把它的 URL 发给别人）
- 一个 `.pdf`（或任何能被渲染的东西）可以被伪造成"您的登录已过期，请重新登录"的假页面，
  而**地址栏显示的是我们自己的域名** —— 这是钓鱼最有效的一种形态

所以做了**两道各自独立的防线**：

1. **白名单**（`app.upload.attachment-allowed-extensions`）：只放行 16 种确定安全的
   格式，`html` / `htm` / `svg` / `xml` / `js` / `mjs` / `css` **一个都不在里面**
   （黑名单永远列不全 —— `.jsp` / `.xhtml` / `.svgz` ……，白名单才是安全的默认值）
2. **强制下载**（`config/UploadResponseHeaderFilter`）：`/uploads/**` 的响应按
   **文件所在目录**判断 —— 附件目录里的一律带 `Content-Disposition: attachment`
   与 `X-Content-Type-Options: nosniff`，浏览器只下载、不就地打开；
   图片与音频（封面、曲目）**保持内联**，否则前台几十张卡片全会变成"下载按钮"

> **为什么"要不要下载"按目录判断，而不是按扩展名**：附件白名单里也有 `mp3` / `mp4`，
> 它们本身是"可以内联"的媒体格式。但一个放在附件目录里的录音是**要下载的资料**，
> 不是背景音乐。而**它放在哪个目录**是上传时由我们自己决定的，比扩展名更可靠。
> 扩展名只用来兜住第二种情况：不在附件目录、扩展名又不在"图片/音频"白名单里的
> 文件（比如有人手工放进目录的），一律按最保守的方式处理 —— 强制下载。

##### 上传目录的总容量护栏（5GB）

单文件 100MB **挡不住**"几十个附件把磁盘写满"：40 个各 100MB 的附件（每个都合法）
就能填满一台 40GB 的 ECS。而磁盘写满的后果比"某次上传失败"严重得多 ——
MySQL 写不进 redo/binlog、Redis 的 AOF 写不进去、应用日志也写不进去，
表现是**整个站点一起挂**，日志里只有一堆不相干的写入错误。

所以在上传前还会量一遍上传目录的占用：**已用 + 本次 > `app.upload.max-total-size`（默认 5GB）
就拒绝**，并给出明确提示（"上传空间不足：已用 x，上限 y，请先删除不再需要的旧附件"）。

- **统计的是"目录里所有文件加起来"，不是"数据库里记录的附件"** —— 磁盘是真的会满的，
  而库里的记录不一定对得上（删文章会删文件、用户传了没保存会留下孤儿文件、
  有人手工往目录里放过东西）。直接量目录才是"磁盘还剩多少"这个问题的答案
- **是"尽力而为"而不是强一致**：两个人同时上传时可能各自看到"还装得下"，
  于是略微超出上限（不超过"并发数 × 单文件上限"）。要对齐到字节就得上分布式锁，
  那个锁的代价（等待、超时、Redis 故障时上传全挂）远大于"偶尔超几十 MB" ——
  这道闸的目的是别让磁盘爆掉，不是把每一字节都算准
- 用例见 `ArticleAttachmentTest` ⑤（把上限调小到 6KB 来测，不真写 5GB）

**这里的文件是怎么被删掉的**：文章与曲目的删除都会连带清理**它们独占的**物理文件，
但**先查有没有别人还在用这个文件**（文章的正文/封面、文章的附件行、还在的曲目 ——
三处都为零才删），共用的一律不动。删除动作一律安排在**事务提交之后**（删文件不可逆，
而事务可能回滚；先提交再删文件，最坏只是留下一个没被引用的文件 + 一条日志）。
完整推导见下文「文章附件」与「音乐」两节，用例见 `ArticleAttachmentTest` ⑬⑭（★ 共用图不误删）
与 `MusicFileCleanupTest`。

**本项目用本地磁盘存封面图、音频与附件，不用对象存储。** 理由很简单：单台 ECS +
个人博客的量级，本地磁盘完全够用，**少一个外部依赖就少一处会失败的地方**
（网络抖动、密钥过期、配额限制、还要多付一份钱）。

> 📌 **那一套 OSS 代码已经删掉了（2026-09-10）**
> 项目早期确实写过一个 `OssFileStorage`（和 `LocalFileStorage` 实现同一个 `FileStorage`
> 接口），用 `app.upload.storage=local|oss` 这个开关切换，`aliyun-sdk-oss` 依赖也在 pom 里。
> 后来定为只用本地磁盘，于是把**实现类、依赖、一堆 `oss-*` 配置项、以及部署文档里的密钥说明
> 一起删了**。
>
> 为什么要"删掉"而不是"留着备选"？留下它的代价不只是几百 KB 的 jar：
>   · 它是**一条没有测试覆盖、也没人走过的代码路径**（要真实密钥与公网才能跑），
>     坏了不会有任何用例变红 —— 只会在某天"顺手切过去试试"时炸在线上
>   · 它带着一套**密钥配置**，配置项长期不用的典型结局是"过期了没人知道"
>   · `FileStorage` 这个接口本身没有一起删 —— **存储位置恰恰是几乎一定会变的那种东西**，
>     保留接口的成本是一个空文件，好处是换存储时 `UploadService` 一行都不用改。
>     将来真要上对象存储（图片多到磁盘吃紧、或者要上多台机器做负载均衡 ——
>     那时本地磁盘在每台机器上各自独立，用户上传的图在另一台就看不到），
>     照着接口写一个新实现 + 它自己的配置项即可。

图片的完整地址形如：

```
{UPLOAD_BASE_URL}/uploads/cover/2026/09/{uuid}.png
                      └─ 按年月分目录 ─┘  └ UUID 重命名 ┘
```

音频同理，只是目录换成了 `music`：

```
{UPLOAD_BASE_URL}/uploads/music/2026/09/{uuid}.mp3   ← 直接塞进 <audio src>
```

附件再到 `attachment`：

```
{UPLOAD_BASE_URL}/uploads/attachment/2026/09/{uuid}.pdf   ← 点它一定【下载】，不会就地打开
```

- **按年月分目录**：单目录里堆几万个文件，`ls`/备份/排查都会变得很难受
- **UUID 重命名**：用原名会撞名、还会把用户的文件名暴露在 URL 里 ——
  音频这一点更明显（"周杰伦 - 夜曲 (Live).mp3" 这种名字直接进 URL 只会给编码找麻烦）；
  附件同理（"毕业论文（终稿）.pdf" 这种名字会让 URL 编码很难受），
  而**附件在页面上的显示名**来自数据库（`article_attachment.name`）——
  用户看到的仍然是原始文件名，URL 里那个 UUID 他永远看不到
- **三类分开目录**：图片是"文章封面"、音频是"曲目文件"、附件是"可下载的资料"，
  量与生命周期都不同，备份 / 清理 / 排查（"这个 mp3 是谁传的"）时按目录一眼分得开；
  ⚠️ 而且**目录还是"要不要强制下载"的判断依据**（见上面那两道防线）

> ⚠️ **部署时最容易踩的一个坑：上传目录必须挂到卷上**
>
> 容器里的文件系统是**临时的**：容器一重建（改配置、升级版本、
> `docker compose up -d --build` 都会重建），写在容器里的图片**全部消失**，
> 而且不会有任何报错 —— 用户第二天来看，所有封面图都变成了裂图。
>
> 所以 `docker-compose.prod.yaml` 里给后端挂了具名卷：
> `uploads_data:/app/uploads`。具名卷独立于容器存在，
> `docker compose down` 也不会删它（**只有加 `-v` 才会删**，
> 所以清理环境时千万别顺手加 `-v`）。
>
> 另外 Dockerfile 里专门 `mkdir -p /app/uploads && chown app:app` ——
> 因为卷第一次被使用时，Docker 会把镜像里该挂载点的**属主**一起复制进新卷；
> 镜像里没这个目录的话，新卷会建成 `root:root`，
> 而非 root 运行的进程第一次上传就会 `Permission denied`。

**什么时候才真的需要换成对象存储**

① 图片多到 ECS 磁盘吃紧；② 将来上多台机器做负载均衡 ——
那时本地磁盘在每台机器上是各自独立的，用户上传的图在另一台上就看不到，
必须换成对象存储（或共享盘）。

换的时候要动的东西（都要改，缺一个就出问题）：
· 照 `FileStorage` 接口写一个新实现（例如 `OssFileStorage`），
  它自己负责"拼 URL / 调官方 SDK / 校验自己的配置项"
· 加回它自己的配置项与密钥注入（**必须用 RAM 子账号并只授权那一个 Bucket**，
  不要用主账号 AccessKey：主账号密钥等于整个账号的权限，泄漏了能改账单、能删所有资源）
· `UploadService` 与 `UploadController` **一行都不用改**（它们只依赖接口）
· 记得给它写用例 —— 上一版被删掉的原因之一就是"这条路径没有测试覆盖"

#### 数据库迁移（Flyway）

```properties
spring.flyway.enabled=true
spring.flyway.locations=classpath:db/migration
spring.flyway.baseline-on-migrate=true
spring.flyway.baseline-version=1
```

`baseline-on-migrate` 的作用见上面「初始化表结构」一节：让"空库自动建表"和
"已有的库不重复建表"这两件事用同一份配置解决。

> 💡 **一个容易踩的坑**：Spring Boot 4 把自动配置按技术拆成了独立模块，
> 所以除了 `flyway-core` 和 `flyway-mysql`，**必须再加 `spring-boot-flyway`**。
> 少了它编译和启动都不报错，但 Flyway 会**一声不响地不执行**
> （`flyway_schema_history` 表根本不出现）——这种"静默失效"最难查。

#### 🔑 JWT 密钥配置（重要）

```properties
jwt.secret=${JWT_SECRET:nr7j55m-oPN4-4FmtRY3OsMW6xQLJFuqsGFux7vqwSI}
jwt.expire-time=${JWT_EXPIRE_TIME:86400000}
```

**密钥注意事项（务必阅读）：**

1. **`jwt.secret` 是 JWT 签名密钥，必须保密。** 任何人拿到它都能伪造出合法的 token。
2. **默认值仅供本地开发。** 仓库里默认值即使公开也无碍，但**真实/生产密钥不要写死在仓库里**。
3. **生产环境如何覆盖密钥？** 两种方式：
   - **方式一（环境变量）**：
     ```bash
     export JWT_SECRET=你的真实密钥
     ```
   - **方式二（本地不提交的配置文件）**：创建 `application-secret.properties`（已加入 `.gitignore` 不会提交），内容填 `JWT_SECRET=你的真实密钥`，Spring 会自动读取。
4. **密钥长度 ≥ 32 字节（256 位）**，否则 HS256 签名会在启动时报错。
5. **`JWT_EXPIRE_TIME`**：token 过期时间，单位**毫秒**，默认 `86400000`（1 天），生产环境可适当调短。

> ⚠️ **生产环境务必替换默认 `JWT_SECRET`。** 用环境变量或本地配置文件注入真实密钥，切勿在仓库里硬编码真实密钥。

### 5. 运行

> 先确认第 2 步的本地 MySQL / Redis 已经起来 —— **这一步和跑测试不同**，
> 应用是连本地库的（`localhost:3310`），MySQL 没起会在启动时报连不上。

```bash
# 方式一：Maven
mvn spring-boot:run

# 方式二：仓库自带 wrapper（无需本地装 Maven）
./mvnw spring-boot:run
```

应用默认监听 `http://localhost:8082`。启动日志里会看到 Flyway 的执行结果：
空库会打印 `Successfully applied 1 migration`，已建过表的库会打印
`Schema ... is up to date. No migration necessary.`

### 6. 接口文档

- Swagger UI：`http://localhost:8082/swagger-ui.html`
- OpenAPI JSON：`http://localhost:8082/v3/api-docs`

> 可导入 **Apifox**（从 URL 导入 `http://localhost:8082/v3/api-docs`）进行接口管理与测试。

## 认证与鉴权（JWT 流程）

```
登录 → 校验用户名/密码（BCrypt） → 生成并返回 JWT
之后每次请求 → 请求头带 Authorization: Bearer <token>
        → JwtAuthenticationFilter 验签 → 查 Redis 认证缓存 → 写入 SecurityContext
        → 受保护接口校验通过后放行
```

- **密码**：BCrypt 单向加密存储，不保存明文
- **JWT**：无状态，服务端不存 token 本身，凭签名自证身份
- **角色**：`ADMIN` / `GUEST`，通过 `@PreAuthorize("hasRole('ADMIN')")` 做接口级权限

### 缓存与 token 即时撤销

JWT 是**无状态**的：服务端签出去就不管了，所以 token 在过期前一直有效。
这带来两个必须分别处理的问题，本项目用两套机制解决：

**① 用户身份/权限变了 → 按人撤销**（`UserAuthCache`）

管理员把某个用户禁用、删除或降级成游客之后，那个人手里的旧 token 还能继续用。
解决方式是把用户的认证信息（角色、状态）缓存进 Redis，并且**在四个写操作里主动删掉缓存**：
禁用/启用、改角色、重置密码、删除用户。

效果：这些操作生效后，该用户的下一次请求就会查不到缓存 → 回源数据库 →
读到新的 `status` / `role` → **旧 token 立刻被拒绝**（`UserAdminTest` 里有 3 个用例专门验证）。

**② 用户主动退出登录 → 按 token 撤销**（`TokenBlacklist`）

这个场景上面那套机制管不了：退出时用户本身没有任何变化 ——
账号还在、角色还在、状态也正常，只是**这一个 token 不该再被认**。

所以 `POST /auth/logout` 会把当前 token 的唯一编号（标准字段 `jti`）写进 Redis 黑名单，
过滤器每次解析完 token 会先查一下黑名单。

| 设计点 | 怎么做的 | 为什么 |
|--------|---------|--------|
| 按 `jti` 而不是按用户 | 只拉黑这一个 token | 用户可能手机电脑同时登着，**在手机上退出不该把电脑也踢下线** |
| 黑名单有 TTL | TTL = 该 token 的**剩余有效期** | 过了那一刻 token 自己就失效了，记录留着纯属占内存；**不需要任何定时清理任务** |
| 登出接口幂等 | 永远返回成功 | 重复点退出、token 已失效、甚至没带 token，都应该是"退出成功"。否则前端会在用户点完退出的瞬间弹一个"登录已过期"的登录框 |
| 查黑名单失败宁可放行 | 读失败当作"未拉黑" | Redis 抖动不应该变成全站不可用；真正的兜底是 token 的过期时间 |

> **代价要说清楚**：每个带 token 的请求多一次 Redis 查询（`hasKey`）。
> 这是"能撤销 token"必须付的成本 —— 无状态的 JWT 本身做不到撤销。
> 想省掉这一次查询就只能改用有状态 session，那是另一个方向的取舍。

## 接口列表

共 **60 个接口**。「是否需要登录」一列指**访问该接口本身**的要求，
具体到角色见下方「接口 × 角色权限矩阵」。

| # | 方法 | 路径 | 说明 | 是否需要登录 |
|---|------|------|------|:---:|
| 1 | POST | `/auth/register` | 用户注册 | 否 |
| 2 | POST | `/auth/login` | 登录（返回 token，**有限流**：5 次/分钟） | 否 |
| 3 | POST | `/auth/logout` | 退出登录（把当前 token 拉黑，幂等） | 否 |
| 4 | GET | `/auth/me` | 获取当前登录用户 | 是 |
| 5 | GET | `/article/page` | 前台文章分页列表（仅已发布，**走 Redis 缓存 + 限流**；支持 `tagId` 标签筛选） | 否 |
| 6 | GET | `/article/{id}` | 前台文章详情（仅已发布，返回里带 `tags` 与 `attachments`） | 否 |
| 7 | GET | `/article/stats` | 站点统计：文章数 / 总浏览量 / 分类数（首页那三个数字，**只算已发布**） | 否 |
| 8 | GET | `/article/archive` | 归档：已发布文章**按年月分组**（最新的月份在前），走 Redis 缓存 | 否 |
| 9 | GET | `/article/rss` | RSS 数据：最近 20 篇已发布文章的正文（前端用它拼 `feed.xml`），走 Redis 缓存 | 否 |
| 10 | GET | `/category/list` | 分类列表 | 否 |
| 11 | GET | `/tag/list` | 标签列表（标签云，**每个标签带已发布文章数**，走 Redis 缓存） | 否 |
| 12 | GET | `/comment/list` | 某篇文章的评论（**只返回已通过的**，必须带 `articleId`） | 否 |
| 13 | POST | `/comment` | 发表评论（**游客可用，默认待审核**；有限流：20 次/分钟；**站点设置里关掉评论总开关时后端直接拒绝**，code 403） | 否 |
| 14 | GET | `/user/page` | 用户分页查询 | 是（ADMIN） |
| 15 | PUT | `/user/{id}/status` | 启用 / 禁用用户 | 是（ADMIN） |
| 16 | PUT | `/user/{id}/role` | 修改用户角色 | 是（ADMIN） |
| 17 | PUT | `/user/{id}/password` | 重置用户密码 | 是（ADMIN） |
| 18 | DELETE | `/user/{id}` | 删除用户（逻辑删除） | 是（ADMIN） |
| 19 | GET | `/admin/article/page` | 后台文章分页（含草稿，多条件筛选） | 是（ADMIN） |
| 20 | GET | `/admin/article/{id}` | 后台文章详情（含正文；带 `tags` 与 `attachments`） | 是（ADMIN） |
| 21 | POST | `/admin/article` | 新增文章（可带 `tagIds` 打标签、`attachments` 提交附件；**可选 `Idempotency-Key` 请求头防重复提交**） | 是（ADMIN） |
| 22 | PUT | `/admin/article/{id}` | 编辑文章（`tagIds` 与 `attachments` 都是**覆盖式**语义） | 是（ADMIN） |
| 23 | PUT | `/admin/article/{id}/status` | 发布 / 下架文章 | 是（ADMIN） |
| 24 | DELETE | `/admin/article/{id}` | 删除文章（逻辑删除） | 是（ADMIN） |
| 25 | GET | `/admin/tag/list` | 标签列表（后台，**不走缓存**：刚建完就要看得见） | 是（ADMIN） |
| 26 | POST | `/admin/tag` | 新建标签 | 是（ADMIN） |
| 27 | PUT | `/admin/tag/{id}` | 编辑标签（改名 / 改排序） | 是（ADMIN） |
| 28 | DELETE | `/admin/tag/{id}` | 删除标签（**物理删除**，同时解除文章关联） | 是（ADMIN） |
| 29 | GET | `/admin/comment/page` | 评论分页（含待审核，**带邮箱与 IP**） | 是（ADMIN） |
| 30 | PUT | `/admin/comment/{id}/status` | 审核评论（1 通过 / 2 拒绝） | 是（ADMIN） |
| 31 | DELETE | `/admin/comment/{id}` | 删除评论（逻辑删除） | 是（ADMIN） |
| 32 | GET | `/admin/category/list` | 分类列表（后台） | 是（ADMIN） |
| 33 | POST | `/admin/category` | 新建分类 | 是（ADMIN） |
| 34 | PUT | `/admin/category/{id}` | 编辑分类（改名 / 描述 / 排序） | 是（ADMIN） |
| 35 | DELETE | `/admin/category/{id}` | 删除分类（**分类下有文章时会被拒绝**） | 是（ADMIN） |
| 36 | POST | `/upload` | 上传文件（**不传 `type` 或 `type=image` → 图片，10MB；`type=audio` → 音频，mp3 / 20MB；`type=attachment` → 文章附件，16 种格式 / 100MB**），返回可访问地址 `{url, name, size}` | 是（ADMIN） |
| 37 | GET | `/link/list` | 友链列表（**只含"显示"的**，按 `sort` 升序；走 Redis 缓存） | 否 |
| 38 | GET | `/admin/link/list` | 友链列表（后台，**含隐藏的、不走缓存**） | 是（ADMIN） |
| 39 | POST | `/admin/link` | 新建友链 | 是（ADMIN） |
| 40 | PUT | `/admin/link/{id}` | 编辑友链（名称 / 地址 / 头像 / 简介 / 排序 / 显示状态） | 是（ADMIN） |
| 41 | DELETE | `/admin/link/{id}` | 删除友链（逻辑删除） | 是（ADMIN） |
| 42 | GET | `/project/list` | 项目列表（**只含"显示"的**，按 `sort` 升序；走 Redis 缓存） | 否 |
| 43 | GET | `/admin/project/list` | 项目列表（后台，**含隐藏的、不走缓存**） | 是（ADMIN） |
| 44 | POST | `/admin/project` | 新建项目（**在线地址与仓库地址至少填一个**） | 是（ADMIN） |
| 45 | PUT | `/admin/project/{id}` | 编辑项目（名称 / 简介 / 两个地址 / 封面 / 技术栈 / 排序 / 显示状态） | 是（ADMIN） |
| 46 | DELETE | `/admin/project/{id}` | 删除项目（逻辑删除） | 是（ADMIN） |
| 47 | GET | `/favorite/list` | 收藏列表（**只含"显示"的**，按 `sort` 升序；走 Redis 缓存） | 否 |
| 48 | GET | `/admin/favorite/list` | 收藏列表（后台，**含隐藏的、不走缓存**） | 是（ADMIN） |
| 49 | POST | `/admin/favorite` | 新建收藏 | 是（ADMIN） |
| 50 | PUT | `/admin/favorite/{id}` | 编辑收藏（标题 / 地址 / 备注 / 分组 / 排序 / 显示状态） | 是（ADMIN） |
| 51 | DELETE | `/admin/favorite/{id}` | 删除收藏（逻辑删除） | 是（ADMIN） |
| 52 | GET | `/about` | 关于页信息（单条对象；**只含一行**，走 Redis 缓存） | 否 |
| 53 | PUT | `/admin/about` | 保存关于页信息（**单条更新，没有新建/删除**） | 是（ADMIN） |
| 54 | GET | `/music/list` | 音乐列表（**只含"显示"的**，按 `sort` 升序、`sort` 相同按 `id` 升序；走 Redis 缓存；**列表为空返回空数组，不是 404**） | 否 |
| 55 | GET | `/admin/music/list` | 音乐列表（后台，**含隐藏的、不走缓存**） | 是（ADMIN） |
| 56 | POST | `/admin/music` | 新建音乐（`url` 来自 `POST /upload?type=audio`） | 是（ADMIN） |
| 57 | PUT | `/admin/music/{id}` | 编辑音乐（曲名 / 歌手 / 音频地址 / 封面 / 歌词 / 排序 / 显示状态） | 是（ADMIN） |
| 58 | DELETE | `/admin/music/{id}` | 删除音乐（逻辑删除；**音频文件在确认无人引用后一并清掉**，外链不碰） | 是（ADMIN） |
| 59 | GET | `/setting` | 站点设置（单条对象；站点名 / 首页公告 / 评论总开关 / 页脚版权与两个备案号（`icpNumber` / `policeNumber`）/ 每页文章条数，**走 Redis 缓存**） | 否 |
| 60 | PUT | `/admin/setting` | 保存站点设置（**单条更新，没有新建/删除**；`pageSize` 上限跟文章接口同为 50） | 是（ADMIN） |

**共 60 个接口。** 接口文档（`/v3/api-docs`、`/swagger-ui/**`、`/swagger-ui.html`）也无需登录。
用本地磁盘存储时，上传的图片、音频与附件都通过 `GET /uploads/**`（以及 `HEAD /uploads/**`，
见下面「为什么 HEAD 也要放行」）公开读取（无需登录）——
图片在 `uploads/cover/`，音频在 `uploads/music/`，附件在 `uploads/attachment/`，
同一个静态映射覆盖子目录，不需要额外配置。
⚠️ 三种文件的**响应头不同**：图片与音频内联展示/播放，附件强制
`Content-Disposition: attachment` 下载（见「文件上传」章节里那两道防线）。

**文章附件的接口形状**（前端已按它实现）：

```
保存文章（POST /admin/article、PUT /admin/article/{id}）请求体里新增：
  "attachments": [ { "name": "毕业论文.pdf", "url": "http://.../uploads/attachment/2026/09/x.pdf", "size": 1258291 } ]
  → 后端【整体替换】：先删掉这篇文章原有的全部附件行（以及其中被移除的文件），再按提交的列表插入
  → 校验：最多 20 条 / name ≤ 100 字 / size ≤ 附件上限（默认 100MB）/ url 必须落在本站上传地址前缀之内
读取文章（GET /article/{id}、GET /admin/article/{id}）返回的 data 里带 attachments 数组（name / url / size）
```

## 接口 × 角色权限矩阵

三种访问身份：**游客**（不带 token）、**普通用户**（GUEST + 合法 token）、**管理员**（ADMIN + 合法 token）。

> **这张表应当覆盖上面全部 60 个接口**，账是这么对的：**18 个公开**（`/auth/register` `/auth/login` `/auth/logout`、
> `GET /article/page` `stats` `archive` `rss` `{id}`、`GET /category/list` `tag/list` `comment/list` `link/list` `project/list` `favorite/list` `music/list` `about` `setting`、`POST /comment`）
> **+ 1 个只需登录**（`GET /auth/me`）**+ 41 个管理员接口**（`/user/**` 5 + `/admin/article/**` 6 +
> `/admin/tag/**` 4 + `/admin/comment/**` 3 + `/admin/category/**` 4 + `/admin/link/**` 4 +
> `/admin/project/**` 4 + `/admin/favorite/**` 4 + `/admin/music/**` 4 + `/admin/about` 1 +
> `/admin/setting` 1 + `POST /upload` 1）= 60。
> 以后加接口时这两处要一起改 —— 少一行不会有任何报错，只会让人在这张表里找不到那个接口的权限。

| 接口 | 游客 | GUEST | ADMIN |
|------|:---:|:---:|:---:|
| `POST /auth/register` | ✅ | ✅ | ✅ |
| `POST /auth/login` | ✅ | ✅ | ✅ |
| `GET /article/page` | ✅ | ✅ | ✅ |
| `GET /article/stats` | ✅ | ✅ | ✅ |
| `GET /article/{id}`（已发布） | ✅ | ✅ | ✅ |
| `GET /article/{id}`（草稿） | ❌ 404 | ❌ 404 | ❌ 404（走后台接口） |
| `GET /category/list` | ✅ | ✅ | ✅ |
| `GET /tag/list` | ✅ | ✅ | ✅ |
| `GET /link/list` | ✅ | ✅ | ✅ |
| `GET /project/list` | ✅ | ✅ | ✅ |
| `GET /favorite/list` | ✅ | ✅ | ✅ |
| `GET /about` | ✅ | ✅ | ✅ |
| `GET /setting` | ✅ | ✅ | ✅ |
| `GET /music/list` | ✅ | ✅ | ✅ |
| `GET /article/archive` | ✅ | ✅ | ✅ |
| `GET /article/rss` | ✅ | ✅ | ✅ |
| `GET /comment/list`（已通过） | ✅ | ✅ | ✅ |
| `POST /comment`（**游客可发**，默认待审核） | ✅ | ✅ | ✅ |
| `POST /auth/logout` | ✅ | ✅ | ✅ |
| `GET /auth/me` | ❌ 401 | ✅ | ✅ |
| `/user/**`（全部 5 个） | ❌ 401 | ❌ 403 | ✅ |
| `/admin/article/**`（全部 6 个） | ❌ 401 | ❌ 403 | ✅ |
| `/admin/tag/**`（全部 4 个） | ❌ 401 | ❌ 403 | ✅ |
| `/admin/comment/**`（全部 3 个） | ❌ 401 | ❌ 403 | ✅ |
| `/admin/category/**`（全部 4 个） | ❌ 401 | ❌ 403 | ✅ |
| `/admin/link/**`（全部 4 个） | ❌ 401 | ❌ 403 | ✅ |
| `/admin/project/**`（全部 4 个） | ❌ 401 | ❌ 403 | ✅ |
| `/admin/favorite/**`（全部 4 个） | ❌ 401 | ❌ 403 | ✅ |
| `/admin/music/**`（全部 4 个） | ❌ 401 | ❌ 403 | ✅ |
| `PUT /admin/about` （只有这一个） | ❌ 401 | ❌ 403 | ✅ |
| `PUT /admin/setting` （只有这一个） | ❌ 401 | ❌ 403 | ✅ |
| `POST /upload`（图片 / 音频 / 附件都是它） | ❌ 401 | ❌ 403 | ✅ |
| `GET /uploads/**`、`HEAD /uploads/**`（本地存储的图片、音频与附件） | ✅ | ✅ | ✅ |

> ⚠️ **为什么 HEAD 也要单独放行（2026-09 补的一行）**：Spring Security 的
> `requestMatchers(HttpMethod.GET, "/uploads/**")` 是**按方法精确匹配**的，HEAD 不在里面 ⇒
> 对 `/uploads/**` 发 HEAD 会落到链尾的 `anyRequest().authenticated()` ⇒ 返回 **401**。
> 浏览器看图、点附件下载用的都是 GET，所以这个洞在人工点页面时**看不出来**；
> 但**监控探活、链接检查器、部分代理的预取**发的是 HEAD，它们会把 401 报成
> "资源不可用 / 权限坏了"。（真实踩到过：给附件做冒烟测试时 `curl -sI` 拿 401，换 GET 就是 200。）
> ⚠️ 代码里是两行而不是一个 matcher 带两个方法：`requestMatchers` 只有
> "一个方法 + 若干路径"和"只给路径"两种重载，写 `requestMatchers(HttpMethod.GET, HttpMethod.HEAD, ...)`
> 是编译不过的（第二个参数起是路径模式）。有用例守着（`UploadAdminTest` ㉓），
> 且那条用例带**反向断言**：`HEAD /user/page` 必须仍然是 401 ——
> 否则"把所有请求都 permitAll"也能让正向断言变绿，而那等于整站接口裸奔。

**几个刻意的设计决定：**

- **区分 401 和 403**：没带 token 是「你是谁我不知道」→ 401；带了 token 但角色不够是「我知道你是谁，但你不能干这个」→ 403。
- **草稿对所有人返回 404，包括管理员走前台接口**。管理员的入口是 `/admin/article/{id}`，
  前台接口就是给游客看的，统一返回 404 可以避免"探测"到某篇草稿的存在。
- **`/user/**` 的权限写在类上**（`@PreAuthorize("hasRole('ADMIN')")`），
  这样新增方法时不会漏加注解——比每个方法写一遍更安全。

## 浏览量为什么是异步的

文章详情是**读接口**，但它原本每次请求都会执行一条 `UPDATE article SET view_count = view_count + 1`。
这会带来两个具体问题：

- **写放大**：全站唯一会写库的读接口，热门文章被频繁打开时，这条 UPDATE 会成为最热的写语句
- **行锁竞争**：并发打开同一篇文章时，这些 UPDATE 会在同一行上排队等锁 ——
  一个"看文章"的动作被写操作拖慢

现在改成：详情页只做一次 **Redis INCR**（内存操作、无行锁），
由 `ViewCountSyncTask` 每 5 分钟把累计增量批量写回数据库。

| 设计点 | 怎么做的 | 为什么 |
|--------|---------|--------|
| 返回的数字 | **库里的快照 + Redis 里还没落库的增量** | 只返回库里的值的话，用户会看到"我刷新了但数字不动"，因为最新计数还没到落库时间 |
| 增量怎么取走 | `GETDEL`（取值并删除，**原子**） | 分"先 GET 再 DEL"两步的话，两步之间来的访问会被 DEL 一起删掉 —— 计数凭空少一次，且事后无法察觉 |
| 落库怎么写 | `view_count = view_count + ?`（让数据库自己加） | 换成"查出来加完再写回"在并发下会丢计数 |
| 空转 | 没有增量时直接返回，**连日志都不打** | 每 5 分钟一条"无事发生"，一天 288 条，会把真正的信息淹掉 |
| 任务间隔 | `app.article.view-sync-interval-ms`，默认 5 分钟 | 抽成配置是为了能调：压测或排查时想让它立刻跑，改配置比改代码快 |
| 调度方式 | `@Scheduled(fixedDelay)` 而不是 `fixedRate` | `fixedRate` 在执行时间超过间隔时会积压甚至重复执行；`fixedDelay` 是"上次跑完再等"，天生不会重入 |

> ⚠️ **两个接口对"最新计数"的态度不一样 —— 这一点很容易被当成 bug（2026-09-11 补记）**：
> · `GET /article/{id}`（详情）= **库里的快照 + Redis 增量**，所以刚看完刷新就能看到 +1；
> · `GET /article/stats`（站点统计）= **只读库里的 `SUM(view_count)`**，所以它**要等落库才会跳一次**，
>   最多滞后 5 分钟（`app.article.view-sync-interval-ms`）。
>
> 实测（同一台 dev 环境、连续操作）：`stats` 的 `viewCount` 是 **47** →
> 连续读 3 次 `/article/117` → 再查 `stats`，**仍然是 47**；
> 而同一时刻那篇文章的详情里显示 **20**、Redis 里 `article:view:117` 的值是 **4**。
> 也就是：**增量确实在，只是还没落库**。
> 这是刻意的取舍 —— 站点总数字没必要每个请求都去 Redis 把每个 key 求一遍和
> （那等于给统计接口引入 N 次读），而"总数字滞后 5 分钟"对展示没有影响。
> 排查时如果看到总数字不动，**先看落库间隔，别先怀疑计数**；
> 想让它立刻落一次，改配置重启即可（默认 300000 毫秒）。
>
> ⚠️ **一个诚实的说明**：流程是"先从 Redis 取走增量 → 再写库"，
> 所以**写库失败的那批增量会永久丢失**（不会有重复计数，但可能少计）。
> 这是刻意的取舍：浏览量是展示型数据，少几次不造成业务错误；
> 而反过来做（先写库成功再删 Redis）会让同一批数字被重复累加，
> 而"数字偏大"更容易被人当成作弊。更严格的做法是用消息队列做可靠投递，
> 对浏览量的精度要求来说不值得。

## 文章列表缓存

前台列表是全站最热的读接口，所以给它加了 Redis 缓存。这一节讲清楚
**缓存怎么组织、什么时候失效、以及为什么不能用最直觉的那种失效方式**。

### 缓存 key 的结构

```
article:page:v1 : {版本号} : {页码}:{每页}:{分类}:{关键词}:{排序}:{方向}
└─── 缓存名:格式版本 ──┘   └─ 版本号 ─┘ └────── 查询条件的归一化字符串 ──────┘
```

- **格式版本 `v1`**：哪天 `ArticleVO` 的结构变了，改成 `v2` 就能让旧缓存
  立刻全部作废，而不会被当成新结构读出来 —— 比手动 `FLUSHDB` 安全，也说得清。
- **版本号**：失效机制的核心，见下。
- **查询条件**：由 `ArticleQuery.toCacheKey()` 归一化生成。归一化很关键 ——
  不传 `size` 与显式传 `size=10` 结果一样，必须命中同一条缓存；
  `size=51 / 52 / 999` 实际都会被夹到 50，也必须同一条，
  否则"改个 size 就能绕过缓存"，缓存等于形同虚设。

### 失效为什么用「版本号」而不是「删缓存」

最直观的写法是 `@CacheEvict(allEntries = true)`：写操作时把缓存名下所有 key 删掉。
**这个方案在这里不成立**，而且原因很隐蔽：

> **`RedisCache.clear()` 不是同步完成的。**
> 用一个"只碰缓存、不碰数据库"的探针实测（同一线程内顺序打印）：
>
> | 操作 | 立刻查 Redis | 500ms 后再查 |
> |---|---|---|
> | `cache.put(k, v)` | 已写入 | —— |
> | `cache.clear()` | **还在！** | 已删除 |
> | `cache.evict(k)` | 已删除 | —— |
>
> 方法返回时还没删完，删除在后台继续跑。业务表现就是
> **"发布文章后刷新前台，有时看得到、有时看不到"** —— 取决于那次刷新
> 有没有赶在后台删除完成之前。而且显式指定 `BatchStrategies.keys()`
> 也不能把它变同步，说明这是该 API 的语义，不是配置问题。

改成版本号之后：

```
读：key = 版本号 + 查询条件   →  命中就直接返回
写：只做一件事 —— INCR 一个计数器（article:page:version）
```

版本号一变，之前所有 key **再也拼不出来** —— 等于一瞬间全部作废，
但**一条数据都不用删**。换来四个好处：

| | 删缓存方案 | 版本号方案 |
|---|---|---|
| 失效时机 | 异步，可能读到旧数据 | **同步**（`INCR` 发完才返回，且后续读走同一条连接，顺序有保证） |
| 写入代价 | O(缓存条数) | **O(1)**：不管缓存里有 10 条还是 10 万条，都只是一次 `INCR` |
| 需要的命令 | `KEYS`/`SCAN` 批量匹配并删除 | 不需要任何批量匹配 |
| 旧数据 | 删掉 | 留着等 TTL 自然过期（TTL 到了自己消失，不会无限增长） |

> **代价说清楚**：旧版本的 key 不会被主动清理，短时间内 Redis 里会同时存在
> 新老两个版本的少量 key。因为 TTL 只有 5 分钟（带抖动），它们会自己消失。

**写入侧的失效点有四处**：新建、编辑、发布/下架、删除 —— 四个都调了同一个
`bump()`，并且各有一条用例盯着（`ArticleCacheTest`）。

### 防穿透 与 防雪崩

| 问题 | 是什么 | 这里怎么防 |
|------|--------|-----------|
| **缓存穿透** | 有人拿一堆**必然查不到**的条件反复刷接口，缓存永远不命中、请求全落到数据库 | **把"没有结果"也缓存起来**，但只给 **30 秒** TTL。30 秒足够挡掉重复的恶意查询，又不会让"刚发布的文章看不到"持续太久 |
| **缓存雪崩** | 一批缓存在**同一时刻**被写入，于是会在同一秒集体失效，请求瞬间全压到数据库 | 每条缓存的 TTL 加 **0~60 秒随机抖动**，把过期时间摊开 |

这两件事由 `RedisConfig` 里的 `TtlFunction` 按内容分别决定时长：
空结果 30 秒、有内容 5 分钟 + 抖动。

### ⚠️ 两个查了很久才查清的"假故障"（都记在测试注释里）

排查缓存问题时，**"看起来没生效"和"真的没生效"很难区分**。
这里有两个坑，结论都是"功能其实是好的，是我读证据的方式错了"：

**① "空结果根本不会被缓存" —— 错的。**

真相是缓存写入是**异步发出**的。用 `redis-cli MONITOR` 抓命令流能看到：

```
[连接A] GET  "article:page:v1:1:...:ZZTXB..."    ← 缓存未命中
[连接A] KEYS "article:page:v1:*"                 ← 测试在这里数 key，数到 0
[连接B] SET  "article:page:v1:1:...:ZZTXB..."    ← put 在 22 微秒之后才被 Redis 处理
```

`SET` 里的值清清楚楚是 `{"records":["java.util.Collections$EmptyList",[]],"total":0}` ——
**空结果被正常缓存了**。只是接口方法返回时写命令可能还在路上，
测试紧接着用**另一条连接**发 `KEYS`，两条连接的命令到达 Redis 的先后顺序没有保证。

所以测试改成"等一小会儿再看"（有上限的轮询）：断言的对象是
**缓存最终有没有写进去**，而不是**写命令有没有在方法返回前就到 Redis** ——
后者本来就不是任何缓存实现会承诺的语义。

**② "`put` 偶发不落地" —— 同一个原因，见上。**

> 教训：**断言了一条系统并不承诺的时序，测试就会随机红。**
> 遇到"偶发失败"时，先问自己："我这个断言依赖了什么保证？那个保证存在吗？"

### 一个已知边界（有用例钉着）

**绕过应用直接改库（手工 SQL、DBA 改数据、别的服务直连同一个库），
缓存不会知道**，数据最多在 TTL 之后才自愈。这是"旁路缓存"必然的代价，
不是 bug。`ArticlePublicTest` 里专门有一条用例把这个边界固定下来：
将来如果有人加了 CDC / 触发器来失效缓存，那条用例会变红，提醒他行为变了。

## 文章详情缓存

详情页也做了缓存。但它的形状和列表缓存**不一样**，因为详情里有一个"每次都在变"的字段：
**浏览量**。这一节主要讲清楚那个字段带来了哪些坑。

### ⚠️ 坑一：浏览量绝不能被缓存进去

详情返回的浏览量 = 「库里的快照 + Redis 里还没落库的增量」，而增量**每次访问都会变**。
如果把最终结果整个缓存起来，命中缓存的请求就不会再执行 `INCR` ——
**浏览量会永远停在那个值上**。

这个 bug 特别隐蔽：页面照常打开、数字照常显示，只是不再增长。
所以缓存的边界划在"**库里的那份数据**"：

```
viewCounter.increment(id)            ← 副作用，必须在缓存外
ArticleVO vo = cache.load(id)        ← 这一层才是缓存的（存放库里的快照）
vo.setViewCount(快照 + pending(id))   ← 合并，也在缓存外
```

`ArticleDetailCacheTest` 里有一条用例专门盯这件事：**连续访问三次，断言 1 → 2 → 3**。
只要有人把 INCR 挪进缓存里，这条立刻就红。

### 坑二：为什么这段可缓存逻辑要单独放一个类

Spring Cache 靠**代理**生效，而代理拦不住"同类内部的方法调用"。
如果直接在 `ArticleServiceImpl.getPublishedDetail()` 里调 `this.loadFromDb()`，
`@Cacheable` 就等于没写 —— 不报错、也不生效（Spring Cache 最经典的那个坑）。

所以抽出了 `PublishedArticleCache` 这个 Bean，由 Service 注入后从外部调用，代理必然生效。

### 防击穿：用 `sync = true`，而不是自己写 SETNX

**击穿**指的是：热点 key 恰好过期，同时涌进来 N 个请求，它们**全部**发现缓存没了、
**全部**去查库 —— 一瞬间对同一行数据发起 N 次查询，等于缓存白做了。

做法是 `@Cacheable(sync = true)`：它让 Spring 走 `Cache.get(key, Callable)` 那条路，
Redis 那边在未命中时先抢一把锁，只有抢到的那个去加载，其余等锁释放后读缓存。

> **为什么不用自己写 SETNX + 双重检查**：那正是写入器内部在做的事，
> 只是它把锁超时、异常时释放、抢不到时重试这些细节都处理好了。
> 自己写只会多出几个能写错的地方，而且错了的表现是"偶发多查一次库"，
> 几乎不可能被发现。

**实测证明**（`ArticleDetailCacheTest` 里那条并发用例，不是嘴上说说）：
用 `@MockitoSpyBean` 给 `ArticleMapper` 套了一层只记账的替身，
**12 个线程同时访问同一篇未缓存的文章，`selectById` 只被调用了 1 次**。

```java
verify(spyArticleMapper, times(1)).selectById(articleId);   // 12 个线程 -> 只有 1 次查库
```

> 📌 这条用例还顺带踩了一个测试上的坑：并发要靠多线程，而别的线程
> **看不到当前测试事务里未提交的那一行**，它们会统统查到"文章不存在"。
> 所以这条用例特意关掉了测试事务（`@Transactional(propagation = NOT_SUPPORTED)`），
> 自己负责清理数据。

### 失效：为什么详情要**单独一个**版本号

这是全量测试抓出来的一个真 bug，值得记下来。

两个缓存都有 `view_count`，但它们对"过期数据"的容忍度完全不同：

| | 列表缓存 | 详情缓存 |
|---|---|---|
| 怎么用这个值 | **原样展示**库里的值 | **快照 + 增量**合并后展示 |
| 落库后变旧会怎样 | 数字暂时偏小，等 TTL 自然刷新；**只会滞后，不会回跳** | 快照是旧的、增量已清零 → 显示值**比落库前还小** —— 数字当着用户的面往回跳 |

具体数字：一篇文章被看了 3 次（库 0 + 增量 3 = 显示 3）→ 定时落库（库变 3、增量清零）
→ 再有人看一次：如果详情缓存里那份快照还是旧的，就会显示 `0 + 1 = 1`，**从 3 掉到 1**。

所以：**文章被写 → 两个版本号一起推进；浏览量落库 → 只推进详情那个**。
只推动必要的，列表缓存就不会被每 5 分钟一次的无谓失效拖累。

> 这个 bug 不是想出来的，是 `ArticleViewCountTest` 的
> "落库之后继续访问 → 从新的基线往上加"那条用例变红之后才暴露的 ——
> **全量测试的价值就在这里**：它抓到了单看某一个功能时完全想不到的交叉影响。

## 统一返回与错误处理

所有接口统一返回：

```json
{ "code": 200, "message": "成功", "data": { } }
```

结果码（`common/ResultCode.java`）：

| code | 含义 |
|------|------|
| 200 | 成功 |
| 400 | 参数校验失败 / 用户名或密码错误 / 账号已存在 / 分类名已存在 / 标签名已存在 / **请求体格式不对** / **缺必填参数** / **参数类型不对** / 分类下还有文章 |
| 401 | 未登录或登录已过期 |
| 403 | 无权限访问 / 账号已被禁用 / **评论已关闭**（站点设置里把评论总开关关掉了，见 `ResultCode.COMMENT_DISABLED`） |
| 404 | 用户不存在 / 文章不存在 / 分类不存在 / 标签不存在 / 评论不存在 / **接口不存在** |
| 405 | **请求方法不支持**（用 POST 打了一个只支持 GET 的接口） |
| 429 | **请求正在处理中，请勿重复提交**（幂等键命中了"处理中"状态）；HTTP 429 则是被限流 |
| 500 | 服务器内部错误 |

### ⚠️ 关于 HTTP 状态码（一个必须说清楚的现状）

本项目**不是**"HTTP 状态码 == body.code"，而是**分成两类**处理。这个划分是有意的：

| 场景 | 由谁处理 | HTTP 状态码 | body.code |
|------|---------|:---:|:---:|
| 未登录 / token 无效 | `SecurityConfig` 的 `authenticationEntryPoint` | **401** | 401 |
| 权限不足（过滤器层） | `SecurityConfig` 的 `accessDeniedHandler` | **403** | 403 |
| 权限不足（`@PreAuthorize`） | `GlobalExceptionHandler#handleAccessDenied` | **403** | 403 |
| **地址不存在** | `GlobalExceptionHandler#handleNoResourceFound` | **404** | 404 |
| **请求方法用错** | `GlobalExceptionHandler#handleMethodNotSupported` | **405** | 405 |
| **被应用层限流** | `GlobalExceptionHandler#handleRateLimited` | **429** | 429 |
| **请求体不是合法 JSON / 缺必填参数 / 参数类型不对** | 三个专门的 handler（见下） | **200** | 400 |
| 业务异常 / 参数校验 / 认证失败 / 账号禁用 / 未知异常 | `GlobalExceptionHandler` 其余 handler | **200** | 400 / 401 / 403 / 500 |

**划分的依据是"这个错误是谁的问题"**：

- **请求本身有问题**（地址写错、方法用错、没登录、没权限）→ 返回**真实状态码**。
  因为这一类错误【不是业务语义的一部分】，而且中间所有的软件
  （Nginx 访问日志、监控告警、负载均衡健康检查、浏览器）**都只看状态码**。
  如果这些也报 200，"全站错误率"在监控里永远是 0% —— 数据库挂了、接口全 500，
  报表上还是一片绿。
- **业务上不成功**（密码错、文章不存在、参数不合法）→ **HTTP 200 + body.code**。
  前端只需要认 `code` 一个地方，不用同时判断状态码和内容；
  这是一种常见且自洽的约定。
- **请求写错了、但前端会自己触发**（body 不是合法 JSON、漏传必填参数、
  参数类型不对）→ 仍然是 **HTTP 200 + body.code = 400**。
  这一类归到"业务错误"里，是因为它确实会被前端自己触发（表单/请求拼错了），
  改状态码要前后端一起回归 —— 那是下面那条 5.6 该做的事。
  **但重点是它绝不能报 500**：500 的语义是"服务器自己坏了"，
  监控会把它当成真故障、真正的服务器故障反而被这种噪音淹掉。
  这三个 handler 是给新接口做真实环境验证时顺手发现的
  （作者本人用 curl 拼坏了一个 JSON，看到回的是"服务器内部错误"，于是把这一类都试了一遍），
  现在分别回"请求体格式不正确 / 缺少必填参数：xxx / 参数 id 格式不正确"。

> **为什么业务错误不一起改成真实状态码**
> 那是**破坏性变更**：前端现在写的是"只要 HTTP 200 就读 body.code"，
> 一改状态码，前端每个调用点都要跟着改并重新回归。
> 而"请求本身有问题"这一类前端**永远不会主动触发**（它不会去请求不存在的地址），
> 所以改它对前端零影响 —— 这是算过收益与破坏面之后的取舍，不是漏改了。
> 完整统一（业务错误也一致）排在路线图 5.6，需要前后端在同一个时间窗内一起改。

## 接口幂等（防止重复提交）

**场景**：后台点「发布」，网络卡了两秒没反应，你又点了一下 ——
浏览器发出两个一模一样的创建请求，数据库里就出现了两篇一样的文章。

**做法**：幂等键（`Idempotency-Key` 请求头），这是 Stripe 等支付接口用的同一套思路。

```
前端：打开「新建文章」弹窗时生成一个 UUID —— 这一次提交动作全程用它
后端：① 拿这个键去 Redis 占位（SET NX，原子的）
      ② 占上了 → 正常创建，成功后把结果（文章 id）存起来，24 小时后过期
      ③ 没占上、但已有结果 → 直接把上次的 id 返回，【不再创建】
      ④ 没占上、还没结果 → 说明另一个同样的请求正在处理，返回 429「请勿重复提交」
```

| 设计点 | 怎么做的 | 为什么 |
|--------|---------|--------|
| 键什么时候生成 | **打开弹窗时一次**，保存成功后换新 | 每次点保存都生成新键的话，点两下就是两个不同的键，后端当成两次请求，**照样写出两篇**。绑定到"一次提交动作"才拦得住 |
| 占位用什么命令 | `SET key value NX EX 60`（`setIfAbsent`） | "判断不存在"和"写入"必须在一条命令里原子完成；分两步的话两个并发请求会同时查到不存在然后都写进去 |
| 结果为什么存起来 | 存的是**创建出来的文章 id** | 只存"处理过了"的话，重复请求只能回一句"你已经创建过了"，前端还得自己去列表里找是哪一篇 —— 把问题丢给了调用方 |
| 占位 TTL 为什么是 60 秒（结果 24 小时） | 两者差两个数量级 | 占位代表"正在处理"，正常只存在几十毫秒。若给 24 小时，一旦进程中途被杀，这个键会一直返回"正在处理"，用户重试永远失败且看不出原因 |
| 创建失败怎么办 | 捕获异常后**主动释放占位** | 不释放的话，用户在占位 TTL 内重试都会得到"处理中"—— 一个没有原因也无法自救的失败。"失败就该能重试"是最基本的预期 |
| Redis 挂了怎么办 | **放行**（打日志，不阻断） | 幂等是保护层，不该因为它自己挂了就让用户彻底发不出文章；代价是极端情况下可能重复创建一篇，删掉即可。⚠️ 换成"扣款、下单"这类场景就该反过来**拒绝** —— 那时重复执行的代价远大于暂时不能用 |
| 哪些接口要幂等 | 只有 `POST`（新建） | `PUT`/`DELETE` 本来就是幂等的（执行两次结果和执行一次一样），给它们加幂等键只是徒增复杂度 |
| 不带这个头会怎样 | 行为与以前**完全一样** | 幂等键是可选的，否则对老调用方就是破坏性改动。前端可以平滑升级 |

> **为什么不自制 `@Idempotent` 注解 + AOP 切面**：那要自己处理环绕通知、
> 参数序列化、切面顺序（还得保证在事务外层），坑一个不少，
> 而收益只是"调用方少写一行"。用主流做法更省事也更可靠。
>
> **为什么不用数据库唯一索引兜底**：它需要一条**业务唯一性规则**
> （比如"同一作者不能有同名文章"），而这条规则本身就是错的 ——
> 用户完全可能真想写两篇同名的。用业务约束去实现技术目的，会误伤正常场景。

前端一侧还做了**第一道防线**：按钮保存中会置灰，且 `saveArticle` 开头直接
`if (artSaving.value) return` —— 因为极快连点时，第一次点击设置的状态
还没让浏览器重绘，第二次点击就已经进来了，光靠按钮禁用挡不住。

## 操作审计（谁在什么时候做了什么）

**场景**：某天发现一篇文章被人改成了广告，或者某个账号被悄悄提了权限。
应用日志能告诉你"发生过什么"，但日志是**没有结构的一堆文本行**、
还会被轮转删掉；真正要回答的问题只有四个字：
**谁、何时、对什么、做了什么**。

**做法**：一张 `operation_log` 表 + Spring 的事件机制。

### 记了哪些操作

| 对象 | 操作 | action |
|------|------|--------|
| 文章 | 新建 / 编辑 / 发布下架 / 删除 | `CREATE_ARTICLE`、`UPDATE_ARTICLE`、`UPDATE_ARTICLE_STATUS`、`DELETE_ARTICLE` |
| 用户 | 启用禁用 / 改角色 / 重置密码 / 删除 | `UPDATE_USER_STATUS`、`UPDATE_USER_ROLE`、`RESET_USER_PASSWORD`、`DELETE_USER` |
| 标签 | 新建 / 编辑 / 删除 | `CREATE_TAG`、`UPDATE_TAG`、`DELETE_TAG` |
| 分类 | 新建 / 编辑 / 删除 | `CREATE_CATEGORY`、`UPDATE_CATEGORY`、`DELETE_CATEGORY` |
| 评论 | 审核（通过 / 拒绝）/ 删除 | `UPDATE_COMMENT_STATUS`、`DELETE_COMMENT` |
| 友链 | 新建 / 编辑 / 删除 | `CREATE_LINK`、`UPDATE_LINK`、`DELETE_LINK` |
| 项目 | 新建 / 编辑 / 删除 | `CREATE_PROJECT`、`UPDATE_PROJECT`、`DELETE_PROJECT` |
| 收藏 | 新建 / 编辑 / 删除 | `CREATE_FAVORITE`、`UPDATE_FAVORITE`、`DELETE_FAVORITE` |
| 关于页 | 保存（只有这一种） | `UPDATE_ABOUT`（`target_id` 恒为 1） |
| 音乐 | 新建 / 编辑 / 删除 | `CREATE_MUSIC`、`UPDATE_MUSIC`、`DELETE_MUSIC` |

### 每条记录有哪些字段，为什么

| 字段 | 内容 | 为什么需要它 |
|------|------|-------------|
| `user_id` + `username` | 操作人 | **两个都存是有意的**：id 用来关联查询，username 是**快照**。用户改名或被删之后（本项目的删除会把用户名改写成 `原名#deleted#id`），靠 id 已经追不回"当时是谁"了 |
| `action` / `target_type` / `target_id` | 做了什么、对哪条数据 | 用枚举而不是字符串（见 `OperationAction`）：写错一个字母会出现一个永远统计不到的"分类"，而且不会报错 |
| `detail` | 补充说明 | 例如 `标题=xxx`、`角色 GUEST → ADMIN`。**删除类操作必须在 detail 里留标题 / 用户名快照** —— 删掉之后再回查那条数据，已经查不出它原来叫什么了 |
| `ip` | 来源 IP | 取 `X-Forwarded-For` 的**第一段**。线上请求先过 Nginx，`getRemoteAddr()` 拿到的是 `127.0.0.1`，记它没有意义 |
| `trace_id` | 链路追踪 ID | **这条记录和日志系统之间的那根线**：有了它，就能从"谁改了这篇文章"一路查到"那次请求里每一条 SQL、每一次报错"。没有它，审计表只是一个孤立的记录 |
| `create_time` | 什么时候 | 索引都是带时间列的，因为审计查询**永远带时间范围** |

### 链路：业务代码只加一行

```
Service（写方法）
   │  operationLogRecorder.record(...)      ← 业务侧只有这一行，不关心后面怎么处理
   ▼
OperationLogRecorder
   │  在这一刻（还在请求线程上）把"当前是谁 / 从哪来 / traceId"抄进事件对象
   │  publishEvent(OperationLogEvent)
   ▼
OperationLogListener（@Async + @TransactionalEventListener(AFTER_COMMIT)）
   │  业务事务真的提交了 → 在另一个线程上插库
   ▼
operation_log 表（异步完成，请求早就返回了）
```

| 设计点 | 怎么做的 | 为什么 |
|--------|---------|--------|
| 为什么用事件，而不是在写方法里直接 `insert` | 业务只"喊一声" | 直插有三个问题：① 每加一个写方法都得记得加一行，漏了没人发现；② 审计写入落在业务事务里，插库失败会把用户的正常操作一起搞挂；③ 每次写操作都同步多一次数据库往返 |
| 为什么是 `AFTER_COMMIT` | 事务提交后才记账 | 业务最后回滚了（比如保存时分类不存在），这条记录就不该存在。不这样写，表里会出现一批"从没发生过"的操作 |
| 为什么还要 `fallbackExecution = true` | 没有事务时**立即**执行 | `UserServiceImpl` 的启用禁用 / 改角色 / 重置密码三个方法**没有 `@Transactional`**（这是已知的风格不一致）。默认 `false` 时，在那里发的事件会被**静默丢弃** —— 审计对这三个接口等于没生效，而且不会有任何报错 |
| 那三个方法为什么不顺手补上 `@Transactional` | 刻意不动 | 补上会把**清缓存挪进事务里**：缓存已清、事务还没提交的这段时间里，任何一次读都会把**旧数据**重新塞回缓存，提交之后缓存里就是脏的，一直脏到 TTL 到期。现在的写法（先改库、再清缓存）反而窗口更小 |
| 为什么 `@Async` | 请求不等落库 | 审计是一次额外的数据库写入，没道理让用户的上传 / 保存动作等它。⚠️ 但因为换了线程，用户、IP、traceId 这三个 ThreadLocal 必须在**发布事件的那一刻**就抄进事件对象 —— 这个坑不注意的话，审计表里这三个字段会全是 `null` |
| `@Async` 还需要什么 | `@EnableAsync`（见 `config/AsyncConfig`） | 没有这个注解，`@Async` 就只是一个注释：不报错、也不生效（和 `@EnableCaching` / `@EnableScheduling` 是同一类坑） |
| 线程池满了怎么办 | 有界队列 200 + `CallerRunsPolicy` | 见「配置」章节的说明。核心是**既不抛异常也不丢记录** |
| 落库失败呢 | 监听器自己 `catch` + 打 ERROR 日志 | 监听器是链路的末端（业务早就提交返回了），异常往上抛没有接收方。⚠️ 代价说清楚：数据库真挂了会有审计记录丢失。个人博客可以接受；换到金融场景就该走"消息队列 + 落库确认"来保证不丢 |
| 为什么这张表**没有逻辑删除** | 项目里唯一没有 `deleted` 的业务表 | 审计的价值就是"发生过的事不能被抹掉"。给它加逻辑删除等于给了"把痕迹藏起来"的操作空间；真要清理历史数据，应该是按时间归档这种明确的运维动作 |
| 为什么 `username` 不直接查 `user` 表 | 存快照 | 用户删除后用户名会被改写、行也被标记删除，关联查已经查不出人；审计记录必须**自己带着**当时的信息 |
| 现在有查询接口吗 | **没有** | 目前只写入，还没有后台查询页面 —— 这一点也写在「规划中」里，不做成"看起来有、其实没做完" |
| 为什么**发表评论不记审计** | 只记"审核 / 删除"这类管理动作 | 评论本身就是内容、数量会持续增长。把每条公开评论都塞进审计表，只会让真正要追溯的管理动作被淹没；评论的记录就在 `comment` 表里，需要时按 ID 查即可 |

### ⚠️ 写这类测试时踩到的坑（记下来免得再踩）

`@Transactional` 的测试类**永远不会提交**（跑完就回滚），而落库监听器等的就是那个
`AFTER_COMMIT` —— 两者一叠加，事件永远等不到提交，**一条审计都不会写**。
它的失败长相还很误导：测试红在"查不到审计记录"，看起来像功能没实现，
真正的原因却是"测试环境的事务语义和线上不一样"。

所以 `OperationLogTest` 显式声明了 `Propagation.NOT_SUPPORTED`，让业务真的提交，
代价是数据要自己清理（`@AfterEach` 里按唯一标记物理删除）。
同一类取舍在 `ArticleDetailCacheTest` 的并发用例里也出现过 —— 那次是为了让别的线程看得见数据。

另一条：落库是异步的，所以断言**必须轮询等**（`awaitLog`），
不能"请求一返回就查库说没有"—— 那是假绿；也不能 `sleep` 一个固定值，
给短了随机红、给长了每条用例白等。

## 接口安全约定

几个容易被忽略、但这里都处理了的地方：

| 约定 | 说明 |
|------|------|
| **分页上限** | 文章一页最多 50 条、用户一页最多 100 条（Service 里的业务规则）；`size` 传 0 或负数回落成默认 10；**另有一道全局兜底**（`MybatisPlusConfig.MAX_PAGE_SIZE = 100`）在分页插件层把超过上限的 `size` 挡在 SQL 之前 |
| **排序白名单** | 排序字段只允许白名单内的列，防止把任意字段拼进 `ORDER BY` |
| **列表不返回正文** | 列表接口的 VO 不含 `content`（`longtext`），详情才返回 |
| **出参一律用 VO** | 用户相关接口绝不返回 `User` 实体，避免 `password` / `deleted` 泄露 |
| **双重校验** | DTO 上 `@Valid` 注解 + Service 里业务校验，不依赖单一层 |
| **逻辑删除** | `@TableLogic`，删除是 `deleted = 1`；用户名删除后被改写为 `原名#deleted#id`，释放唯一索引 |
| **自我保护** | 管理员不能禁用/删除/降级自己，防止把自己锁在系统外 |

## 数据库表

| 表 | 说明 | 关键索引 | 建表者 |
|----|------|---------|--------|
| `user` | 用户 | `uk_username` 唯一 | Flyway `V1__init.sql` |
| `category` | 文章分类 | `uk_name` 唯一 | Flyway `V1__init.sql` |
| `article` | 文章（含草稿与浏览量） | `idx_status_top_create(status, is_top, create_time)`、`idx_status_create(status, create_time)`、`idx_deleted_status(deleted, status)`、`idx_category(category_id)` | Flyway `V1` + `V2` + `V3` |
| `operation_log` | 操作审计（谁 / 何时 / 对什么 / 做了什么） | `idx_user_time(user_id, create_time)`、`idx_create_time(create_time)` | Flyway `V4__create_operation_log.sql` |
| `tag` | 文章标签（**没有 `deleted`：物理删除**） | `uk_name` 唯一、`idx_sort` | Flyway `V5__create_tag_tables.sql` |
| `article_tag` | 文章-标签关联（多对多） | 联合主键 `(article_id, tag_id)`、`idx_tag(tag_id)` | Flyway `V5__create_tag_tables.sql` |
| `comment` | 文章评论（含审核状态） | `idx_article_status(article_id, status, create_time)`、`idx_status_create(status, create_time)` | Flyway `V6__create_comment_table.sql` |
| `friend_link` | 友情链接（含显示/隐藏） | `idx_status_sort(status, sort)` | Flyway `V7__create_friend_link_table.sql` |
| `project` | 项目展示（含显示/隐藏、技术栈） | `idx_status_sort(status, sort)` | Flyway `V8__create_project_table.sql` |
| `favorite` | 收藏（含分组、显示/隐藏） | `idx_status_sort(status, sort)` | Flyway `V9__create_favorite_table.sql` |
| `about` | 关于页信息（**全表只有一行，`id` 恒为 1**） | 主键 `id`（不需要别的索引） | Flyway `V10__create_about_table.sql`（含那一行的 INSERT） |
| `music` | 音乐（含歌手 / 封面 / LRC 歌词原文；**迁移刻意不插种子数据**） | `idx_status_sort_id(status, sort, id)`（前台那条查询是 `WHERE status = 1 ORDER BY sort, id`，id 也进索引是为了消掉 filesort，见 V11 注释） | Flyway `V11__create_music_table.sql` |
| `site_setting` | 站点设置（站点名 / 首页公告 / 评论总开关 / 页脚版权与两个备案号（ICP `icp_number`、公安网安 `police_number`）/ 每页文章条数；**全表只有一行，`id` 恒为 1**） | 主键 `id`（不需要别的索引） | Flyway `V12__create_site_setting_table.sql`（含那一行的 INSERT）+ `V13__add_police_number_to_site_setting.sql`（加公安网安备案号列，**刻意不写种子值**） |
| `flyway_schema_history` | Flyway 自己的迁移记录表 | —— | Flyway 自动创建 |

> `article` 上四个索引看着多，其实每个都有明确的归属，缺了会有可量化的退化：
> `idx_status_top_create` 管"取 10 行数据"、`idx_deleted_status` 管"数一共有多少条"、
> `idx_status_create` 管"按发布时间排序"、`idx_category` 管"按分类筛"。
> 哪一条是谁的、为什么不能互相替代，见「性能」章节（那里有逐条的实测数字）。
>
> 表结构**不要手动改**。需要改表就新增一个迁移脚本（`V5__xxx.sql`），
> 让开发库、测试库、生产库走同一条路径。
>
> `operation_log` 是**唯一没有 `deleted` 字段**的业务表，这是刻意的：
> 审计的价值就在于"发生过的事不能被抹掉"，给它加上逻辑删除等于
> 给了"把痕迹藏起来"的操作空间（理由写在 `V4__create_operation_log.sql` 里）。
>
> `tag` 与 `article_tag` **也没有 `deleted`**，但理由完全不同：它们是**物理删除**。
> 逻辑删除与 `tag.uk_name` 这个唯一索引天生打架 —— 删掉的行仍占着名字，
> 于是"删了标签 A 再建同名 A"会撞 Duplicate entry，而带 `@TableLogic` 的查重语句
> 又看不见那行已删除的数据（这个坑在 `user` 表上踩过一次，当时的解法是删除时改写用户名）。
> 标签不值得付那个代价（名字会被污染成 `技术#deleted#7`），所以选择真删；
> **删除这件事仍然有痕迹**：审计表里有一条 `DELETE_TAG`。
> 完整推导写在 `V5__create_tag_tables.sql` 的注释里。
>
> `comment` 则**又回到逻辑删除**了 —— 判断标准只有一条：**这张表上有没有唯一索引**。
> 评论没有任何唯一索引（同一个人发两条一模一样的话是合法的），
> 所以"逻辑删除 + 唯一索引"那个坑不存在，可以安心保留"删错了能恢复"的能力。
>
> `friend_link`（友链，V7）同样没有任何唯一索引 —— 站点名不是身份标识
> （两个站点都可能叫"某某的博客"），而给 `url` 加唯一索引会误伤"同一个站点换域名 /
> 带不带 www 想各留一条"这类正当需求，还要背上"逻辑删除 + 唯一索引"那个坑，
> 所以它**不加唯一索引、用逻辑删除**，属于 `comment` 那一类。
>
> `favorite`（收藏，V9）同样如此。它有一个**容易搞混的字段**：`category` 是
> **自由文本的分组名**（工具 / 文章 / 视频），**不是 `category` 表的 id** ——
> 那张表是"博客文章的栏目"（有唯一索引、删之前要数文章），
> 若收藏去引用它，删掉一个只被收藏用到的分组之后，收藏的分组名就变成查不到的 id。
> 完整推导写在 `V9__create_favorite_table.sql` 里。
>
> `about`（关于页，V10）是**另一类**：它只有一行、没有 `deleted`、没有 `create_time`。
> 它是全站唯一一张"删掉某一行的需求不存在"的表（不要了就清空字段），
> 所以不需要"逻辑删除"这个概念本身；代价与兜底是
> **缺行时读返回空壳、保存时自愈写回**（见 `AboutServiceImpl` 与 `AboutTest` ⑫）。
>
> `project`（项目，V8）与 `friend_link` 完全同类：没有唯一索引（项目名不需要唯一）、
> 用逻辑删除。它的 `tech`（技术栈）是**逗号分隔的字符串而不是关联表** ——
> 纯展示字段，没有"按技术栈筛选 / 统计"的需求，不值得为它写一套覆盖式重写关联的逻辑
> （理由写在 V8 迁移脚本里）。
>
> `music`（音乐，V11）同样没有唯一索引（曲名当然可以重复）、用逻辑删除。
> 它值得单独记一笔的是**两件容易误判的事**：
> ① **迁移里不插任何种子数据** —— 库里一首都没有是正常状态，前端在列表为空时
>    会回落成内置的那一首（`static-media/bg-music.mp3`）；看到空表不要去"补数据"
> ② **删歌连不连磁盘上的 mp3，是 2026-09 变过一次的**：现在是"先查三处引用
>    （别的曲目 / 文章正文与封面 / 文章附件），都为零才删"；外链地址不碰。
>    旧行为是只标记数据库行、文件留着不删（靠人工按目录清）。
>    为什么改、以及为什么"引用检查"要覆盖文章那两张表，见 `MusicServiceImpl.delete` 的长注释
> 索引比 V7–V9 多带一个 `id`：那条查询的排序是 `sort ASC, id ASC` 两段，
> 把 id 放进索引才能完全消掉 filesort（理由写在 V11 迁移脚本里）。
>
> `site_setting`（站点设置，V12；公安网安备案号列是 V13 加的）与 `about` 属于**同一类**（只有一行、没有 `deleted`、
> 没有 `create_time`、缺行时读给默认值 + 保存自愈写回），但**刻意分成两张表**：
> `about` 是"站长是谁"这类对外展示的内容，几个月不动一次；
> 站点设置是"站点怎么运行"的配置，上线当天就可能改好几轮。
> 分表的实际收益是**接口形状与缓存范围都清楚**：改一个"每页条数"不该让关于页的缓存重建，
> 也不该让"关于页"的读接口里塞进一堆与站长无关的开关。
>
> 它有三处值得单独记一笔：
> ① **`page_size` 的上限跟着文章接口走** —— 校验上写的是 `ArticleQuery.MAX_PAGE_SIZE`
>    这个常量本身，而不是另写一个 50。接口那边超出会**静默夹到 50**，
>    所以设置里允许填更大的话，站长会得到一个"保存成功但首页还是只列 50 篇"的开关，
>    而且没有任何报错（属于最难查的一类问题）
> ② **种子值等于前端原本写死的那些值**（站点名 `亿轨星途`、评论开着、每页 **12** 条，
>    公告 / 两个备案号 / 版权留空）—— 目的是让这个功能上线本身**不改变站点外观**：
>    部署完打开首页应当"什么都没变，只是以后可以在后台改"。
>    ⚠️ 其中 `page_size` 的 12 **不是** `ArticleQuery.DEFAULT_PAGE_SIZE`（10）：
>    前者是"前端首页此刻真正在用几篇"（3 列瀑布流 4 行），后者是"调用方没传 size 时
>    接口用什么"—— 两件事。种子值用错的话，这个功能一上线首页就会从 12 篇变成 10 篇，
>    而"不改变外观"正是种子值存在的全部意义。
>    ⚠️ 尤其**不预填备案号**：它属于站点主体的备案材料，该由站长在后台自己填。
>    V13 加公安网安备案号（`police_number`）时同样**没有写任何种子值**：
>    两个备案号是**两套独立的备案体系**（工信部 vs 公安机关），编号形态与
>    **链接规则**都不同（ICP 链 `beian.miit.gov.cn`，公安链 `beian.mps.gov.cn` 的
>    备案查询页并挂平台图标），所以各占一列、各自留 NULL = 页脚不渲染那一行。
>    理由写在 V13 脚本里，实现侧的对应处是 `SettingServiceImpl.normalizeOptional`
>    与 `toVO`（两条链路都带上 `policeNumber`）
> ③ **`comment_enabled` 在库里是 `tinyint` 0/1，而接口上是布尔** ——
>    库里用 0/1 便于用 SQL 排查（`WHERE comment_enabled = 0`），
>    接口给布尔是因为前端那个控件就是 `el-switch`。转换只发生在 `SettingServiceImpl` 一处，
>    两个方向各有一条断言（转换写反的表现是"关了评论却还开着"，不会报错只会做错事）

## 性能

### 前台列表的索引优化（183ms → 0.11ms）

前台首页那条 SQL 长这样（`deleted = 0` 是 MyBatis-Plus 逻辑删除插件自动加的）：

```sql
SELECT ... FROM article
WHERE deleted = 0 AND status = 1
ORDER BY is_top DESC, create_time DESC
LIMIT 0, 10;
```

在 **20 万行**（16 万已发布、2000 置顶）的数据上实测，`V1` 只有
`idx_status_create(status, create_time)` 时的执行计划是：

```
-> Limit: 10 row(s)  (cost=3414 rows=10) (actual time=183..183 rows=10 loops=1)
    -> Sort: is_top DESC, create_time DESC, limit input to 10 row(s) per chunk  (actual time=183..183 rows=10)
        -> Filter: (deleted = 0)  (cost=3414 rows=98510) (actual time=0.0677..163 rows=160000)
            -> Index lookup on article using idx_status_create (status=1)  (actual time=0.0593..155 rows=160000)
```

**读法**：索引只用到第一列 `status` —— 因为 `ORDER BY` 里出现了索引中没有的 `is_top`，
索引的有序性就断了。于是 MySQL 把 `status = 1` 的 **16 万行全部取出来**，
排一次序（`Using filesort`），最后只留 10 行。
为了 10 行结果干了 16 万行的活，慢的不是"取数据"，而是"取出来又扔掉"。

`V2` 加的索引 `idx_status_top_create(status, is_top, create_time)` 把这段补齐：

```
-> Limit: 10 row(s)  (cost=3417 rows=10) (actual time=0.107..0.11 rows=10 loops=1)
    -> Filter: (deleted = 0)  (cost=3417 rows=9883) (actual time=0.105..0.108 rows=10)
        -> Index lookup on article using idx_status_top_create (status=1) (reverse)  (actual time=0.0835..0.0857 rows=10)
```

| | 优化前 | 优化后 |
|---|---|---|
| 执行计划 | `ref` + **Using filesort** | `ref` + **Backward index scan** |
| 预估扫描行数 | 98,510 | 98,830（但**实际只读了 10 行**就停） |
| 实际耗时 | **183 ms** | **0.11 ms** |

关键在 `Backward index scan`：索引里 `status` 定住之后，`is_top`、`create_time`
本来就是按顺序存的，而 `ORDER BY` 恰好是同方向的 `DESC, DESC`，
所以 MySQL 可以从索引尾部**倒着扫、取够 10 行就停**——排序整个消失了。

**为什么老索引 `idx_status_create` 必须留着（差点被当成重复索引删掉）**

看到 `(status, is_top, create_time)` 很容易觉得 `(status, create_time)` 是它的前缀、可以删。
不是：复合索引只能用**最左前缀**，`status` 后面跟的是 `is_top`，
所以它撑不住"按发布时间排序"那条路径。用户在前台点"按发布时间排序"时 SQL 变成
`ORDER BY create_time DESC`，实测走的正是 `idx_status_create`（0.068ms）。
**两个索引服务两条不同的排序路径，谁也不能替谁。**

**两个"想过但量完之后否掉"的方案**（记下来是因为"不加"也需要理由）

| 候选 | 想法 | 实测结果 | 结论 |
|---|---|---|---|
| `(status, deleted, is_top, create_time)` | `deleted = 0` 也是等值条件，塞进去能少一次回表 | 也能消掉 filesort，但 `type` 从 `ref` 退化成 `range`，估算代价 3417 → 122555，实测 **0.11ms → 0.30ms** | 否。`deleted` 只有 0/1 两个值、选择性极差，只让索引变宽 |
| `(status, category_id, is_top, create_time)` | 前端按分类筛选时也快 | 该查询 **0.12ms → 0.072ms** | 否。两者都在 1 毫秒以内，用户感知不到；而每多一个二级索引，每次写入都要多维护一棵 B+ 树 |

> **怎么复现这些数字**：`docs/perf/explain-article-list.sql` 是一个自包含脚本，
> 自己建一个一次性的 `ygt_bench` 库、造 20 万行、把上面每个方案都量一遍、最后删库。
> 跑法见文件头注释。**不要**在项目自己的库上跑（它会 DROP DATABASE）。

**索引有了，怎么保证它不会被人悄悄删掉**

`ArticleIndexTest` 把"索引应当长什么样"钉成了几条断言：
V2 / V3 迁移确实执行成功（防"迁移文件写了但没生效"——这个项目真的踩过）、
每个索引的列与列顺序正确、老索引还在、三条真实 SQL 的 `possible_keys` 里
都能看到对应索引。

> 这里刻意**没有**断言"不能出现 filesort"：Testcontainers 里的表几乎是空的，
> 数据量小的时候优化器会**理性地**选择全表扫描，那种断言必然时绿时红。
> 一个会随机变红的用例比没有用例更糟，因为它会让人开始无视红灯。
> 耗时对比放在上面那个可复现脚本里，不放测试里。

### 分页的 COUNT 才是真正的瓶颈（V3）

上面把"取 10 行数据"优化到 0.09ms 之后，接口并没有变快 ——
压测只有 **39 req/s、平均延迟 255ms**。数据查询已经那么快了，时间花在哪？

把一次分页请求拆开量（10 万行，8 万已发布）：

| 一次分页发的 SQL | 耗时 | 占比 |
|---|---|---|
| ① `SELECT COUNT(*) WHERE deleted = 0 AND status = 1`（分页组件要的 total） | **74.1 ms** | **99.8%** |
| ② `SELECT ... ORDER BY is_top DESC, create_time DESC LIMIT 0,10` | 0.09 ms | 0.1% |
| ③ 分类名查询（每页一次，已避免 N+1） | 0.05 ms | 0.1% |

**"数一共有多少条"吃掉了几乎全部时间**，而那个数字用户根本不在意 ——
它只是分页组件必须知道的一个值。

为什么数得那么慢：`idx_status_create(status, create_time)` 能定位到 8 万条，
但里面**没有 `deleted` 这一列**，而每条 SQL 都被逻辑删除插件加上了 `deleted = 0`，
于是这 8 万条**每一条都要回表**确认一次：

```
-> Aggregate: count(0)  (actual time=74.1..74.1 rows=1)
    -> Filter: (article.deleted = 0)  (actual time=0.129..71.5 rows=80002)     ← 这一步在回表
        -> Index lookup on article using idx_status_create (status=1)  (rows=80002)
```

`V3` 加索引 `idx_deleted_status(deleted, status)`，让"数数"完全在索引里完成
（**Covering index** = 要用的列全在索引里，不用回表）：

```
-> Aggregate: count(0)  (actual time=21.7..21.7 rows=1)
    -> Covering index lookup on article using idx_deleted_status (deleted=0, status=1)  (rows=80002)
```

**74.1ms → 21.4ms（3.5 倍）**，而且完全不影响数据查询（仍是 0.09ms）。

| | 优化前 | 优化后 |
|---|---|---|
| COUNT 执行计划 | `Index lookup` + **回表 8 万次** | **Covering index lookup**（不回表） |
| COUNT 耗时 | 74.1 ms | **21.4 ms** |

**为什么 `deleted` 放前面而不是 `(status, deleted)`**：两种顺序的 COUNT 一样快
（实测 21.7ms vs 21.4ms），但 `(deleted, status)` 还能服务后台列表那条
`COUNT(*) WHERE deleted = 0`；`(status, deleted)` 因为最左前缀是 `status`，对那种查询用不上。
**既然一样快，就选能多管一种查询的顺序。**

> 21ms 离"快"还有距离，因为 COUNT 天然是 O(已发布文章数) 的。
> 把它降到 0 要靠缓存总数（要做失效一致性），那属于缓存那批工作（路线图 M2）。
> 这里先把 O(n) 的常数项压下去，并把瓶颈本身记清楚。

### 压测：用 wrk 量出来的对比

工具是 `wrk`，跑在容器里打本机后端（Windows 上不用装任何东西）：

```bash
docker run --rm skandyla/wrk -t4 -c50 -d20s --latency "http://host.docker.internal:8082/article/page"
```

**① 覆盖索引的效果**（连接池 10，10 并发，10 万行数据）

| | 只有 `idx_status_create` | 加了 `idx_deleted_status` |
|---|---|---|
| QPS | 38.94 | **607.61 / 569.44**（两次） |
| 平均延迟 | 255.62 ms | **19.92 / 25.4 ms** |
| P50 | 255.57 ms | 14.89 / 17.18 ms |
| 20 秒完成请求数 | 780 | 12166 / 11400 |

**约 15 倍吞吐、约 13 倍延迟改善。** 注意瓶颈自始至终不在"取 10 行数据"上，
而在那条 COUNT 上 —— 这就是"优化要看数据、不要凭直觉"的具体例子。

**② 连接池大小的影响**（都有覆盖索引，50 并发）

| 池大小 | QPS | P50 | P99 |
|---|---|---|---|
| 10（默认） | 623 / 666 / 657 | 72 / 68 / 71 ms | 135 / 516 / 107 ms |
| 20 | **872 / 872** | **52 / 50 ms** | 521 / 77 ms |

同一行有多个数字 = 同一配置重复跑了几次。可以看出：

- **QPS 很稳定**（池 20 两次都是 872），所以吞吐这个指标可信；
- **P99 很不稳定**（同一配置能跑出 107ms 也能跑出 516ms）——
  单次 20 秒的压测里，一次 GC 或 Docker Desktop 的一次抖动就足以污染尾延迟。
  所以这张表里**不拿 P99 下结论**，只看 QPS 与 P50。
- 池从 10 加到 20，吞吐涨了约 34%，P50 也降了约 28%。
  那为什么不默认用 20？因为**池大小应该跟着机器核数走**：
  HikariCP 官方公式是 `连接数 ≈ 核数 × 2 + 磁盘数`。
  本机这台开发机容器里有 **32 核**（实测 `docker info` 的 NCPU=32），
  按公式该远不止 20；而线上那台 ECS 大概率是 2~4 核，公式算下来是 5~9，
  取 10 已经偏宽松。所以做成 `DB_POOL_SIZE` 可配置（见「配置」章节），
  换机器改环境变量即可，不用改代码。


### 慢查询日志

MySQL 官方镜像的默认值是 `slow_query_log = OFF`、`long_query_time = 10` ——
**等于没有慢查询记录**。上面那条 183ms 的 SQL，在默认配置下永远不可能出现在任何日志里。
所以两套 compose 都显式开了这个日志：

```yaml
command:
  - mysqld
  - --slow-query-log=ON
  - --slow-query-log-file=/var/lib/mysql/slow.log   # 写进数据目录，容器重建也不丢
  - --long-query-time=0.1                           # 100ms
  - --log-output=FILE
```

`0.1` 这个阈值是按本项目实测值反推的：优化后的列表查询 0.11ms、优化前 183ms，
定在 100ms 等于"比应有水平慢三个数量级就报警"。官方默认的 10 秒，
意味着用户早就关掉页面了你才知道。

看日志：

```bash
docker exec yiguixingtu-mysql cat /var/lib/mysql/slow.log
```

每条记录都会带 `Query_time` / `Lock_time` / `Rows_examined` / `Rows_sent`，
其中 **`Rows_examined` 远大于 `Rows_sent`** 就是最典型的"索引没排上用场"信号
（上面的优化前正是这个形状：`Rows_examined=160000`，`Rows_sent=10`）。

> ⚠️ **为什么写 `command` 参数，而不是挂一个 `.cnf` 配置文件**（踩过的坑）
>
> 最初的写法是写一个 `slow.cnf` 再 bind mount 到 `/etc/mysql/conf.d/`
> （官方镜像的 `/etc/my.cnf` 里有 `!includedir`，本来就是留给用户放自定义配置的），
> 结果配置**完全没有生效**，启动日志里只有一行极易忽略的警告：
>
> ```
> [Warning] World-writable config file '/etc/mysql/conf.d/slow.cnf' is ignored.
> ```
>
> MySQL 出于安全考虑会**主动忽略权限为 777 的配置文件**（能被别人改的配置 =
> 能被别人改数据库行为）。而 Windows 文件系统没有 POSIX 权限概念，
> bind mount 进去的文件一律是 `-rwxrwxrwx`，于是必然被忽略。
> 这个坑最阴的地方是：**Linux 服务器上跑得好好的（文件是 644），
> 只有本机 Windows 开发环境静默失效**——典型的"线上对、本地错"，而且一点声音都没有。
> 换成命令行参数后，权限语义完全不参与，两个平台行为一致。

## 可观测

### 暴露了哪些端点

| 端点 | 谁能访问 | 用途 |
|------|---------|------|
| `/actuator/health` | 所有人（匿名） | 容器健康检查、负载均衡探活。只返回 `{"status":"UP"}`，不带数据库/Redis 细节 |
| `/actuator/prometheus` | 匿名可达，但**只绑在 127.0.0.1**，且 Nginx 把 `/api/actuator/` 挡成 404 | Prometheus 抓取指标 |

**其余端点一律没开**（`beans` / `env` / `mappings` / `heapdump` …）。
它们会把内部结构甚至内存内容吐出来，对一个公网服务来说没必要。

`MetricsEndpointTest` 里有一条用例专门验证这件事 —— 而且它写得比看起来麻烦：

> 📌 这条用例踩了两次有意思的坑，都记在测试代码注释里：
> ① 匿名请求 `/actuator/env` 得到的是 **401** 而不是 404 ——
> Spring Security 在**路由之前**就拦掉了，请求根本没走到"端点存不存在"那一步。
> 401 同时对应"端点没开"和"端点开着只是要登录"两种完全不同的情况，断言它等于没测。
> 改成带管理员 token 请求，才能走到派发这一步。
>
> ② 带上 token 之后，得到的也不是 404，而是 **HTTP 200 + `{"code":500}`** ——
> 因为「统一返回」那条约定把所有异常都转成了 200 + body.code，
> "找不到端点"抛出的 `NoResourceFoundException` 也落进了兜底处理器。
> **所以在这个项目里，HTTP 状态码不能用来判断端点存不存在。**
> 最终断言改成"响应体里没有 env/beans 的数据"—— 它跟错误码约定解耦，
> 将来真把状态码统一了（路线图 5.6），这条用例也不用改。

### 框架自带 vs 我们自己埋的指标

装上 `micrometer-registry-prometheus` 之后，下面这些**不用写一行代码**就有：

| 指标 | 看什么 |
|------|--------|
| `jvm_memory_used_bytes` / `jvm_gc_*` | JVM 内存与 GC —— 判断"是不是内存不够导致卡" |
| `http_server_requests_seconds` | 每个接口的请求量与耗时分布（能算 P50/P99） |
| `hikaricp_connections_*` | 数据库连接池的活跃/空闲连接 |
| `tomcat_threads_*` | Web 容器线程池是否被占满 |

但框架指标只知道"技术层面发生了什么"。它看不到业务上的异常，举个真实的例子：

> 浏览量落库失败时 —— 详情页照样返回 200（`http_server_requests` 一切正常）、
> JVM 正常、连接池正常。可 Redis 里的增量正在悄悄堆积，
> 数据库里的浏览量和真实访问量越差越远。**技术指标全绿，业务已经出事了。**

所以另外埋了 5 个业务指标（都在 `BusinessMetrics` 里集中定义）：

| 指标 | 类型 | 含义 |
|------|------|------|
| `yiguixingtu_article_views_flushed_total` | Counter | 累计落库的浏览量增量（次） |
| `yiguixingtu_article_views_synced_articles_total` | Counter | 累计落库涉及的文章数（篇） |
| `yiguixingtu_article_views_pending` | Gauge | 最近一次同步时待落库的文章数 —— 持续不为 0 说明同步在堆积 |
| `yiguixingtu_article_views_sync_seconds` | Timer | 一次批量落库的耗时 |
| `yiguixingtu_auth_login_total` | Counter | 登录次数，标签 `result=success\|failure` |
| `yiguixingtu_auth_token_revoked_total` | Counter | 被拉黑的 token 数（登出**真正生效**的次数） |

> Micrometer 里的点号 `.` 在 Prometheus 输出里会变成下划线 `_`，
> `..._total` 后缀是 Prometheus 给 Counter 加的，所以对上表时注意名字的这点差异。

**三个刻意的设计决定：**

1. **标签里绝不放会无限增长的值。**
   登录指标只打了 `result` 和 `reason`（异常类名），
   **没有**打用户名 —— 用户名做成标签的话，序列数量随用户数无限增长，
   内存和 Prometheus 存储都会被拖垮（"标签基数爆炸"）；
   而且用户名属于个人信息，监控系统不该收到它。用户名只进日志。
   同理，HTTP 指标的 `uri` 标签由框架替换成 `/article/{id}` 这样的模板，
   并且默认上限 100 个不同取值（`management.metrics.web.server.max-uri-tags`）。

2. **"待落库数量"用内存里的数字，而不是去问 Redis。**
   Gauge 的取值发生在 Prometheus 来抓的那一刻，最直白的实现是那时去 Redis 数一下 key 个数
   ——但那要跑一条 `KEYS`，而 `KEYS` 会阻塞 Redis（它扫整个 keyspace）。
   让监控采集去阻塞生产 Redis 是本末倒置。所以改成由定时任务在同步时把值记下来，
   采集时只读一个内存数字。代价是它滞后一个同步周期，对"有没有在堆积"这个判断完全够用。

3. **登录统计靠监听 Spring Security 自己的事件，而不是在登录接口里插桩。**
   `AuthenticationSuccessEvent` / `AbstractAuthenticationFailureEvent` 是框架本来就发的，
   监听它们比在 Controller 里写 try/catch 更不容易漏（写验证码登录、
   或别处调用 `AuthenticationManager` 时那份统计依然有效）。
   监听的是**抽象基类**而不是一个个具体事件 —— 将来 Spring 新增一种失败事件也不会漏统计。
   ⚠️ 这条依赖"Boot 自动配置了 `AuthenticationEventPublisher`"，
   而"应该会"不等于"真的会"，所以有用例真的登录失败一次、断言计数涨了。

> **为什么没有"缓存命中率"这条**：它属于缓存那批工作（路线图 M2），
> 而缓存目前是暂停状态（有一个复现不稳定的现象还没查清，见路线图）。
> 指标要在功能落地之后再埋，否则就是一个永远为 0 的假指标 —— 那比没有更糟。

### 谁来抓、怎么抓

```yaml
# prometheus.yml
scrape_configs:
  - job_name: yiguixingtu
    metrics_path: /actuator/prometheus
    static_configs:
      # 后台端口只绑在 127.0.0.1，所以 Prometheus 和它装在同一台机器上；
      # 如果 Prometheus 是容器、和 backend 在同一个 compose 网络里，
      # 这里可以写 http://backend:8082
      - targets: ['127.0.0.1:8082']
```

验证抓得到：

```bash
curl -s http://127.0.0.1:8082/actuator/prometheus | head -20
```

> ⚠️ **`/actuator/prometheus` 是匿名可达的，它的安全护栏不在代码里，在部署上：**
>
> | 护栏 | 在哪 |
> |------|------|
> | 后端端口只绑 `127.0.0.1` | `docker-compose.prod.yaml` 里的 `"127.0.0.1:8082:8082"` |
> | **Nginx 显式把 `/api/actuator/` 返回 404** | README「Nginx 反向代理」那段里的 `location /api/actuator/ { return 404; }` |
> | 云服务器安全组不开 8082 | 阿里云控制台 |
>
> ⚠️ **中间那一行是【必须的】，而且它的原因很容易想反——这里原来就写错了，留个记录：**
>   原文写的是"Nginx 只反代 `/api/`，没有 `/actuator` 的 location"，
>   并把它当成"所以从域名访问不到"。**这个推论是反的**：
>   `location /api/ { proxy_pass http://127.0.0.1:8082/; }` 结尾那个 `/`
>   表示"把匹配到的 `/api/` 前缀替换成 `/`"，也就是把剩下的部分拼到后端根路径上。
>   于是 `/api/actuator/prometheus` 正好映射到后端那个匿名放行的 `/actuator/prometheus`。
>   请求走的一直是 `/api/` 那一条，它根本不需要任何 `/actuator` 的 location。
>   ⇒ 也就是说：**上面这张表原来列的三道护栏，实际只有两道在起作用。**
>     现在补上第三道，并让 `ActuatorExposureContractTest` 盯着它别再被删掉
>     （那个测试只能证明"文档里有这一段"，服务器上真的配了没有，
>      仍然要靠上线核对清单第 9 条实际 curl 一次）。
>
> 为什么不用 JWT 保护它：抓取指标的是 Prometheus，它没有也不该有我们的 token。
> 要求登录的实际结果只有两种 —— 要么抓不到，要么为了能抓配一个长期不过期的 token，
> 那比放行更糟。所以选择"用网络位置限制"，并把这件事写进上线核对清单。

## 安全

### 安全响应头

这些头是给**浏览器**看的，用来约束浏览器怎么对待我们的响应。
本项目虽是前后端分离的接口服务，但同一台机器上挂着前端页面，
而且很可能有人把接口地址直接贴进浏览器打开 —— 成本几乎为零，却能挡掉几类常见网页攻击。

| 响应头 | 值 | 防的是什么 |
|--------|-----|-----------|
| `X-Content-Type-Options` | `nosniff` | 浏览器"猜"内容类型后把上传的文本当 HTML 执行（上传功能最典型的连带 XSS 风险） |
| `X-Frame-Options` | `DENY` | 点击劫持：攻击者用 iframe 把自己的页面套在我们的界面之上 |
| `Referrer-Policy` | `no-referrer` | 接口地址（可能带 `?keyword=` 这类参数）跟着 `Referer` 泄漏给第三方站点 |
| `Strict-Transport-Security` | `max-age=31536000; includeSubDomains` | HTTPS 降级劫持（把 HTTPS 请求劫持成 HTTP 再窃听） |

> ⚠️ **本地看不到 HSTS 是正常的**：浏览器只在 **HTTPS 响应**上认这个头。
> 本地开发是 `http://localhost`，所以用 curl 看不到它 —— 不是配置没生效。
> 测试里用 `.secure(true)` 模拟 HTTPS 请求来验证它，同时也断言
> **明文请求上不该出现它**（在 HTTP 上发 HSTS 没有意义，还容易让人误以为"HTTP 也安全了"）。
>
> ⚠️ **HSTS 的 `max-age` 不要随便加大，也不要加 `preload`**：一旦浏览器记住了，
> 在到期前**无法撤销** —— 如果域名将来不支持 HTTPS，站点就彻底打不开了。

**7 个用例逐个头断言**，其中特意覆盖了两条容易被漏的路径：
**错误响应（401）** 和 **上传文件的响应** —— 安全头最常漏的就是出错的那条路径，
因为大家往往只测了正常返回。

### 生产环境的凭据治理

| 项 | 怎么做的 |
|----|---------|
| 数据库账号 | compose 里单独建 `yiguixingtu` 业务账号，**不让应用用 root**（最小权限） |
| Redis | 设 `requirepass`，**内网也设** —— 默认无密码时，只要容器网络可达就等于公开 |
| JWT 密钥 | 必须用环境变量覆盖，且 prod 配置里**不给默认值**（忘配就启动失败） |
| 上传的文件 | 图片、音频与文章附件都存在服务器磁盘上（具名卷 uploads_data），不用对象存储；/uploads/** 只放行 GET（图片在 cover/、音频在 music/、附件在 attachment/，同一个映射覆盖子目录）；**附件响应强制下载 + nosniff**（uploads/attachment/ 里的东西绝不能被浏览器当页面执行） |
| 依赖漏洞 | `.github/dependabot.yml` 每周检查 maven 与 github-actions 依赖，自动开 PR |
| 接口文档 | prod 下 Swagger 真的关掉（不只设开关，还从放行名单里移除，见「配置」章节） |

## 部署（Docker Compose + 阿里云 ECS）

### 部署形态

```
                      ┌──────────────────────── 一台 ECS ────────────────────────┐
   浏览器 ──https──▶  │  Nginx（宿主机的 80/443）                                │
                      │    ├─ /      → 前端（Nuxt 容器 3000）                    │
                      │    └─ /api/  → 后端（Spring Boot 容器 8082）             │
                      │                                                          │
                      │  docker compose 内网（外网连不到）                        │
                      │    frontend ──▶ backend:8082                             │
                      │                 backend ──▶ mysql:3306                   │
                      │                         └──▶ redis:6379                  │
                      └──────────────────────────────────────────────────────────┘
                                    封面图 ──▶ 服务器磁盘（具名卷 uploads_data，随容器重建不丢）
```

**四个容器都由 `docker-compose.prod.yaml` 管理**，和本地开发的 `docker-compose.yaml`
是两个文件 —— 生产环境**不把 MySQL / Redis 的端口映射到宿主机**，
它们只在 compose 内网里可达（这是防拖库最基本的一条）。

> **前端也在这个 compose 里**（`frontend` 服务会去构建前端仓库的镜像）。
> 默认假设两个仓库是**同级目录**（如 `/srv/yiguixingtu` 与 `/srv/yiguixingtu-web`）；
> 不是的话在 `.env` 里写 `FRONTEND_DIR=/你的/实际/路径`。
>
> 这么做是为了"一次 `up` 起全栈"：前后端在同一个 compose 网络里，
> 前端用服务名 `backend` 就能访问后端，不用手写网络配置。
> 代价是上线时两个仓库要放在一起 —— 对一个单机博客来说这个取舍是划算的。

#### ⚠️ 内存预算：必须给容器设上限，而且基数要用【实测值】而不是标称值

`docker-compose.prod.yaml` 里给四个服务都写了 `mem_limit`。**这不是"性能调优"，
而是"能不能稳定跑起来"的前提** —— 原因在 JVM 的容器感知只认 cgroup 限额：

- `Dockerfile` 里**不再写任何堆参数**（镜像保持中立）；堆的大小由 compose 的
  `JAVA_TOOL_OPTIONS: -XX:MaxRAMPercentage=60` 决定，而这个百分比乘的是
  **容器内存上限**（`mem_limit`）。
- **不设** `mem_limit` 时容器没有限额，JVM 看到的是**宿主机**的那几个 G，
  于是算出"堆可以到 1.2G"。一台 2G 的机器上，后端 + MySQL + Redis + Node
  的峰值必然超过物理内存，最后由内核的 OOM killer 出手 ——
  而它挑的通常是 RSS 最大的那个：**MySQL**。
  症状是"数据库莫名重启、连接全断"，应用日志里什么都看不到（要看 `dmesg`）。
- 设了上限之后，越界只会杀掉**越界的那一个**容器，其余服务不受影响，
  `restart: unless-stopped` 再把它拉回来 —— 故障范围被限制在一个容器里。

| 服务 | 上限 | 预估实占 | 主要构成 |
|---|---|---|---|
| `mysql` | 384m | ~280MB | 缓冲池 128M + 连接与线程 |
| `backend` | 640m | ~500MB | 堆 ~384M + 元空间 160M + 直接内存 48M |
| `frontend` | 240m | ~180MB | Node SSR 的常驻内存 |
| `redis` | 96m | ~20MB | `maxmemory 64mb`（实际只用到十几 MB） |
| **合计** | **1360m** | | 余 ~278MB 给系统 + Docker + Nginx |

> ⚠️⚠️ **基数是 1638MB，不是 2048MB —— 这一条是整套参数的地基**
>
> 实例标称"2 核 2G"，但服务器上 `free -h` 显示的是 **`Mem: 1.6Gi`（≈1638MB）**：
> 内核并不拥有标称的全部物理内存（虚拟化层与内核自身会占掉一部分）。
>
> 第一版按标称的 2048 算，得出"四个容器上限之和 1728m、余 320m"这份表 ——
> **看着合理，实际上上限之和已经超过物理内存 90MB**。而"上限之和必须给系统留余量"
> 这条不变式恰恰是用来防这件事的：基数用错，它就成了一条**永远会通过的假测试**。
>
> ⇒ **内存预算必须按实测值算**。换机器时先跑 `free -m`，再回来改这一节。

**这张表里哪些是实测过的、哪些不是**（分开说，免得把估算当实测）：

| 服务 | 实测情况 |
|---|---|
| `mysql` | ✅ 空数据目录初始化成功（mysql **8.4.11**），`OOMKilled=false`；`performance_schema=0`、`innodb_buffer_pool_size=128M`、`max_connections=50` 三项均生效。⚠️ 但那次是在**更大的上限**下量的，收紧到 384m 之后没有再量过 |
| `redis` | ✅ 启动正常；`maxmemory` 与 `maxmemory-policy` 都生效 |
| `backend` | ✅ 以 `--memory` 实测过"堆正是上限的 60%、元空间与直接内存上限都生效、`gc.log` 能写出来" |
| `frontend` | ❌ **唯一没有实测的一项**（没在本机起 SSR 容器量过，~180MB 是估算） |

> ⚠️ **MySQL 的缓冲池会被静默向上取整**（这一条是实测踩出来的）：
> MySQL 会把 `innodb_buffer_pool_size` 取整到 `innodb_buffer_pool_chunk_size`
> 的整数倍，而那个 chunk 默认就是 **128M**。所以
> `--innodb-buffer-pool-size=192M` 实际生效的是 **256M**（实测
> `@@innodb_buffer_pool_size = 268435456`）——白占 64M，而且不报错、不警告。
> 写 `128M` 才实测得到 128M。**取值应当是 128M 的整数倍**
> （除非连 chunk size 一起改），测试里有一条用例盯着这件事。

> ⚠️ **后端那三个硬上限只留了约 48M 给代码缓存 / 线程栈 / GC 结构**：
> 384（堆）+ 160（元空间）+ 48（直接内存）= 592M < 640M。余量是**偏薄**的
> （JIT 代码缓存实测通常就有 40~50M）。真正兜住的是"这些上限在实际负载下都够用"
> ——堆实际占 300M 上下、元空间 110M 上下。若出现"容器被 OOM 杀（ExitCode 137）
> 而堆转储显示堆并没有满"，就把 `MaxRAMPercentage` 降到 50 再观察。

> ⚠️ **上限之和刻意不顶满**。操作系统、Docker 守护进程、Nginx、sshd
> 自身也要内存，而它们不在 compose 里。把容器上限之和顶到物理内存，
> 等于把"谁先被 OOM"交给内核随机决定。

**宿主机还要配 4G swap**（compose 里没有对应配置项，所以写在这里）：

```bash
sudo fallocate -l 4G /swapfile && sudo chmod 600 /swapfile
sudo mkswap /swapfile && sudo swapon /swapfile
echo '/swapfile none swap sw 0 0' | sudo tee -a /etc/fstab
free -h          # 应当看到 Swap: 4.0Gi
```

**为什么是 4G**：compose 不写 `memswap_limit` 时，Docker 默认允许容器使用
"内存上限 + 等量 swap"，四个容器加起来最坏是 `384+640+240+96 = 1360M`，
4G 的 swapfile 把最坏情况整个接住还留了很大余量 ——
**余量是给构建用的**（`docker compose build` 跑在服务器上时要 Maven / npm 同时吃内存，
那时候四个容器还没起来，swap 是构建不会因为内存不足被杀掉的兜底）。
**swap 不是"内存够就不用配"** —— 它在这里的作用是给内存上限兜底，
让容器即使在峰值也只会变慢、不会被杀。

验证上限真的生效（`LIMIT` 列应当就是上面那几个值）：

```bash
docker stats --no-stream
# 或看单个容器：docker inspect yiguixingtu-prod-backend --format '{{.HostConfig.Memory}}'
```

> **换到更大内存的机器时**先跑 `free -m` 拿实测值，再按同一张表的比例放大：
> 主要改四个 `mem_limit`；backend 的堆是百分比、会自动跟着走，
> 但 mysql 的 `--innodb-buffer-pool-size` 是绝对值、要一起改
> （**而且要给 128M 的整数倍**，理由见上面那条取整的说明）。
> ⚠️ 别忘了同时改 `DeploymentMemoryBudgetTest` 里的 `HOST_MEMORY_MB`
> —— 那边也按实测值写，两边一起改才不会自相矛盾。
>
> ⚠️ **万一 MySQL 初始化 / 运行中被杀**（容器反复重启、`docker inspect` 的
> `ExitCode` 是 137、`dmesg` 里有 oom-kill 记录）：把 mysql 提到 448m、
> 同时把 backend 降到 576m —— **总量维持不变，不要直接加总预算**。

#### ⚠️ 国内服务器：Docker Hub 拉不动、GitHub 间歇性连不上（实测记录）

这两个不是"配置问题"，是这个网络环境下绕不开的前提，写在这里免得下次重新踩一遍。

**① Docker Hub 拉不到，而阿里云自己的镜像加速器也不顶用**

实测：`/etc/docker/daemon.json` 里配了阿里云容器镜像服务的专属加速器
（`https://<你的id>.mirror.aliyuncs.com`）之后，`docker pull redis:7` 依然失败：

```
failed to resolve reference "docker.io/library/redis:7": docker.io/library/redis:7: not found
```

注意它是 **`not found`，不是超时** —— 加速站返回了 404，而 Docker 把 404 当成
"这个镜像不存在"，**不会**回退去连官方源。所以这句报错特别容易读成
"镜像名写错了"，而实际上镜像名一点问题都没有。

> ⚠️ 顺带一个实测教训：**浮动标签拿到的东西可能很旧**。
> 这台机器上曾有过的 `mysql:8` 镜像，`mysqld --version` 是 **8.0.27（2021-10）**——
> 少了四年多的安全补丁，而且它来自哪个源已经查不清了。
> 所以 compose 里两个数据服务的镜像都**钉到了小版本**（`mysql:8.4` / `redis:7.4`），
> `DeploymentMemoryBudgetTest` 里有一条用例盯着"不许写成只有大版本的浮动标签"。

解法（实测可行）：**从 AWS 的公共 ECR 拉，再改回标准名**。它镜像了 Docker 官方镜像：

```bash
docker pull public.ecr.aws/docker/library/redis:7.4
docker tag  public.ecr.aws/docker/library/redis:7.4 redis:7.4
```

构建要用的那几个基础镜像同理，逐个换成标准名：
`maven:3.9-eclipse-temurin-17`、`eclipse-temurin:17-jre`、`node:24-alpine`、
`alpine:latest`、`mysql:8.4`。

> ⚠️ **别去用那些不知名的第三方加速站**（各种 `docker.1panel.live` / `hub.rat.dev` /
> `dockerproxy.*` 之类）。它们确实常常能拉通，但你是把**生产数据库镜像**从陌生人的
> 机器上拉下来 —— 镜像里多一个后门，在 compose 里完全看不出来。
> ECR Public 是正经厂商在官方镜像之上做的只读镜像，性质不同。

**② GitHub 是间歇性的，不能当地基**

实测同一条命令：有 `git ls-remote` 正常返回 SHA 的时候，也有
`Failed to connect to github.com port 443 after 133821 ms: Couldn't connect to server`
的时候。所以：

- 首次 `git clone` 加 **`--depth 1`**（只取当前快照、不搬全部历史，传输量小一半以上）
  并**重试几次** —— 在忽通忽断的链路上这是把成功率明显抬高的一步
- 部署流程**不要**依赖"服务器随时能访问 GitHub"：拉不动时用
  `git bundle create` 从本地搬（`git bundle` 保留完整历史，服务器端
  `git clone xx.bundle` 之后照常 `git pull`）
- 以后的 `git pull` 只传变动的几个文件（几百 KB），比首次 clone 容易得多

**③ Maven 依赖走阿里云镜像（已经写死在 Dockerfile 里）**

实测直连 Maven Central 时 `dependency:go-offline` 跑了 **25 分钟还没结束**
（`-q` 把下载日志吞了，看起来像卡死）；换成 `maven.aliyun.com/repository/public`
之后整个构建 **7.8 分钟**。所以 `Dockerfile` 里默认写入了那个镜像地址，
可用 `--build-arg MAVEN_MIRROR_URL=...` 覆盖或关掉。

### 1. 准备环境变量

在服务器上（`docker-compose.prod.yaml` 同目录）创建 `.env`：

```properties
# ---- 数据库 ----
MYSQL_ROOT_PASSWORD=换成一个强密码
DB_PASSWORD=换成另一个强密码

# ---- Redis ----
REDIS_PASSWORD=再换一个强密码

# ---- JWT ----
# ⚠️ 必须换掉仓库里的默认值，长度至少 32 字节。生成方法：
#   openssl rand -base64 48
JWT_SECRET=用上面命令生成一串填这里

# ---- 跨域：填前端域名，否则浏览器会跨域失败 ----
CORS_ALLOWED_ORIGINS=https://你的域名

# ---- 后端接口的对外地址（**浏览器**访问的那个，不是容器用的） ----
# 如果 Nginx 把后端反代在 /api/ 下，这里就填 https://你的域名/api
PUBLIC_API_BASE=https://你的域名/api

# ---- 站点自己的对外域名（前端页面地址，不是接口地址） ----
# canonical、sitemap.xml、robots.txt 里的地址都用它拼。
# ⚠️ 换域名部署时忘了改这一项，页面照常能打开，但搜索引擎看到的
#    canonical 与 sitemap 会指向另一个域名 —— 属于"不报错但 SEO 全废"的一类问题，
#    所以这里也用了 ${VAR:?} 的写法（没配就直接起不来）。
PUBLIC_SITE_URL=https://你的域名

# ---- 第一个管理员（库里没有管理员时自动创建，详见上文） ----
BOOTSTRAP_ADMIN_USERNAME=你的管理员账号
BOOTSTRAP_ADMIN_PASSWORD=一个强密码
BOOTSTRAP_ADMIN_NICKNAME=站长

# ---- 可选 ----（都有默认值，不填也行）
# FRONTEND_DIR=../yiguixingtu-web   # 前端仓库路径
# BACKEND_PORT=8082
# FRONTEND_PORT=3000
```

> **`.env` 绝对不要提交进仓库**（已在 `.gitignore` 与 `.dockerignore` 里）。
>
> **这份配置刻意用了 `${VAR:?必须设置}` 这种写法**：少配任何一个，
> `docker compose up` 会直接报错并告诉你缺哪个，而不是悄悄用一个默认值跑起来。
> 想确认自己配齐了没有，可以先跑一句 `docker compose -f docker-compose.prod.yaml config --quiet`。

### 2. 启动

```bash
# 第一次（要构建后端镜像，会花几分钟）
docker compose -f docker-compose.prod.yaml up -d --build

# 看状态：四个都应该是 healthy
docker compose -f docker-compose.prod.yaml ps

# 跟一下后端日志，确认这几件事都发生了：
#   ① Flyway 建表（空库会打印 Successfully applied 1 migration）
#   ② 管理员引导（会打印 已创建初始管理员账号 [xxx]）
docker compose -f docker-compose.prod.yaml logs -f backend
```

**容器 `healthy` 是靠健康检查判断的**，不是"进程还在"：
后端探针每 15 秒请求一次 `/actuator/health`；
MySQL 用 `mysqladmin ping`、Redis 用 `redis-cli ping`。
`backend` 通过 `depends_on: condition: service_healthy` 等另外两个**真的能用**了才启动 ——
否则 MySQL 还在初始化数据目录时应用就去连库，Flyway 会直接报错退出。

**重启 Docker 服务后会自动恢复**：四个服务都配了 `restart: unless-stopped`。

### 3. Nginx 反向代理（含第一层限流）

#### 3.0 先把 HTTPS 证书拿到手（否则 Nginx 根本起不来）

⚠️ **这一步不能跳。** 下面的配置里有 `listen 443 ssl;`，而 Nginx 对 ssl 端口
**强制要求**配 `ssl_certificate` —— 证书文件不存在或不配，`nginx -t` 会直接报错
（`no "ssl_certificate" is defined for the "listen ... ssl" directive`），
站点一个请求都收不到。这是第一次部署最常见的卡点。

两种拿证书的方式，选一种：

**方式 A：Let's Encrypt + certbot（推荐，免费且自动续期）**

```bash
# 1) 先让 80 端口能访问（certbot 的 HTTP-01 校验要打 http://你的域名/.well-known/...）
#    所以这一步要在 Nginx 有一个最简单的 80 站点之后做 ——
#    最省事的做法：先只配 80（不带 ssl）跑起来，再执行下面这条
sudo apt install -y certbot python3-certbot-nginx
sudo certbot --nginx -d 你的域名 -d www.你的域名
#    certbot 会自动改写 Nginx 配置（补上 ssl_certificate / ssl_certificate_key
#    与 80 → 443 的跳转），并注册一个 systemd timer 自动续期
sudo certbot renew --dry-run     # 演练一次续期，确认它真的能续
```

**方式 B：阿里云免费证书（不依赖外部 ACME 服务）**

1. 阿里云控制台 → 数字证书管理服务 → 申请免费证书（DV，一年期）
2. 签发后下载 **Nginx 格式**的压缩包，得到 `xxx.pem` 与 `xxx.key`
3. 传到服务器并放好权限：

```bash
sudo mkdir -p /etc/nginx/ssl
sudo cp xxx.pem /etc/nginx/ssl/fullchain.pem
sudo cp xxx.key /etc/nginx/ssl/privkey.pem
sudo chmod 600 /etc/nginx/ssl/privkey.pem     # 私钥只给 root 读
```

然后在上面的 `server` 块里补两行（方式 A 的话 certbot 已经替你写好了）：

```nginx
    ssl_certificate     /etc/nginx/ssl/fullchain.pem;
    ssl_certificate_key /etc/nginx/ssl/privkey.pem;
    ssl_protocols       TLSv1.2 TLSv1.3;
```

> ⚠️ **域名要指向这台服务器（A 记录）**，而且**大陆节点的 ECS 必须先完成 ICP 备案**，
> 否则 80/443 会被拦掉、证书校验也过不了。
> 另外阿里云的免费证书是**一年期、到期要手动换**，别忘了设个日历提醒
> （方式 A 的 certbot 是自动续期，省掉这件事）。

#### 3.1 站点配置

在宿主机配一个站点，把前端和后端分别代理出去：

```nginx
# ============ 限流：按客户端 IP 记一个令牌桶 ============
# 【为什么放在 http 块里】limit_req_zone 只能定义在 http 层，
# 它是一个"共享内存区"，所有 server/location 共用同一份计数。
#
# 【$binary_remote_addr 是什么】客户端的二进制形式 IP（4 字节，比字符串省空间）。
# 【zone=api:10m】开一块 10MB 的共享内存存计数，能装大约 16 万个 IP。
# 【rate=20r/s】每个 IP 每秒 20 个请求 —— 正常用户远远用不到，
#               但足以把脚本刷接口挡在门外。
limit_req_zone $binary_remote_addr zone=api:10m rate=20r/s;

# 登录接口单独一个更严的桶：5 次/分钟（和后面应用层的配额对齐）
limit_req_zone $binary_remote_addr zone=login:10m rate=5r/m;

server {
    listen 443 ssl;
    server_name 你的域名;

    # 证书（见上面 3.0；用 certbot 的话这两行它已经自动写好了）
    ssl_certificate     /etc/nginx/ssl/fullchain.pem;
    ssl_certificate_key /etc/nginx/ssl/privkey.pem;
    ssl_protocols       TLSv1.2 TLSv1.3;

    # ---- 请求体大小：上传功能必须自己放开，默认只有 1MB ----
    #
    # 【为什么这一行不能省】Nginx 的 client_max_body_size 默认是 **1m**。
    #   不改的话，超过它的请求会在【边缘】就被拒掉（413），
    #   而且后端一行日志都不会有 —— 表现是"上传失败，但服务端什么都没记"，
    #   排查时容易一路怀疑到业务代码，其实请求压根没进来。
    #   这个坑对三种上传都成立：图片（10MB）、音频（20MB）、附件（100MB）。
    #   本机演练时只传过一张几十 KB 的封面，所以从来没触发过它。
    #
    # 【110m 是怎么来的】== 后端 spring.servlet.multipart.max-request-size（110MB）。
    #   两层的关系与"框架层 / 业务层"完全一样：Nginx 管"请求能不能进来"，
    #   Spring 管 multipart 解析，业务层再按 type 判"这一类允许多大"。
    #   ⚠️ 改后端那两个限额时，这里要一起改 —— 只改后端的话，
    #      超过 110m 的上传仍然死在这一行上。
    #   ⚠️ Nginx 默认会把整个请求体先缓冲到磁盘（client_body_temp_path）再转发，
    #      所以一次 100MB 的上传会额外占用 100MB 临时空间 —— 上传目录那 5GB
    #      的预算是另一回事（见「文件上传」章节）。
    client_max_body_size 110m;

    # 前端（Nuxt 容器）
    #
    # 【⚠️ /sitemap.xml、/robots.txt、/feed.xml 必须走这里，不能配成静态文件】
    #   它们现在是前端的【运行时路由】（由 Node 按后端数据现算 sitemap 与 RSS），
    #   不是 public/ 下的静态文件。所以不能让 Nginx 用 try_files 直接返回磁盘文件 ——
    #   那样子目录里没有这个文件，用户拿到的就是 404，而页面一切正常，
    #   只有搜索引擎/订阅器那边悄悄失败。
    #   同理：前端仓库里已经【删掉了】public/robots.txt（留着会变成两个来源，
    #   改了运行时路由却"没生效"，最难查）。
    location / {
        proxy_pass http://127.0.0.1:3000;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        # ⚠️ 这一行必须有：后端做与 IP 相关的判断（以及日志）要靠它，
        #    没有它所有请求的来源都会是 Nginx 自己的地址
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
    }

    # 浏览器请求后端接口时走的路径（PUBLIC_API_BASE = https://你的域名/api，所以它是【对外】的）
    #   ⚠️ 别把这里和"前端容器怎么访问后端"搞混：容器走的是 compose 网络里的服务名
    #      （NUXT_API_BASE_SERVER=http://backend:8082，见 nuxt.config 与 compose），
    #      完全不过 Nginx。这个 location 服务的是**浏览器**（含 SSR 期间由浏览器发起的那些请求）。
    location /api/ {
        proxy_pass http://127.0.0.1:8082/;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;

        # ---- 第一层限流：按 IP ----
        # burst=40   允许突发 40 个请求先排队（不多于这个数就不立刻拒）
        # nodelay    排队的请求【立刻】处理，而不是按 rate 一个一个放 ——
        #            不加它的话，突发流量会被强行拉慢，正常用户也会感觉卡
        limit_req zone=api burst=40 nodelay;
        # 被限流时返回 429（Nginx 默认是 503，那是"服务不可用"的语义，
        # 跟"你请求太多了"是两回事；而应用层也用 429，两层保持一致）
        limit_req_status 429;
    }

    # ---- 管理端点（/actuator/**）：必须显式挡掉 ----
    #
    # 【⚠️ 这一段是【必须的】，不是"多一层保险"—— 而且原因很容易想反】
    #   上面那句 `location /api/ { proxy_pass http://127.0.0.1:8082/; }` 里，
    #   proxy_pass 结尾的那个 `/` 表示"把匹配到的 /api/ 前缀【替换】成 /"，
    #   也就是把剩下的部分拼到后端的【根路径】上。于是：
    #       https://你的域名/api/actuator/prometheus  →  后端 /actuator/prometheus
    #   而后端那个端点对匿名请求是【放行】的（抓指标的是 Prometheus，
    #   它没有也不该有我们的 JWT，见 SecurityConfig 里那段说明）。
    #   ⇒ 不写这一段，上面那个地址就能从公网读到接口路径、调用量、连接池占用、
    #     JVM 内存等内部信息。
    #
    #   ⚠️ 这里曾经有一个想当然的推论，一共【三处】都写错了，记下来免得再犯：
    #      "Nginx 里没有 /actuator 这个 location，所以从域名访问不到它。"
    #      这是【反的】：请求走的一直是 /api/ 那一条，它根本不需要一个
    #      /actuator 的 location —— 前缀被替换掉之后才拼成 /actuator/...。
    #      （原来这句同时出现在 SecurityConfig 的注释、「可观测」章节那张
    #        "安全护栏"表和下面核对清单第 9 条里，现已全部改正。）
    #
    # 这个 location 比 /api/ 更具体，Nginx 按"前缀取最长"会优先匹配它
    # （和上面那两个 location 是同一个机制，与书写顺序无关）。
    # 直接 return 404 而不是"反代过去再让后端拒绝"：既省一次转发，
    # 也不给这个端点留下任何被误放行的机会。
    location /api/actuator/ {
        return 404;
    }

    # ---- 上传的封面图：单独一段，两个"必须" ----
    # 这个 location 比 /api/ 更具体，Nginx 会优先匹配它（前缀匹配取最长）。
    location /api/uploads/ {
        proxy_pass http://127.0.0.1:8082/uploads/;
        proxy_set_header Host $host;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;

        # 【必须一：图片不能走上面那个限流】
        #   一篇带 10 张图的文章，打开一次就是 10 个请求 ——
        #   套上 20r/s 的桶之后，一个正常用户翻两页就会把自己限成 429，
        #   表现是"图片刷不出来"，而真正的原因是被限流了。
        #   这里刻意【不写 limit_req】。

        # 【必须二：把后端的 no-store 头摘掉，换成长期缓存】
        #   后端是 Spring Security 保护的，它默认给所有响应加
        #   Cache-Control: no-cache, no-store, max-age=0, must-revalidate ——
        #   那是给"接口响应"用的（接口数据确实不该被缓存），
        #   但对图片是灾难：浏览器每次都要重新下整张图。
        #   proxy_hide_header 把上游那个头藏掉，再用 add_header 换成长期缓存。
        #   敢给 30 天是因为图片文件名是 UUID：内容永不改变，
        #   换了图就是换了 URL，不存在"缓存了旧图"的问题。
        proxy_hide_header Cache-Control;
        expires 30d;
        add_header Cache-Control "public, max-age=2592000, immutable";
        access_log off;
    }

    # ---- 前端的背景视频 / 音乐：不进构建产物，由 Nginx 直接发文件 ----
    # 这两个文件（bg-star.mp4 / bg-music.mp3，合计约 12.4MB）如果留在前端仓库的
    # public/ 目录里，就会被打进前端构建产物，而且每次请求都要穿过 Nuxt 进程。
    # 它们是"不会变的大文件"—— 交给 Nginx 直接返回，最省事也最快
    # （比起绕一圈对象存储/CDN，单台 ECS 上少一次外网往返）。
    # 部署时把前端仓库 static-media/ 里的文件传到 /var/www/media/ 即可。
    location /media/ {
        alias /var/www/media/;
        access_log off;
        # 文件名固定、内容不变，可以放心长期缓存；将来换素材就换个文件名
        expires 7d;
        add_header Cache-Control "public, max-age=604800";
        # ⚠️ 这里刻意【不加】limit_req：<video>/<audio> 是按 Range 分段拉取的
        #    （一次播放可能发几十个请求），套上限流会让播放中途卡住，
        #    而表现看起来像"网络不好"，极难排查
    }

    # 登录接口：更严的桶（防暴力破解）
    location = /api/auth/login {
        proxy_pass http://127.0.0.1:8082/auth/login;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;

        limit_req zone=login burst=3 nodelay;
        limit_req_status 429;
    }
}
```

**这两个背景文件（`bg-star.mp4` / `bg-music.mp3`）怎么部署**：

```bash
# 在服务器上：建目录 + 从本地把前端仓库里的媒体文件传上去
sudo mkdir -p /var/www/media
scp yiguixingtu-web/static-media/* root@服务器IP:/var/www/media/
```

⚠️ 它们**不进前端构建产物**（这正是把它们从 `public/` 挪出来的目的：前端
`.output/public` 从约 17.2MB 降到约 3.5MB，少掉的 13.7MB 就是这两个文件），
所以 `docker compose up -d --build` **不会**带上它们 ——
必须单独传一次。忘了传的症状是"页面能开，背景黑屏、音乐点了没反应"，
而浏览器控制台只会报一个 `404 /media/bg-star.mp4`。

> **为什么限流要做两层（Nginx + 应用层）**
>
> | | Nginx 的 `limit_req` | 应用层的 Resilience4j |
> |---|---|---|
> | 按什么限 | **按客户端 IP** | **按接口的全局配额** |
> | 挡住的是谁 | 单个 IP 的疯狂刷接口 | 所有来源加起来把某个接口刷爆 |
> | 保护的是谁 | 后端进程本身 | **后面的数据库和 Redis** |
>
> 两层各自都有挡不住的情况，所以都要有：
> Nginx 挡不住"几千个代理 IP 每个只请求几次"（每个 IP 都没超限，但总量能把数据库打满），
> 应用层挡不住"某个 IP 独吞了全部配额"（它只认总数，不知道是谁在用）。
>
> ⚠️ 还有一点必须说清楚：**应用层的配额是整站的，不是按人的**
> （Resilience4j 的 RateLimiter 是进程级计数器）。按 IP 的细粒度限制只在 Nginx 这层。
> 两层的具体数值与理由见 `application.properties` 里「接口限流」那一段。

> **如果走 `/api/` 前缀**，前端要相应地把 `NUXT_PUBLIC_API_BASE`
> 设成 `https://你的域名/api`；后端的 `CORS_ALLOWED_ORIGINS` 填 `https://你的域名`。
> 两者必须对得上，否则就是跨域失败。

### 4. 上线前的核对清单

| # | 检查项 | 怎么确认 |
|---|---|---|
| 1 | `JWT_SECRET` 已换成随机值 | `.env` 里不是仓库里那串默认值 |
| 2 | `CORS_ALLOWED_ORIGINS` 是真实域名 | 浏览器打开站点，F12 里没有跨域报错 |
| 3 | Swagger 关掉了 | 访问 `https://你的域名/api/v3/api-docs` 应当 **401** |
| 4 | MySQL / Redis 端口没有对外暴露 | 在服务器外 `telnet 服务器IP 3306` 应当连不上 |
| 5 | 管理员能登录后台 | 打开 `/admin`，用引导账号登录 |
| 6 | 换掉引导管理员的初始密码 | 后台 → 用户管理 → 重置密码 |
| 7 | 数据库每天自动备份 | 见下面「备份与恢复」 |
| 8 | 后端/前端端口只绑回环，没有对公网开放 | 在服务器外 `telnet 服务器IP 8082` 与 `telnet 服务器IP 3000` 都应当连不上（`docker-compose.prod.yaml` 里已写成 `127.0.0.1:` 前缀） |
| 9 | `/actuator/prometheus` 没有被公网看到 | `curl -i https://你的域名/api/actuator/prometheus` 应当返回 **404**（由 Nginx 里那条 `location /api/actuator/ { return 404; }` 挡掉）。<br>⚠️ 这一项原来写的是"Nginx 只反代 `/api/`，正常情况打不到"——**那是错的**：`location /api/` 的 `proxy_pass` 结尾带 `/`，会把 `/api/actuator/...` 拼到后端根路径上，正好命中那个匿名放行的端点。所以这一条必须真的 curl 一次，不能靠"按道理应该打不到" |
| 10 | **上传的图片在容器重建后还在** | 后台上传一张封面 → `docker compose -f docker-compose.prod.yaml up -d --force-recreate backend` → 再打开那篇文章，图片应当还能显示（守"上传目录有没有真的挂到卷上"） |
| 11 | 图片地址是外网可访问的 | 右键封面图「复制图片地址」，在无痕窗口打开应当能看到图（守 `UPLOAD_BASE_URL` 填的是浏览器能访问到的地址） |
| 12 | **背景视频/音乐能播** | `curl -I https://你的域名/media/bg-music.mp3` 应当返回 **200**（守"前端仓库 `static-media/` 里的文件真的传到了 `/var/www/media/`"——它们不在构建产物里，忘了传就只有 404） |
| 13 | **审计表真的在记** | 后台改一下某篇文章，然后在服务器上执行：`docker exec yiguixingtu-prod-mysql sh -c 'exec mysql -uroot -p"$MYSQL_ROOT_PASSWORD" yiguixingtu -e "SELECT * FROM operation_log ORDER BY id DESC LIMIT 3"'` —— 应当能看到那条记录，且 `ip` 是**真实访问者地址**（不是 `127.0.0.1`；是它就说明 Nginx 没把 `X-Forwarded-For` 传下去）<br>⚠️ 注意是 **`-prod-`** 那个容器名，而且密码要用 `sh -c` 从容器自己的环境变量取 —— 直接写 `-p"$MYSQL_ROOT_PASSWORD"` 的话，那个变量在宿主机 shell 里通常**并不存在**，会变成空密码并进入交互式提示 |
| 14 | **SEO 三件套都对** | `curl -s https://你的域名/ \| grep canonical` 应当是**你的真实域名**；`curl -I https://你的域名/sitemap.xml` 返回 200 且 `Content-Type` 是 XML；`curl -s https://你的域名/robots.txt` 里的 `Sitemap:` 也是你的域名；`curl -s https://你的域名/feed.xml \| head -c 200` 能拿到 RSS（守 `PUBLIC_SITE_URL` 有没有填对 —— 填错不会报错，只会让搜索引擎/订阅器指向别的域名） |
| 15 | **首页 HTML 里有文章** | `curl -s https://你的域名/ \| grep -o '<h3[^>]*>[^<]*'` 应当能看到文章标题 —— 首页是 SSR 的，源码里就该有内容；如果只有 `<div id="__nuxt"></div>`，说明 SSR 没生效（那是纯客户端渲染的表现） |
| 16 | **评论链路是通的** | 打开一篇文章发一条评论 → 前端提示"等待审核"、前台列表里**看不到** → 后台点通过 → 前台刷新能看到（守"待审核状态写死在前台查询里"这条规则真的生效） |
| 17 | **HTTPS 真的生效、且会自动续期** | `curl -I https://你的域名` 返回 200 且证书有效（浏览器地址栏是小锁不是"不安全"）；方式 A 的话再跑一次 `sudo certbot renew --dry-run`。⚠️ 用阿里云免费证书的话它是**一年期、不自动续**，记得设置到期提醒 |
| 18 | **http 会跳到 https** | `curl -I http://你的域名` 应当是 **301** 到 https（没有这条跳转的话，用户用 http 打开会看到一个空白页或证书错误） |
| 19 | **`nginx -t` 通过、改完 reload 过** | `sudo nginx -t && sudo systemctl reload nginx` —— 配置写了但没 reload 是最常见的一种"明明改了却没生效" |
| 20 | **磁盘不会被日志写满** | `df -h` 看水位；`docker system df` 看镜像/卷/构建缓存占比。容器日志已配轮转（每容器上限 30MB），应用日志文件有 15 天 + 2GB 双重上限（见「备份与恢复」后面那节），但**构建缓存**会随每次 `--build` 增长，定期 `docker builder prune` 清一下 |
| 21 | **日志文件确实写在卷里（升级后还能查）** | `docker exec yiguixingtu-prod-backend ls -l /app/logs` 应当能看到 `yiguixingtu.log` 且属主是 `app`；再 `docker compose -f docker-compose.prod.yaml up -d --force-recreate backend` → 文件仍在（守 `LOG_DIR` 指向挂载点 —— 指错的话日志会写进容器可写层，**重建即丢，而且没有任何提示**） |
| 22 | **归档页把全部文章的内链放进了服务端 HTML** | `curl -s https://你的域名/archive \| grep -c 'href="/article/'` 应当**等于已发布文章的篇数**（后端上限 500），并且源码里能看到「20xx 年 x 月」这样的分组标题与「共 N 篇」—— 归档页是 SSR 的，它存在的意义就是"一次拿到全部内链"（爬虫不用执行 JS、站内 Ctrl+F 也能用）；只看到 `<div id="__nuxt"></div>` 说明 SSR 没生效。这一条同时守两件事：**前端真的按年月把 `GET /article/archive` 的分组渲染出来了**，以及**这个页面没有被 Nginx 配成静态文件**（和 sitemap / robots / feed.xml 一样，它属于前端的运行时路由，必须由 `location /` 转发给前端容器） |
| 23 | **大文件上传真的穿得过 Nginx** | 后台上传一个 **>1MB** 的文件（例如 5MB 的附件）应当成功。<br>⚠️ 守的是 `client_max_body_size`：Nginx 默认只有 **1m**，不改的话图片（10MB）/ 音频（20MB）/ 附件（100MB）都会在边缘被 **413** 拒掉，而后端日志里一条都查不到。上面站点配置里已写成 `110m`（与后端 `spring.servlet.multipart.max-request-size` 对齐）—— **改后端那两个限额时要一起改这里** |
| 24 | **附件点开是下载、图片仍然内联** | 传一个 PDF 当文章附件 → 前台点它应当是**下载**而不是在标签页里打开（响应头 `Content-Disposition: attachment`）；同时封面图要**正常显示**（不能变成下载按钮）。守的是 `UploadResponseHeaderFilter`：附件必须下载（html/svg 那类一旦被就地打开就是在自家域名下执行脚本），图片/音频必须保持内联 |

#### 这份清单在本机演练过一遍（不是纸面清单）

上线这条路最容易出问题的恰好是"**空库第一次启动**"——它只在服务器上执行一次，
而且正是最不该出错、又最没机会练的那一次。所以部署前在本机用
`docker-compose.prod.yaml` **原封不动地演练了一遍生产形态**（只改了两个宿主机端口，
用 `BACKEND_PORT=8092` / `FRONTEND_PORT=3010` 避开正在跑的开发环境；
compose 里的独立项目名 `yiguixingtu-prod` 保证了它和本地开发容器互不干扰）：

| 演练项 | 实测结果 |
|--------|---------|
| 四个容器 | mysql / redis / backend / frontend 全部 `healthy`（frontend 是 Nuxt SSR 容器） |
| 空库启动 → Flyway | `Successfully applied 6 migrations`（V1 init → V2 排序索引 → V3 覆盖索引 → V4 审计表 → V5 标签表 → V6 评论表），7 张表全部建好 |
| 管理员引导 | 用 `.env` 里的引导账号能直接登录（`role=ADMIN`） |
| 端口暴露面 | 只有 `127.0.0.1:8092->8082`（后端）与 `127.0.0.1:3010->3000`（前端）；**mysql 与 redis 一个端口都没映射** |
| prod profile | `/actuator/health` 返回 UP；`/v3/api-docs` 返回 401（**Swagger 确实关掉了**） |
| 内容链路 | 建分类 + 标签 + 文章（带 `tagIds`）→ `?tagId=` 筛选 total=1、详情带 1 个标签、归档 1 个月、RSS 1 条且**含正文** |
| 评论链路 | 游客发评论 → `status=0` 且**带 createTime** → 前台 total=0（看不到）→ 接口通过 → 前台 total=1 |
| **前端 SSR（容器间互访）** | 首页 HTML 30KB，**含刚发的文章标题**与 canonical；文章页 82KB **含那条评论**；**可被爬虫跟随的标签内链在文章页**：`<a class="doc-tag" href="/?tagId=1">` |
| 归档页（SSR） | `/archive` HTML 里 `class="am-item"` 的条数 **= 接口的 total**（实测 2 = 2），且有「20xx 年 x 月」分组标题 —— 守"全部内链真的进了服务端 HTML" |
| sitemap / robots / feed | `/sitemap.xml` 200 且含 `/article/1` 与 `/archive`；`/robots.txt` 200 且 `Sitemap:` 用的是配置的域名；`/feed.xml` 200 且**能被严格 XML 解析器解析**并含标题与 `pubDate` |
| **按需引入的组件样式真的在** | 样式是**按路由**的，所以分页查：首页 CSS（93KB）里有 `.el-message`（每个页面都靠它显示提示）、`.el-button`；**后台页** CSS（238KB）里有 `.el-table` / `.el-dialog` / `.el-input` / `.admin .panel` / `.ed-row`；同时**反向确认**站点没用到的 `.el-calendar` / `.el-carousel` / `.el-cascader` 在**两个页面里都是 0 处** |
| 操作审计 | 上面那些管理动作在 `operation_log` 里逐条留痕（CREATE_CATEGORY / CREATE_TAG / CREATE_ARTICLE / UPDATE_COMMENT_STATUS） |
| 上传落卷 | 上传的封面写进 `/app/uploads/cover/2026/09/…png`，`GET` 返回 200；**`--force-recreate` 重建容器后文件还在、内容逐字节一致**（守"具名卷有没有真的挂上"） |
| 应用层限流 | 连续 8 次登录 → `200,200,200,200,200,429,429,429`（配额 5 次/分钟，返回**真 HTTP 429**）；并且验证了"前 5 次确实进到了控制器"——否则一次 JSON 解析失败也会让 8 次都返回 200，看起来像限流失效 |
| 日志落卷 | `/app/logs/yiguixingtu.log` 属主 `app`、64 行；重建容器后仍在（守 `LOG_DIR` 指向挂载点） |

> ⚠️ **首页上的标签不是链接，这是设计**（2026-09-11 校正一条旧记录）：
> 首页把标签渲染成 `<button class="tagp">`，点击后**原地筛选**并由前端改地址栏，
> 所以**按设计就没有 `href`**；可被爬虫跟随的 `/?tagId=N` 内链在**文章详情页**。
> 之前那句"标签是真实内链"写在首页那行下面，容易被理解成"首页有内链"——
> 我自己在验收脚本里就照着查错过一次（查首页 → 假失败），所以这里写清是哪个页面。
>
> 演练完 `docker compose down -v` 把演练用的容器与卷全部删掉，
> 开发环境（8082 / 3310 / 6380）不受任何影响。**实测**：演练后 `docker ps -a` 里
> 与 `yiguixingtu-prod` 相关的容器与卷都是 0 个，dev 后端与两个 dev 容器照常运行。

#### ⚠️ 镜像构建会下载 Maven 依赖 —— 国内网络务必注意

`Dockerfile` 的构建阶段要跑 `mvn dependency:go-offline` + `mvn package`，
也就是**在构建镜像时把整个依赖树拉一遍**。实测（本机、国内网络）：
直连 Maven Central 时，`dependency:go-offline` 跑了 **25 分钟还没结束**
（`-q` 把下载日志吞了，看起来就像卡死）；换成阿里云镜像后整个构建 **7.8 分钟**。

所以 Dockerfile 里已经默认写入了阿里云公共镜像（`maven.aliyun.com/repository/public`）。
想换掉或关掉它：

```bash
# 换成别的镜像
docker build --build-arg MAVEN_MIRROR_URL=https://你的镜像/repository/public -t yiguixingtu-backend .

# 关掉 mirror（回到直连 Maven Central）
docker compose -f docker-compose.prod.yaml build --build-arg MAVEN_MIRROR_URL=
```

> 另一条路是**在本机构建好镜像再传到服务器**（`docker save` + `docker load`，
> 或者推到阿里云 ACR）—— 服务器上就不用再下载依赖了。
> 本项目 README 的部署章节默认走"服务器上构建"，所以把镜像配好更省事。

### 5. 备份与恢复

> ⚠️ **容器名别弄混**：生产编排用了独立项目名与 `-prod` 后缀，
> 所以生产库的容器叫 **`yiguixingtu-prod-mysql`**；
> 本地开发那个叫 `yiguixingtu-mysql`。下面给的都是**生产**的名字 ——
> 拿开发的名字去服务器上执行，只会得到一句 `No such container`。

```bash
# —— 手动备一次（日常由 cron 自动跑，见下一节）——
/srv/yiguixingtu/scripts/backup.sh

# —— 恢复 MySQL（先停后端，避免写入和恢复互相打架）——
# 用带时间戳的那一份（db-YYYYmmdd-HHMMSS.sql.gz）
docker compose -f docker-compose.prod.yaml stop backend
gunzip -c /srv/backup/db-20260912-033000.sql.gz | \
  docker exec -i yiguixingtu-prod-mysql sh -c \
  'exec mysql -uroot -p"$MYSQL_ROOT_PASSWORD"'
docker compose -f docker-compose.prod.yaml start backend

# —— 恢复上传文件（把备份解回卷里；用运行中的 backend 容器，不必拉别的镜像）——
docker exec -i yiguixingtu-prod-backend tar -xzf - -C /app/uploads \
  < /srv/backup/uploads-20260912-033000.tar.gz
```

#### 自动备份是怎么装的（2026-09-12 落地）

**一个脚本 + 一条 cron，脚本本身跟着代码一起版本化**（`scripts/backup.sh`，注释里写了每一步的理由）：

```bash
# 脚本做了这些：取锁 → 磁盘空间护栏 → mysqldump 全库 gzip → 打包上传目录
#                → 校验（gzip -t / 有没有 CREATE TABLE / 表数量对账）→ 写 sha256 → 清理旧备份
chmod +x /srv/yiguixingtu/scripts/backup.sh

# crontab -e 里加这一行：每天 3:30 跑，输出追加到日志
30 3 * * * /bin/bash /srv/yiguixingtu/scripts/backup.sh >> /var/log/yiguixingtu-backup.log 2>&1
```

| 项 | 值 |
|---|---|
| 备份目录 | `/srv/backup/`（`db-*.sql.gz` + `uploads-*.tar.gz` + `backup-*.sha256`） |
| 保留 | **14 天**（`KEEP_DAYS` 可覆盖），到点自动删 |
| 日志 | `/var/log/yiguixingtu-backup.log` |
| 什么时候跳过 | 上一次还在跑（`flock`）—— 跳过不算失败，日志里会写清楚 |
| 什么时候**拒绝**跑 | 磁盘剩余 < 500MB（`MIN_FREE_MB`）—— 宁可少一天备份，也不要写满磁盘 |

> ⚠️ **脚本里几个"看起来多余、其实都是坑"的设计**（改脚本前先读注释）：
> - **`set -o pipefail`**：没有它，`mysqldump | gzip` 里 mysqldump 的失败会被 gzip 的成功掩盖，
>   结果是**安静地产出一个只有半个库的备份** —— 备份脚本最危险的失败方式；
> - **写 `.tmp` 再 `mv`**：中途被杀会留下半截文件，而它名字、gzip 头都对，看起来和好备份一样；
>   改名是原子的，"目录里存在的正式备份 = 当时确实写完过"；
> - **表数量对账**：备份里的 `CREATE TABLE` 条数必须等于线上表数，少一张就判失败；
> - **打包上传目录用 backend 容器、不用 `docker run -v 卷:/data alpine ...`**：
>   后者要额外拉镜像，而**服务器拉镜像本来就时好时坏**（实测镜像加速器经常失败）——
>   "平时能跑、偶尔因为拉不到镜像而失败"的备份等于没有备份；
> - **`MYSQL_PWD` 走环境变量**：密码不进程命令行（命令行参数在 `ps` 里谁都看得见）；
>   密码始终由**容器自己的**环境变量提供，不出容器。

**验证过的备份才算备份**：脚本带一个手动开关，会把备份**真的导进一个临时库**、比对行数、再删掉临时库：

```bash
/srv/yiguixingtu/scripts/backup.sh --verify-restore
# 实测输出（2026-09-12）：
#   ⚠️ --verify-restore：把备份导进临时库 yiguixingtu_verify_20260912-112152 并比对行数
#   ✅ 恢复演练通过：article 行数 3 = 3，临时库已删除
```

> ⚠️ 这一步刻意**不进 cron**（它比日常备份重得多）；但每次改完数据库结构、
> 或者心里没底的时候，手动跑一次。⚠️ 演练时的 `USE` 目标会被改写成临时库 ——
> 这一步写错就等于"恢复演练把生产库清了"，所以脚本里把它放在导入前、并且只改 `USE` 那一行。
>
> ⚠️ **一个必须承认的短板**：这些备份都躺在**同一块盘**上。
> 它能挡住"误删数据 / 迁移写坏 / 卷损坏"，挡不住"磁盘整个没了 / 实例被释放"。
> 真要防后者，还得加一样**离开这台机器**的：阿里云**快照**（整盘，最省事）
> 或者把 `/srv/backup` 每天同步到 **OSS**（`ossutil cp -r --update`）。
> 这两件都需要你的云账号，所以还没做 —— 记得补上。

#### ⚠️ 别忘了上传文件（备份脚本里已经包含）

封面图 / 音频 / 附件存在服务器的**具名卷** `yiguixingtu-prod_uploads_data` 里，
它和数据库是两回事 —— **只备份 MySQL 的话，数据库恢复出来了、图片却是空的**，
文章里全是裂图。所以 `scripts/backup.sh` **每次都会同时产出两份**：
`db-*.sql.gz` 与 `uploads-*.tar.gz`，两者用**同一个时间戳**配对（`sha256` 也是同一份文件里）。

恢复上传文件（把备份解回卷里）：

```bash
# ⚠️ 这一步用【运行中的 backend 容器】解包，不拉额外镜像（理由同上：服务器拉镜像不稳）
docker exec -i yiguixingtu-prod-backend tar -xzf - -C /app/uploads \
  < /srv/backup/uploads-20260912-033000.tar.gz
# 解完确认一下文件数（脚本的日志里记着备份时是几个文件）
docker exec yiguixingtu-prod-backend sh -c 'find /app/uploads -type f | wc -l'
```

> **备份要验证过才算备份**。数据库那份用 `--verify-restore` 演练（见上一节，已实测通过）；
> 上传文件那份最省事的验证是 `tar -tzf uploads-*.tar.gz | head`（能列出文件名就说明包没坏）
> 加上"解进一个空目录、文件数与日志里的数字对得上"。
> 没验证过的备份，真出事时大概率用不了。

#### 磁盘不会被日志写满（两类日志都管住了）

**要分清楚有两份日志，它们的去处和上限完全不同**：

| | 谁写的 | 去哪 | 上限 | 怎么查 |
|---|---|---|---|---|
| 标准输出 | 应用 + 各容器 | Docker 的 `json-file` | **每容器 30MB**（锚点配置 10m×3） | `docker compose -f docker-compose.prod.yaml logs --tail=200 backend` |
| 应用日志文件 | logback | 具名卷 `logs_data` → 容器内 `/app/logs` | **滚 15 天**、单文件 50MB、总量 2GB（logback 自己的策略） | `docker exec yiguixingtu-prod-backend ls -l /app/logs` |

Docker 默认的 `json-file` 驱动**不轮转** —— 不管它，容器日志会一直长下去。
一台 40GB 盘的 ECS 加上 MySQL 慢查询日志，几个月后就可能"数据库写不进去、
容器起不来"，而且错误信息不会指向日志。所以 compose 里给四个服务都配了轮转：

```yaml
x-logging: &default-logging
  driver: json-file
  options:
    max-size: "10m"     # 单个日志文件到 10MB 就切
    max-file: "3"       # 最多留 3 个（合计每容器 30MB）
```

> ⚠️ **日志文件必须挂卷，否则"保留 15 天"是句空话**
> logback 默认把日志写在容器工作目录下的 `logs/`（也就是容器的可写层）——
> 容器一重建（改配置、升级、`up -d --build`）就全没了，
> **而那恰恰是最需要回头查历史日志的时候（刚升级完发现不对）**。
> 所以 backend 服务挂了 `logs_data:/app/logs` 并把 `LOG_DIR` 指过去，
> 实测：`/app/logs` 属主是 `app:app`、日志文件真的在写、卷里也确实有文件。
> 这个卷**不需要备份**（它只是排查用的），而且 logback 有 15 天 + 2GB 双重上限，不会无限涨。

> **要长期保留的是审计表**（`operation_log`，结构化、可查询），不是这些文本日志 ——
> 两者的职责分开，才不会指望日志文件当审计用。

> 磁盘水位也要看：`df -h` 与 `docker system df`（后者告诉你镜像/卷/构建缓存各占多少）。

### 6. 升级流程

```bash
git pull
docker compose -f docker-compose.prod.yaml up -d --build
docker compose -f docker-compose.prod.yaml ps   # 等 healthy
```

**表结构变更走 Flyway**：新增的 `V2__xxx.sql` 会在容器启动时自动执行，
不需要手工上服务器改表。这也是当初把建表从"手工 SQL"换成 Flyway 的回报。

### 7. 出问题时的排查顺序

| 现象 | 先看这里 |
|---|---|
| 容器一直 `unhealthy` | `logs backend`。最常见是环境变量没配齐，或连不上 `mysql`（注意**地址要用服务名 `mysql`，不是 `localhost`**） |
| 前端报跨域 | `CORS_ALLOWED_ORIGINS` 是否等于前端实际访问的域名（协议、端口都要一致） |
| 打不开后台 / 登不进去 | 日志里有没有"已创建初始管理员账号"。没有的话说明 `BOOTSTRAP_ADMIN_*` 没配，库里可能没有管理员 |
| 接口 401 | token 过期（默认 1 天），重新登录即可 |
| 改了配置不生效 | 环境变量改完要 `docker compose -f docker-compose.prod.yaml up -d` 让容器重建 |

## 构建与测试

### 跑测试

```bash
# 仓库自带 wrapper（推荐，锁定 Maven 3.9.16）
./mvnw -B test          # Linux / macOS
.\mvnw.cmd -B test      # Windows

# 或者用本地 Maven
mvn test
```

**只需要本机有 Docker，不需要事先启动 MySQL / Redis**（原因见下面「测试环境自带容器」）。

### 构建 + 覆盖率报告

```bash
./mvnw -B verify
```

用 `verify` 而不是 `test`，因为覆盖率报告绑定在 `verify` 阶段。
跑完在 `target/site/jacoco/index.html` 可以看到可视化报告。

**当前覆盖率（`verify` 实测）：**

| 维度 | 覆盖率 |
|------|:---:|
| 行覆盖 | **91.1%**（2,427 / 2,663） |
| 方法覆盖 | **97.1%**（462 / 476） |
| 指令覆盖 | **90.3%**（10,716 / 11,868） |
| 分支覆盖 | 71.2%（681 / 956） |

> 分支覆盖率明显低于行覆盖率，是因为大量的**参数校验分支、异常兜底分支、
> 空值判断分支**不会被每个用例都走到——这是正常的，不必为了刷数字硬凑用例。
> 真正该关心的是关键路径有没有被覆盖：认证、权限、草稿隔离、逻辑删除这几条都在 100% 附近。

覆盖率**只统计本项目自己的代码**（JaCoCo 配了 `includes: com/yigalaxy/yiguixingtu/**`），
不把依赖库算进去。

### 持续集成（GitHub Actions）

`.github/workflows/ci.yml`，在 **push 到 master** 和 **PR** 时触发：

1. 装 JDK **17**（与 `pom.xml` 的 `java.version=17` 一致）
2. `./mvnw -B verify` —— 构建 + 跑 463 个用例 + 出覆盖率
3. 上传 `surefire-reports` 与 `jacoco-report` 两个 artifact（`if: always()`，测试失败时报告最需要看）

**CI 上不需要配置任何 MySQL / Redis 服务** —— 测试用 Testcontainers 自己拉起容器，
GitHub 的 ubuntu runner 自带 Docker。这正是把测试容器化的价值所在。

### 测试环境自带容器

> ✅ **测试不需要事先启动任何服务。** 测试启动时由 **Testcontainers**
> 自己拉起 MySQL 与 Redis 容器、跑完自动销毁，所以
> **即使先执行 `docker compose down`，`mvn test` 也照样全绿** —— 只需要本机装了 Docker。
>
> 这意味着：任何人 clone 下来就能验证这 463 个用例，CI 上也能跑
> （在此之前，测试直连本机 3310/6380，换台机器不先起容器就全红，CI 更是跑不了）。

**42 个测试类文件（其中 41 个含用例，另 1 个是无用例的基类 `AbstractIntegrationTest`），463 个用例，全部通过：**

> 统计口径（这一行别改错）：下表**每个测试类文件一行**（基类也占一行，用例数记 0），
> 所以**行数 = `src/test/java/com/yigalaxy/yiguixingtu/` 下的 .java 文件数（42）**，
> **各行用例数之和 = 总数（463）**。新加测试类时必须同时改这三处：
> 加一行、把该行数字填对、把「合计」与上面那句总数改掉 ——
> 少改一处的表现是"文档里的数字和 CI 报的不一样"，而不会有任何测试变红。

| 测试类 | 用例数 | 覆盖 |
|--------|:---:|------|
| `AuthLoginTest` | 4 | 登录成功/失败、禁用账号、返回体结构 |
| `UserRegisterTest` | 3 | 注册成功、用户名重复、参数校验 |
| `JwtSecurityTest` | 4 | JWT 签发/解析、无 token 401、游客 403 |
| `GlobalExceptionHandlerTest` | 8 | 异常到统一返回体的映射（含「地址不存在 → 真 404」「方法用错 → 真 405」，以及**请求体不是合法 JSON / 缺必填参数 / 参数类型不对 → 400 而不是 500**） |
| `UserAdminTest` | 21 | 用户管理全部接口 + 自我保护 + 分页夹取 + **禁用/删除/降级后的 token 即时失效** |
| `ArticleAdminTest` | 26 | 后台文章增删改查、草稿隔离、状态流转、权限、逻辑删除（用 `JdbcTemplate` 直查物理行） |
| `ArticlePublicTest` | 15 | 前台列表与详情、只返回已发布、分页边界、排序白名单 |
| `ArticleCacheTest` | 13 | 列表缓存：第二次走缓存、四个写操作都让缓存失效、TTL 区间、空结果也缓存（防穿透）、key 归一化 |
| `ArticleStatsTest` | 8 | 站点统计：只算已发布、逻辑删除不计入、空库不报错、走缓存、写操作让缓存失效 |
| `ArticleArchiveTest` | 7 | 归档：只含已发布（草稿不出现）、按年月分组、月份与月内都倒序、空数据返回空分组、匿名可访问、走缓存且写操作后失效、key 带版本号且有 TTL |
| `ArticleRssTest` | 7 | RSS 数据：按时间倒序、只含已发布、**带正文**（与列表接口最本质的区别）、上限 20 篇且挤掉的是最早那篇、匿名可访问、走缓存、key 带版本号且有 TTL |
| `ArticleViewCountTest` | 10 | 浏览量：Redis 计数、累加不丢、定时批量落库、落库后增量清零 |
| `ArticleDetailCacheTest` | 11 | 详情缓存：走缓存、**浏览量不被冻住**、写操作后立刻更新、下架即 404、自愈重建、**12 线程并发只查库 1 次（防击穿）**、TTL 有效 |
| `ArticleIdempotencyTest` | 5 | 接口幂等：同键两次只创建一篇且返回同一 id、不同键各自创建、不带键保持旧行为、处理中返回 429、失败后能重试 |
| `ArticleIndexTest` | 7 | 索引契约：V2/V3 迁移确实执行、列顺序正确、老索引没被误删、三条查询（数据 / 排序 / COUNT）都能用上对应索引 |
| `UploadAdminTest` | 23 | 上传的**图片与音频两套规则**：图片（类型/大小白名单、UUID 重命名、非管理员 403、**能被匿名 GET 到，且 `HEAD` 也要放行** —— ㉓ 带反向断言"`HEAD /user/page` 仍是 401"）；音频（`type=audio` 传 mp3 成功且**文件真的落盘、返回的 url 能匿名取到**、音频接口拒 png、默认接口拒 mp3、**大小上限按 type 分流**、超音频上限被拒、未知 type 被拒、音频同样只有管理员能传、`type` 大小写与空格容错、两个前缀各自取自配置）；另两条守住"存储实现只有一种"与"配置改名后前缀仍生效" |
| `ArticleAttachmentTest` | 18 | 文章附件：**上传**（16 种白名单、返回 `{url,name,size}`、名字清洗与截断、超单文件上限被拒且不落盘、**html/htm/svg/xml/js/mjs/css 逐个被拒 + 白名单本身也不许出现它们**）、**数字钉死**（读 `application.properties` 原文核对 10MB / 100MB / 5GB / multipart 105MB）、**总容量护栏**（调成 6KB：压满后再传 1 字节也被拒，且提示里带"已用"）、**三套规则互不放宽**（图片/音频都拒 pdf、未知 type 报错）、**保存**（随表单落库、详情带 attachments、空数组 = 清空）、**整体替换**（旧行消失、新行出现、**被移除的文件真的被删**）、**校验**（数量 20 / 名字 100 字 / size 上限 / url 必须是本站地址，且**校验先于写入**：失败时原附件分毫未动）、**级联删除**（删文章 → 附件行 + 附件文件 + 封面 + 正文图全清；文章行仍是逻辑删除）、**★ 共用的图不误删**（两篇共用正文图与封面，删一篇后共用的留着、独占的删掉、另一篇仍打不开得开）、**共用附件同理**、**下载头**（附件强制 `Content-Disposition: attachment` + `nosniff`；图片与音频仍内联，附件目录里的 mp3 也强制下载） |
| `AbstractIntegrationTest` | 0 | **基类（没有用例，占一行是为了让"行数 = 测试类文件数"这条能对账）**：singleton 容器模式起 MySQL/Redis、`@DynamicPropertySource` 注入连接、每个用例前重置限流器、`@Transactional` 自动回滚 |
| `LogoutTokenTest` | 11 | 登出后旧 token 立即失效（jti 黑名单）、未登出的不受影响 |
| `SecurityHeadersTest` | 7 | 四个安全响应头，含 401 与上传响应两条易漏路径 |
| `MetricsEndpointTest` | 8 | 指标端点：Prometheus 格式与内容、未开放的端点确实不可达、登录/登出/浏览量落库指标真的会涨 |
| `RateLimitTest` | 5 | 接口限流（Resilience4j 注解式）：配额用完返回 **HTTP 429 + code 429**、**限流不改变正常路径**（配额内的请求仍是正常业务响应）、文章列表同样受保护、**重置配额后就恢复**（说明是"窗口内计数"而不是永久封禁）、**两个限流实例真的被注册**（防注解名字写错导致限流静默失效 —— 那是最难发现的一种失败） |
| `TracingTest` | 5 | 链路追踪：Tracer 可用、日志 traceId 与响应头 `X-Trace-Id` 一致、两次请求不重复、响应头存在 |
| `PaginationLimitTest` | 2 | 分页全局上限（从 Mapper 层验证插件兜底，接口层测不到） |
| `ProfileDevConfigTest` | 6 | dev 环境行为：Swagger 开着 / SQL 日志 / 跨域白名单 |
| `ProfileProdConfigTest` | 3 | prod 环境行为：Swagger 关闭 / 凭据必须来自环境变量 |
| `AdminBootstrapInitTest` | 5 | 管理员初始化引导（空库直接启动也能进后台） |
| `TagTest` | 14 | 标签：前台标签云（带已发布文章数、草稿不计）、后台增删改、**物理删除**（原生 SQL 数物理行）、删标签连带清关联、名字查重与首尾空格、权限、缓存命中与失效 |
| `ArticleTagTest` | 12 | 文章打标签与按标签筛选：覆盖式语义（换标签旧的不留）、**校验先于写入**（失败不动原有标签）、去重、草稿不泄漏、标签不存在返回空页、列表带标签、删文章清关联、打标签后标签云计数立刻 +1 |
| `CommentTest` | 17 | 评论：游客可发但**默认待审核**、传 `status=0` 也拿不到待审核内容、审核通过才可见、草稿不能评论、XSS 转义、**常见符号（→ — … ©）不被转义**、**极端输入先转义再截断不报 500**、响应里带 `createTime`、公开响应体不含邮箱与 IP、后台带邮箱/IP/文章标题、审核参数校验、逻辑删除（原生 SQL 验物理行）、权限、限流 429、正序与分页 |
| `CategoryAdminTest` | 11 | 分类：新建/编辑/改名后前台立刻生效、重名与空格、**分类下有文章时拒绝删除**、**删掉后同名分类能重建**（名字被释放）、**文章逻辑删除后分类就能删**（守"手写 COUNT 要自己加 deleted=0"）、权限、前台仍公开 |
| `OperationLogTest` | 20 | 操作审计：管理动作都留痕（含标签、评论、分类、友链、项目、收藏、音乐的增删改，以及关于页与站点设置的保存）、发表评论**不**记、回滚与失败不记账、审计行不含明文密码 |
| `FriendLinkTest` | 20 | 友链：前台只含"显示"的（隐藏的不出现）、排序 sort + id 双段稳定、后台增删改（**清空可选字段真的写成 NULL**）、非法 URL（含 `javascript:`）与非法状态 400、逻辑删除（原生 SQL 验物理行与 deleted）、权限 401/403、缓存命中 / 写操作推进版本号 / key 带版本号且有 TTL |
| `ProjectTest` | 23 | 项目：同友链那一整套，外加**本项目特有**的"在线地址与仓库地址至少填一个"（两个都空被拒、只填一个合法、编辑时把两个都清空被拒**且库里的旧值分毫未动**）、封面与简介与技术栈的长度边界 |
| `FavoriteTest` | 21 | 收藏：同友链那一整套，外加 **`category` 是自由文本分组名**的语义（带斜杠合法、同一分组可多条、空串归一化成 NULL、只卡长度 50）、地址必填与格式白名单、备注与标题的长度边界 |
| `AboutTest` | 16 | 关于页：**单条记录的三条不变量**（反复保存永远只有一行 / 那一行被物理删掉后 GET 仍 200 返回空壳且保存能写回 / POST 是真 405）、迁移脚本已插好那一行、昵称与头像与 GitHub 与邮箱与长文本的长度与格式边界、一次性清空全部可选字段真的写成 NULL、权限 401/403、缓存命中与保存后失效 |
| `MusicTest` | 23 | 音乐：前台只含"显示"的（隐藏的不出现）、**排序两段稳定（sort 升序、sort 相同按 id 升序）**、列表为空返回空数组而不是 404、响应字段齐全（歌词是 LRC 原文、换行不丢）、**音频地址两种形态都合法（`/uploads/music/…` 与 http(s) 外链）而 `javascript:` 被拒**、曲名/歌手/歌词/地址/状态的长度与格式边界、约 5000 字歌词原样存取（守 TEXT 列不被改成短列）、增删改与逻辑删除（原生 SQL 验物理行与 deleted）、清空歌手与封面与歌词真的写成 NULL、权限 401/403、缓存命中 / 写操作推进版本号 / key 带版本号且 TTL 落在 300~360 秒（含抖动） |
| `MusicFileCleanupTest` | 5 | 删歌时的音频文件清理：**外链地址不碰任何本地文件**（且不报错）、**独占的文件真的被删**（数据库行仍是逻辑删除 `deleted = 1`）、**★ 两首歌共用一个 mp3：删一首文件还在、删到最后一首才真删**、**★ 文章正文引用该 mp3 时删歌不删文件**（文章删掉后才清）、**★ 反向：歌还在时删文章不能删掉这个文件**（同时断言文章独占的封面确实被清，证明清理真的跑了而不是没执行） |
| `SiteSettingTest` | 22 | 站点设置：**单条记录的三条不变量**（反复保存永远只有一行 / 那一行被物理删掉后 GET 仍 200 给默认值且保存能写回 / POST 是真 405）、**迁移种子值读脚本原文核对**（守"上线这个功能本身不改变站点外观"）、七个字段逐个"能改、能读到、能清空"、**公安网安备案号（V13 新列）**：库里真有这一列（`information_schema` 查类型 / 可空 / 紧跟 `icp_number`）、脚本里**没有任何种子值**、能填能读能清空、50 字收下 51 被拒、保存后前台立刻读到新值（缓存 bump）、缺行兜底里是 `null`（展示类字段不编值）、**每页条数上限跟文章接口同为 50**（超了会变成"保存成功但不生效"）、**评论开关 0/1 与布尔的转换两个方向都断言**、**⑰ 评论总开关在后端真的生效**（关掉后 `POST /comment` 返回 code 403，且库里没有多出记录；再打开又能发）、权限 401/403、缓存命中与保存后失效 |
| `DeploymentMemoryBudgetTest` | 9 | 部署配置契约（**读 `docker-compose.prod.yaml` 与 `Dockerfile`，不起 Spring 上下文**）：四个服务都必须有 `mem_limit`、上限之和要给宿主机留余量、**Dockerfile 的 ENTRYPOINT 不许带 JVM 参数**（带了会静默覆盖 `JAVA_TOOL_OPTIONS`）、三个硬上限之和必须小于 `mem_limit`、堆转储与 GC 日志必须落在挂了卷的目录里、redis 必须是 `volatile-lru`（`allkeys-lru` 会淘汰没有 TTL 的浏览量增量 = 真丢数据）、mysql 必须关 `performance_schema` 且缓冲池是 128M 的整数倍（否则被静默取整成 256M）、**数据服务的镜像必须钉住小版本**（浮动 `mysql:8` 实测拉到了四年前的 8.0.27）、YAML 开启重复键检查 |
| `ActuatorExposureContractTest` | 4 | 管理端点的暴露面契约（`/actuator/prometheus` 在应用层是**放行**的，只能靠部署挡住）：`location /api/` 必须剥掉前缀（这也是 `/api/actuator/` 会命中后端根路径的根因）、必须有把 `/api/actuator/` 返回 404 的 location、mysql/redis 不发布任何端口且 backend/frontend 只绑 `127.0.0.1`、上线核对清单里这一项必须写出**可判定的**预期结果（404） |
| `YiguixingtuApplicationTests` | 4 | 冒烟：上下文加载、数据库读写、JWT 签发解析、UserDetailsService、BCrypt |
| **合计** | **463** | |

所有测试类都继承 `AbstractIntegrationTest`，它负责：
启动容器 → 把容器地址通过 `@DynamicPropertySource` 注入 Spring → 事务自动回滚。

> 📌 **容器为什么用 static 代码块启动，而不是 `@Testcontainers` + `@Container`？**
> `@Testcontainers` 扩展管理的是"每个测试类"的生命周期：每个类都重新 start/stop 一次容器。
> 而 Spring 的 ApplicationContext 是**跨测试类复用**的，数据源在第一个类时就绑到了
> 第一个容器的端口上——第二个类起来的是新容器（新端口），复用的却是老 context，
> 结果就是连一个已经被 stop 掉的容器。
> 用 static 代码块（singleton container pattern）只拉一次、全程共用，容器的回收交给
> Testcontainers 的 Ryuk（JVM 退出时自动清理），不需要手写 `@AfterAll`。

**写测试的五条约定（不是随便定的）：**

1. **断言要落到数据库**。用 `JdbcTemplate` 直查原生 SQL 验证，而不是只断言 HTTP 状态码。
   比如验证"删除文章是逻辑删除"，必须确认**物理行还在且 `deleted = 1`**——
   因为 `@TableLogic` 会自动过滤，用 Mapper 是查不出来的。
2. **分页/总数类断言必须先用唯一标记圈定范围**（现有用 `ZZT + 纳秒时间戳`）。
   否则库里已有的真实数据会让 `total` 断言飘忽不定，**这种测试比没有还糟**。
3. **测试数据自己造，不依赖库里预先有什么数据**。
   以前有测试写着"前提：user 表里有 username='yigalaxy'"，换成容器里的空库后这种写法必然失败。
   正确的做法是在 `@BeforeEach` 里插一个自己的用户，用它的**真实 id** 签发 token。
4. **`@Transactional` 让每个用例跑完自动回滚**，不污染数据库。
   注意它**回滚不了 Redis**，所以涉及缓存的用例要在 `@AfterEach` 里手动清 key。
5. **测"提交之后才发生的事"，必须先把测试事务关掉**（`@Transactional(propagation = NOT_SUPPORTED)`）。
   测试事务永远只回滚、不提交，`@TransactionalEventListener(AFTER_COMMIT)` 就永远等不到
   那个 commit —— 功能明明是好的，用例却红在"没生效"，还看不出是环境的锅。
   关掉之后数据要自己清理（`@AfterEach` 里按唯一标记物理删除），
   异步落库的断言还要**轮询**等而不是 `sleep` 固定值（见 `OperationLogTest`）。
6. **⚠️ "全表只有一行"的共享数据（`about` / `site_setting`），不要去查库断言它的具体值。**
   上面第 5 条与这一条会撞在一起：审计用例必须真的提交，而它写的恰恰就是那张单行表，
   于是"库里此刻的值"**取决于测试执行顺序**（所有测试类共用同一个 ApplicationContext
   与同一个数据库）。踩过一次：`SiteSettingTest` 断言"站点名 = 种子值 `亿轨星途`"，
   实际读到的是 `OperationLogTest` ⑳ 刚写进去的 `<mark>-审计站点名`。
   ⇒ 对单行表只用两种断言：
   · **结构性的**（恰好一行、`id` 恒为 1、NOT NULL 列非空、取值落在合法区间）—— 与谁先跑无关
   · **种子值本身，去读迁移脚本原文**（classpath 上的 `V12__xxx.sql`），
     因为那本来就是"脚本里写了什么"的事实，而不是"数据库此刻是什么"。
     同一个做法见 `ProfileProdConfigTest`（读 `application-prod.properties` 原文核对占位符）

## 许可证

尚未指定许可证。若计划开源，建议补充一份 MIT 的 `LICENSE` 文件。
