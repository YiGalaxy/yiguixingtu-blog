# yiguixingtu — 个人博客系统（后端）

[![CI](https://github.com/YiGalaxy/yigalaxy-blog-new/actions/workflows/ci.yml/badge.svg)](https://github.com/YiGalaxy/yigalaxy-blog-new/actions/workflows/ci.yml)
![Java](https://img.shields.io/badge/Java-17-blue)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-4.1.1-brightgreen)
![Tests](https://img.shields.io/badge/tests-285%20passing-success)
![Coverage](https://img.shields.io/badge/coverage-90%25-brightgreen)

> 基于 Spring Boot 4 + MyBatis-Plus + JWT 的个人博客后端服务
> Spring Boot 4.1.1 / Java 17 / MySQL 8 / Redis 7
>
> **285 个集成测试全部通过**（覆盖行 90.5%），测试自带 MySQL / Redis 容器，clone 下来即可验证。

## 项目简介

**yiguixingtu** 是一个个人博客系统的后端服务，已实现 **认证与用户管理**、**文章管理**、
**分类**、**标签**、**评论与审核**、**操作审计** 等模块，
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
| 图片存储 | 服务器本地磁盘 + 具名卷持久化；**不接对象存储**（理由与"将来怎么换"见「文件上传」章节） |
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

**基础设施**
- 统一返回 `{code, message, data}`
- 全局异常处理（业务异常 / 参数校验 / 认证失败 / 账号禁用 / 权限不足 / 兜底）
- 分页与排序参数安全处理（见「接口安全约定」）
- 自动生成 OpenAPI 接口文档
- **接口限流（两层）**：应用层用 Resilience4j 注解式限流（登录 5 次/分钟、
  前台列表 300 次/分钟、发表评论 20 次/分钟），Nginx 那层按客户端 IP 限流；
  被限流返回 **429** —— 两层的分工与数值理由见「部署」章节
- **操作审计**：文章的增 / 改 / 发布下架 / 删除，用户的启用禁用 / 改角色 /
  重置密码 / 删除，标签的增 / 改 / 删，评论的审核 / 删除 —— 共 13 类管理动作
  全部留痕（操作人、来源 IP、traceId、改动内容快照）；
  **业务提交之后才异步落库**，回滚掉的操作不会被记下来 —— 见「操作审计」
- **Flyway 数据库版本化迁移**：空库启动自动建表，表结构只有一份定义
- **Testcontainers 容器化集成测试**：测试自带数据库与 Redis，clone 下来就能验证
- **GitHub Actions 持续集成**：每次 push / PR 自动构建、跑测试、出覆盖率报告
- **图片上传**：扩展名白名单 + 大小限制 + UUID 重命名 + 按日期分目录；
  图片存服务器本地磁盘，并用**具名卷**持久化
- 集成测试 **31 个类 285 个用例**，行覆盖率 **90.5%**

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
│   ├── metrics/BusinessMetrics     # 自定义业务指标（浏览量落库 / 登录 / token 拉黑）
│   └── exception
│       ├── BusinessException       # 自定义业务异常
│       └── GlobalExceptionHandler  # 全局异常处理器（6 类异常）
├── config
│   ├── MybatisPlusConfig           # 分页插件（全局上限 100 兜底）
│   ├── SecurityConfig              # Spring Security 过滤链 + JWT + CORS + 401/403 JSON + 安全响应头
│   ├── WebMvcConfig                # /uploads/** 映射到本地存储目录
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
│   ├── AuditTarget                 # 操作对象类型（ARTICLE / USER）
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
│   ├── service/ArticleService(+Impl)
│   ├── entity/Article
│   ├── mapper/ArticleMapper
│   ├── cache/ArticleViewCounter    # 浏览量 Redis 计数器（详情页只 INCR，不写库）
│   ├── cache/PublishedArticleCache # 详情页那份库数据的可缓存读取（防击穿）
│   ├── cache/ArticleCacheVersion   # 缓存版本号（列表与详情各一个，写操作一起推进）
│   ├── task/ViewCountSyncTask      # 定时把 Redis 增量批量落库
│   └── dto/                        # ArticleForm / ArticleQuery / ArticleVO
├── upload
│   ├── controller/UploadController # POST /upload（ADMIN）
│   ├── service/UploadService       # 类型白名单 + UUID 重命名 + 按日期分目录
│   ├── FileStorage                 # 存储接口（依赖倒置：将来换对象存储时 UploadService 不用改）
│   ├── LocalFileStorage            # 本地磁盘（唯一实现，见「文件上传」章节）
│   └── UploadProperties            # 上传配置绑定
├── category
│   ├── CategoryController                 # 分类列表（公开）
│   ├── AdminCategoryController            # 后台：增删改（删时会检查是否还有文章在用）
│   ├── service/CategoryService(+Impl)     # ⚠️ 删除时把名字改写成 原名#deleted#id
│   ├── entity/Category
│   ├── mapper/CategoryMapper              # 含一条手写 COUNT（要自己写 deleted = 0）
│   └── dto/                               # CategoryVO / CategoryForm
└── tag
    ├── TagController                      # 前台：标签列表（公开，带文章数）
    ├── AdminTagController                 # 后台：标签增删改查（ADMIN）
    ├── service/TagService(+Impl)          # 含"给文章打标签"与缓存失效
    ├── entity/Tag                         # ⚠️ 没有 @TableLogic：标签是物理删除
    ├── mapper/TagMapper                   # 含两个手写聚合查询（文章数 / 批量查标签）
    ├── mapper/ArticleTagMapper            # article_tag 关联表（纯关联，没有实体）
    └── dto/                               # TagForm / TagVO
└── comment
    ├── CommentController                  # 前台：看评论 + 发评论（**游客可用**，发表有限流）
    ├── AdminCommentController             # 后台：审核 / 删除（ADMIN）
    ├── service/CommentService(+Impl)      # 状态写死、HTML 转义、来源 IP
    ├── entity/Comment                     # 三态：0待审核 1已通过 2已拒绝
    ├── mapper/CommentMapper
    └── dto/                               # CommentForm / CommentQuery / CommentVO / AdminCommentVO

src/main/resources
├── application.properties
├── logback-spring.xml            # 日志：按天滚动 + 保留 15 天 + traceId 槽位
└── db/migration
    ├── V1__init.sql                     # user / category / article 建表
    ├── V2__add_article_sort_index.sql   # 列表排序的复合索引（附实测依据）
    ├── V3__add_count_covering_index.sql # 分页 COUNT 的覆盖索引（压测压出来的）
    └── V4__create_operation_log.sql     # 操作审计表（刻意没有逻辑删除，见脚本内说明）

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
├── UploadAdminTest                 # 封面上传（类型/大小校验、权限）
├── LogoutTokenTest                 # 登出后旧 token 立即失效（jti 黑名单）
├── SecurityHeadersTest             # 四个安全响应头
├── MetricsEndpointTest             # 指标端点与自定义业务指标
├── TracingTest                     # 链路追踪：traceId 进日志 + 进响应头
├── OperationLogTest                # 操作审计：8 类写操作都留痕 / IP 取真实客户端 / 回滚与失败不记账
├── PaginationLimitTest             # 分页全局上限（从 Mapper 层验证插件兜底）
├── ProfileDevConfigTest            # dev 环境行为：Swagger 开着 / SQL 日志 / 跨域白名单
├── ProfileProdConfigTest           # prod 环境行为：Swagger 关闭 / 凭据必须来自环境变量
├── AdminBootstrapInitTest          # 管理员初始化引导（空库也能进后台）
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

#### 文件上传（封面图存在服务器磁盘上）

```properties
app.upload.local-dir=${UPLOAD_LOCAL_DIR:./uploads}
app.upload.base-url=${UPLOAD_BASE_URL:http://localhost:8082}
app.upload.allowed-extensions=jpg,jpeg,png,gif,webp
app.upload.key-prefix=${UPLOAD_KEY_PREFIX:cover}
app.upload.max-size=5MB
```

**本项目用本地磁盘存封面图，不用对象存储。** 理由很简单：单台 ECS +
个人博客的图片量级，本地磁盘完全够用，**少一个外部依赖就少一处会失败的地方**
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

- **按年月分目录**：单目录里堆几万个文件，`ls`/备份/排查都会变得很难受
- **UUID 重命名**：用原名会撞名、还会把用户的文件名暴露在 URL 里

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

共 **36 个接口**。「是否需要登录」一列指**访问该接口本身**的要求，
具体到角色见下方「接口 × 角色权限矩阵」。

| # | 方法 | 路径 | 说明 | 是否需要登录 |
|---|------|------|------|:---:|
| 1 | POST | `/auth/register` | 用户注册 | 否 |
| 2 | POST | `/auth/login` | 登录（返回 token，**有限流**：5 次/分钟） | 否 |
| 3 | POST | `/auth/logout` | 退出登录（把当前 token 拉黑，幂等） | 否 |
| 4 | GET | `/auth/me` | 获取当前登录用户 | 是 |
| 5 | GET | `/article/page` | 前台文章分页列表（仅已发布，**走 Redis 缓存 + 限流**；支持 `tagId` 标签筛选） | 否 |
| 6 | GET | `/article/{id}` | 前台文章详情（仅已发布，返回里带 `tags`） | 否 |
| 7 | GET | `/article/stats` | 站点统计：文章数 / 总浏览量 / 分类数（首页那三个数字，**只算已发布**） | 否 |
| 8 | GET | `/article/archive` | 归档：已发布文章**按年月分组**（最新的月份在前），走 Redis 缓存 | 否 |
| 9 | GET | `/article/rss` | RSS 数据：最近 20 篇已发布文章的正文（前端用它拼 `feed.xml`），走 Redis 缓存 | 否 |
| 10 | GET | `/category/list` | 分类列表 | 否 |
| 11 | GET | `/tag/list` | 标签列表（标签云，**每个标签带已发布文章数**，走 Redis 缓存） | 否 |
| 12 | GET | `/comment/list` | 某篇文章的评论（**只返回已通过的**，必须带 `articleId`） | 否 |
| 13 | POST | `/comment` | 发表评论（**游客可用，默认待审核**；有限流：20 次/分钟） | 否 |
| 14 | GET | `/user/page` | 用户分页查询 | 是（ADMIN） |
| 15 | PUT | `/user/{id}/status` | 启用 / 禁用用户 | 是（ADMIN） |
| 16 | PUT | `/user/{id}/role` | 修改用户角色 | 是（ADMIN） |
| 17 | PUT | `/user/{id}/password` | 重置用户密码 | 是（ADMIN） |
| 18 | DELETE | `/user/{id}` | 删除用户（逻辑删除） | 是（ADMIN） |
| 19 | GET | `/admin/article/page` | 后台文章分页（含草稿，多条件筛选） | 是（ADMIN） |
| 20 | GET | `/admin/article/{id}` | 后台文章详情（含正文） | 是（ADMIN） |
| 21 | POST | `/admin/article` | 新增文章（可带 `tagIds` 打标签；**可选 `Idempotency-Key` 请求头防重复提交**） | 是（ADMIN） |
| 22 | PUT | `/admin/article/{id}` | 编辑文章（`tagIds` 是**覆盖式**语义） | 是（ADMIN） |
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
| 36 | POST | `/upload` | 上传图片（封面图），返回可访问 URL | 是（ADMIN） |

**共 36 个接口。** 接口文档（`/v3/api-docs`、`/swagger-ui/**`、`/swagger-ui.html`）也无需登录。
用本地磁盘存储时，上传的图片通过 `GET /uploads/**` 公开读取（无需登录）。

## 接口 × 角色权限矩阵

三种访问身份：**游客**（不带 token）、**普通用户**（GUEST + 合法 token）、**管理员**（ADMIN + 合法 token）。

| 接口 | 游客 | GUEST | ADMIN |
|------|:---:|:---:|:---:|
| `POST /auth/register` | ✅ | ✅ | ✅ |
| `POST /auth/login` | ✅ | ✅ | ✅ |
| `GET /article/page` | ✅ | ✅ | ✅ |
| `GET /article/{id}`（已发布） | ✅ | ✅ | ✅ |
| `GET /article/{id}`（草稿） | ❌ 404 | ❌ 404 | ❌ 404（走后台接口） |
| `GET /category/list` | ✅ | ✅ | ✅ |
| `GET /tag/list` | ✅ | ✅ | ✅ |
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
| `POST /upload` | ❌ 401 | ❌ 403 | ✅ |
| `GET /uploads/**`（本地存储的图片） | ✅ | ✅ | ✅ |

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
| 403 | 无权限访问 / 账号已被禁用 |
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
| `/actuator/prometheus` | 匿名可达，但**只绑在 127.0.0.1** | Prometheus 抓取指标 |

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
> | Nginx 只反代 `/api/` | README「Nginx 反向代理」那段的配置，没有 `/actuator` 的 location |
> | 云服务器安全组不开 8082 | 阿里云控制台 |
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
| 图片存储 | 存在服务器磁盘上（具名卷 uploads_data），不用对象存储；/uploads/** 只放行 GET |
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

# ---- 前端容器用的后端地址（浏览器访问的那个） ----
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

# 看状态：三个都应该是 healthy
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

**重启 Docker 服务后会自动恢复**：三个服务都配了 `restart: unless-stopped`。

### 3. Nginx 反向代理（含第一层限流）

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

    # 前端请求后台接口时走的路径（供前端容器使用，不对外）
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
| 9 | `/actuator/prometheus` 没有被公网看到 | 访问 `https://你的域名/api/actuator/prometheus` 应当拿不到指标（Nginx 只反代 `/api/`，正常情况打不到） |
| 10 | **上传的图片在容器重建后还在** | 后台上传一张封面 → `docker compose -f docker-compose.prod.yaml up -d --force-recreate backend` → 再打开那篇文章，图片应当还能显示（守"上传目录有没有真的挂到卷上"） |
| 11 | 图片地址是外网可访问的 | 右键封面图「复制图片地址」，在无痕窗口打开应当能看到图（守 `UPLOAD_BASE_URL` 填的是浏览器能访问到的地址） |
| 12 | **背景视频/音乐能播** | `curl -I https://你的域名/media/bg-music.mp3` 应当返回 **200**（守"前端仓库 `static-media/` 里的文件真的传到了 `/var/www/media/`"——它们不在构建产物里，忘了传就只有 404） |
| 13 | **审计表真的在记** | 后台改一下某篇文章 → `docker exec yiguixingtu-mysql mysql -uroot -p"$MYSQL_ROOT_PASSWORD" yiguixingtu -e "SELECT * FROM operation_log ORDER BY id DESC LIMIT 3"` 应当能看到那条记录，且 `ip` 是真实访问者地址（不是 `127.0.0.1`） |
| 14 | **SEO 三件套都对** | `curl -s https://你的域名/ \| grep canonical` 应当是**你的真实域名**；`curl -I https://你的域名/sitemap.xml` 返回 200 且 `Content-Type` 是 XML；`curl -s https://你的域名/robots.txt` 里的 `Sitemap:` 也是你的域名；`curl -s https://你的域名/feed.xml \| head -c 200` 能拿到 RSS（守 `PUBLIC_SITE_URL` 有没有填对 —— 填错不会报错，只会让搜索引擎/订阅器指向别的域名） |
| 15 | **首页 HTML 里有文章** | `curl -s https://你的域名/ \| grep -o '<h3[^>]*>[^<]*'` 应当能看到文章标题 —— 首页是 SSR 的，源码里就该有内容；如果只有 `<div id="__nuxt"></div>`，说明 SSR 没生效（那是纯客户端渲染的表现） |
| 16 | **评论链路是通的** | 打开一篇文章发一条评论 → 前端提示"等待审核"、前台列表里**看不到** → 后台点通过 → 前台刷新能看到（守"待审核状态写死在前台查询里"这条规则真的生效） |

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
| **前端 SSR（容器间互访）** | 首页 HTML 28KB，**含刚发的文章标题**与 canonical；文章页 82KB **含那条评论**；标签是真实内链 `<a href="/?tagId=1">`（可被爬虫跟随） |
| sitemap / robots | `/sitemap.xml` 200 且含 `/article/1`；`/robots.txt` 200 且 `Sitemap:` 用的是配置的域名（守 `PUBLIC_SITE_URL`） |
| 操作审计 | 上面那些管理动作在 `operation_log` 里逐条留痕（CREATE_CATEGORY / CREATE_TAG / CREATE_ARTICLE / UPDATE_COMMENT_STATUS） |
| 上传落卷 | 上传的封面写进 `/app/uploads/cover/2026/09/…png`，`GET` 返回 200；**`--force-recreate` 重建容器后文件还在、还能访问**（守"具名卷有没有真的挂上"） |
| 应用层限流 | 连续 6 次登录 → `200,200,200,200,429,429`（配额 5 次/分钟，返回**真 HTTP 429**） |

> 演练完 `docker compose down -v` 把演练用的容器与卷全部删掉，
> 开发环境（8082 / 3310 / 6380）不受任何影响。

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

```bash
# —— 备份 MySQL（建议加进 crontab 每天跑一次）——
docker exec yiguixingtu-mysql sh -c \
  'exec mysqldump -uroot -p"$MYSQL_ROOT_PASSWORD" --single-transaction --databases yiguixingtu' \
  > backup_$(date +%F).sql

# —— 恢复 ——
docker exec -i yiguixingtu-mysql sh -c \
  'exec mysql -uroot -p"$MYSQL_ROOT_PASSWORD"' < backup_2026-09-10.sql
```

两个参数值得说明：
- `--single-transaction`：备份期间不锁表（InnoDB），站点不用停机
- 恢复前**先停掉后端**，避免写入和恢复互相打架：
  `docker compose -f docker-compose.prod.yaml stop backend`

#### ⚠️ 别忘了备份上传的图片

封面图存在服务器的**具名卷** `yiguixingtu-prod_uploads_data` 里，
它和数据库是两回事 —— **只备份 MySQL 的话，数据库恢复出来了、图片却是空的**，
文章里全是裂图。

```bash
# —— 备份图片（把卷里的文件拷到当前目录的 uploads_backup/）——
docker run --rm \
  -v yiguixingtu-prod_uploads_data:/data:ro \
  -v "$PWD":/out \
  alpine sh -c 'mkdir -p /out/uploads_backup && cp -r /data/. /out/uploads_backup/'

# —— 恢复图片（把备份拷回卷里）——
docker run --rm \
  -v yiguixingtu-prod_uploads_data:/data \
  -v "$PWD":/out \
  alpine sh -c 'cp -r /out/uploads_backup/. /data/'
```

> **备份要验证过才算备份**。建议演练一次：拷一份库出来、导进一个新库、
> 启动应用确认文章都在；图片也一样 —— 把备份拷进一个空卷，确认还能显示。
> 没验证过的备份，真出事时大概率用不了。

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
| 行覆盖 | **90.5%**（1,504 / 1,662） |
| 方法覆盖 | **97.1%**（300 / 309） |
| 指令覆盖 | **90.7%**（6,593 / 7,273） |
| 分支覆盖 | 68.4%（373 / 545） |

> 分支覆盖率明显低于行覆盖率，是因为大量的**参数校验分支、异常兜底分支、
> 空值判断分支**不会被每个用例都走到——这是正常的，不必为了刷数字硬凑用例。
> 真正该关心的是关键路径有没有被覆盖：认证、权限、草稿隔离、逻辑删除这几条都在 100% 附近。

覆盖率**只统计本项目自己的代码**（JaCoCo 配了 `includes: com/yigalaxy/yiguixingtu/**`），
不把依赖库算进去。

### 持续集成（GitHub Actions）

`.github/workflows/ci.yml`，在 **push 到 master** 和 **PR** 时触发：

1. 装 JDK **17**（与 `pom.xml` 的 `java.version=17` 一致）
2. `./mvnw -B verify` —— 构建 + 跑 285 个用例 + 出覆盖率
3. 上传 `surefire-reports` 与 `jacoco-report` 两个 artifact（`if: always()`，测试失败时报告最需要看）

**CI 上不需要配置任何 MySQL / Redis 服务** —— 测试用 Testcontainers 自己拉起容器，
GitHub 的 ubuntu runner 自带 Docker。这正是把测试容器化的价值所在。

### 测试环境自带容器

> ✅ **测试不需要事先启动任何服务。** 测试启动时由 **Testcontainers**
> 自己拉起 MySQL 与 Redis 容器、跑完自动销毁，所以
> **即使先执行 `docker compose down`，`mvn test` 也照样全绿** —— 只需要本机装了 Docker。
>
> 这意味着：任何人 clone 下来就能验证这 285 个用例，CI 上也能跑
> （在此之前，测试直连本机 3310/6380，换台机器不先起容器就全红，CI 更是跑不了）。

**31 个测试类，285 个用例，全部通过：**

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
| `UploadAdminTest` | 12 | 封面上传：类型/大小白名单、UUID 重命名、非管理员 403；另两条守住"存储实现只有一种"与"配置改名后前缀仍生效" |
| `LogoutTokenTest` | 11 | 登出后旧 token 立即失效（jti 黑名单）、未登出的不受影响 |
| `SecurityHeadersTest` | 7 | 四个安全响应头，含 401 与上传响应两条易漏路径 |
| `MetricsEndpointTest` | 8 | 指标端点：Prometheus 格式与内容、未开放的端点确实不可达、登录/登出/浏览量落库指标真的会涨 |
| `TracingTest` | 5 | 链路追踪：Tracer 可用、日志 traceId 与响应头 `X-Trace-Id` 一致、两次请求不重复、响应头存在 |
| `PaginationLimitTest` | 2 | 分页全局上限（从 Mapper 层验证插件兜底，接口层测不到） |
| `ProfileDevConfigTest` | 6 | dev 环境行为：Swagger 开着 / SQL 日志 / 跨域白名单 |
| `ProfileProdConfigTest` | 3 | prod 环境行为：Swagger 关闭 / 凭据必须来自环境变量 |
| `AdminBootstrapInitTest` | 5 | 管理员初始化引导（空库直接启动也能进后台） |
| `TagTest` | 14 | 标签：前台标签云（带已发布文章数、草稿不计）、后台增删改、**物理删除**（原生 SQL 数物理行）、删标签连带清关联、名字查重与首尾空格、权限、缓存命中与失效 |
| `ArticleTagTest` | 12 | 文章打标签与按标签筛选：覆盖式语义（换标签旧的不留）、**校验先于写入**（失败不动原有标签）、去重、草稿不泄漏、标签不存在返回空页、列表带标签、删文章清关联、打标签后标签云计数立刻 +1 |
| `CommentTest` | 17 | 评论：游客可发但**默认待审核**、传 `status=0` 也拿不到待审核内容、审核通过才可见、草稿不能评论、XSS 转义、**常见符号（→ — … ©）不被转义**、**极端输入先转义再截断不报 500**、响应里带 `createTime`、公开响应体不含邮箱与 IP、后台带邮箱/IP/文章标题、审核参数校验、逻辑删除（原生 SQL 验物理行）、权限、限流 429、正序与分页 |
| `CategoryAdminTest` | 11 | 分类：新建/编辑/改名后前台立刻生效、重名与空格、**分类下有文章时拒绝删除**、**删掉后同名分类能重建**（名字被释放）、**文章逻辑删除后分类就能删**（守"手写 COUNT 要自己加 deleted=0"）、权限、前台仍公开 |
| `OperationLogTest` | 14 | 操作审计：管理动作都留痕（含标签、评论、分类的增删改）、发表评论**不**记、回滚与失败不记账、审计行不含明文密码 |
| `YiguixingtuApplicationTests` | 4 | 冒烟：上下文加载、数据库读写、JWT 签发解析、UserDetailsService、BCrypt |
| **合计** | **285** | |

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

## 许可证

尚未指定许可证。若计划开源，建议补充一份 MIT 的 `LICENSE` 文件。
