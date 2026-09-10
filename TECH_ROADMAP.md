# 亿轨星途 · 技术增强计划 v2（全项目扫描版）

> 依据：`master@c0c8177` 全量源码**逐文件核对**，每条都标了 `文件:行号`，不是凭印象。
> 目标：把「能跑的 CRUD 博客」补成「有技术纵深、每个决策都答得出'为什么'」的项目。
> 四条原则：**先有痛点再加技术** · **不写没做完的** · **每项都要能回答"不加会怎样"** · **能用主流现成方案就不自研**。
>
> ⚠️ 第四条是硬约束：**限流、操作日志、链路追踪、幂等、敏感词过滤，一律用业界现成的方案/库，
> 不自己写注解、切面、Lua 脚本、Filter。**
> 理由很实在：自研轮子在公司里没人这么干，面试官一眼就看出是"教程项目特征"；
> 而**"知道该用哪个现成方案、为什么选它、它的边界在哪"**才是工程判断力。
> 逐项替换见 **§20 自研 vs 主流：方案修订表**。

> **先读 §15 开发约束（硬规则）与 §16 / §18 分阶段提交清单** —— 每开发一个功能都必须走完四步：
> **先写测试代码 → 跑绿 → 同步 README → 提交**（提交信息按本仓库现有历史风格写，含【测试】与【文档】两节）。
> ⚠️ **后端（§16）与前端（§18）都要**，两边**分开提交**，做完一起**联调**（§15 R6）——对齐关系见 **§19 颗粒度对齐表**。

**本版相对上一版的变化**

- **§0.1 重写**：上一版把整个项目压缩成 12 行"已有能力"，看浅了。本版**逐文件、逐接口重新核对**，补上了权限三态、逻辑删除的唯一索引处理、旧 token 即时撤销、分页上限保护、DTO+Service 双重校验等**已经落地并有用例兜住**的设计。
- **§0.2 重新定性**：上一版把 12 项一律写成"问题"。本版加了**性质标注**（🔴真缺口 / 🟡一致性 / 🟠有意的权衡 / 🔵优化项）——真正会造成故障的只有 4 项，其中 #5（HTTP 200 + body code）是**有意约定，乱改是破坏性改动**。
- **顺序调整**：既然"代码写得比文档和交付好"，第一步改成 **G（文档对齐）+ B1/B2（让 76 个用例能被任何人跑起来）**，而不是一上来就加 Redis。
- **E6 降级**：上一版把"越权自查"当成从零开始的漏洞排查；实际项目里自我保护规则和权限三态**都做了且有用例**，现在只缺一张 README 权限矩阵。
- 修正一处数据：上一版写"78 个用例"，精确重数（`^\s*@Test\s*$`）是 **76 个 `@Test` / 8 个测试类**。数字要经得起追问，先把这个改对。
- 新增章节：**批次 D（性能与可观测）**、**批次 E（安全与配置）**、**批次 F（前端站点）**、**§7 文档一致性**、**§12 面试追问自检**。
- 新增 **§15 开发约束（硬规则 R1–R5）** 与 **§16 分阶段提交清单**：把"每开发一个功能必须配测试代码、跑绿后才提交、提交信息按现有历史风格、分阶段原子提交、**每提交一次就同步一次 README**"落成可执行的规则，并给出后端 0.1–6.3 共 **28 个**原子提交的预写标题与测试要求。
- 新增 **§17 前端现状盘点**：逐文件读完了 `D:\Project\yiguixingtu-web`（11 个源文件 ≈ 2200 行），列出「已经实现的 11 项能力 + 还没实现的 **31 项**（带 `文件:行` 证据，按认证 / 前台 / SEO / 后台 / 工程五组分类）」，并整理出**前端需要后端新增的 8 个接口**。
- 新增 **§18 前端分阶段提交清单**：web-0 仓库抢救 → web-1 工程化 → web-2 前台补齐 → web-3 上线准备 → web-4 限流 → web-5 安全体验 → web-6 内容功能 → web-7 SEO 与性能，共 **25 个**前端原子提交。
- 新增 **§19 前后端颗粒度对齐表**：一张表把"后端提交 ↔ 前端提交 ↔ 联调验收动作"对齐，一行就是一个功能，做完两个提交 + 一次联调才算完成。
- 新增 **§1.1 新增技术栈总表**（16 项技术 × 落在哪 × 解决什么问题 × 对应提交 × 简历关键词 × 可写时机）与 **§1.2 简历口径**：把散在各批次里的技术集中成一张能"做一项勾一项"的表，并明确每一项什么时候才有资格写进简历。
- 新增 **R6 联调关卡**（后端绿测试 ≠ 功能做完）、**R7 两个仓库分开提交**、**R8 前端测试要求**；后续补充 **§17 前端现状与本计划的前端部分**。
- 🔄 **方向调整（最重要）**：新增第 4 条原则 **「能用主流现成方案就不自研」** 与 **§20 自研 vs 主流：方案修订表**。
  删掉全部自研基础设施（自写限流注解 / `@Aspect` 切面 / Lua 脚本 / `TraceIdFilter` / 幂等切面 / DFA 敏感词树），
  换成 **Resilience4j + Nginx**（限流熔断）、**Spring 事件 + `AFTER_COMMIT`**（操作审计）、**Micrometer Tracing**（链路追踪）、
  **Redisson**（分布式锁）、**`@Cacheable(sync=true)`**（防击穿）、**`sensitive-word`**（敏感词）。
  对应提交 4.1 / 4.2 / 5.1 / 5.4 的标题与验收标准已同步改写。

---

## 0. 现状盘点

### 0.1 已经实现的东西（逐文件、逐接口核对过）

**模块盘点**

| 模块 | 位置 | 实现内容 |
|---|---|---|
| 认证 `auth` | `auth/**` | 登录（`AuthenticationManager` + BCrypt）、注册、登出、当前用户；`JwtUtil` 签发/解析/校验；`JwtProperties`（`@ConfigurationProperties(prefix="jwt")`）；`UserDetailsServiceImpl`；`LoginUser implements UserDetails`（角色加 `ROLE_` 前缀、`isEnabled()` 绑 `status`）；`JwtAuthenticationFilter` |
| 用户 `user` | `user/**` | 分页（关键词/角色/状态筛选 + 排序白名单）、启用禁用、改角色、逻辑删除、重置密码；**5 条自我保护规则** |
| 文章 `article` | `article/**` | 前台 2 接口 + 后台 6 接口；`ArticleForm` 字段校验；摘要自动生成；列表排除 longtext；N+1 消除；排序白名单 |
| 分类 `category` | `category/**` | 只读列表，`sort` → `id` 双字段稳定排序 —— **有意只提供读接口** |
| 公共 `common` | `common/**` | `Result<T>`、`ResultCode`（10 个码）、`BusinessException`、`GlobalExceptionHandler`（6 个 handler） |
| 配置 `config` | `config/**` | `MybatisPlusConfig`（分页插件 + `@MapperScan`）；`SecurityConfig`（无状态 + JWT 过滤器 + 方法级权限 + 401/403 JSON + CORS） |
| 缓存 | `auth/cache/UserAuthCache.java` | Redis Hash 存认证信息（**不含密码**），TTL 30min，**写时清除 + 读失败降级回库** |

**接口清单（18 个，逐个数过）**

| 模块 | 数量 | 明细 |
|---|---|---|
| `/auth` | 4 | `POST /login`、`POST /register`、`POST /logout`、`GET /me` |
| `/user`（ADMIN） | 5 | `GET /page`、`PUT /{id}/status`、`PUT /{id}/role`、`DELETE /{id}`、`PUT /{id}/password` |
| `/admin/article`（ADMIN） | 6 | `GET /page`、`GET /{id}`、`POST`、`PUT /{id}`、`PUT /{id}/status`、`DELETE /{id}` |
| `/article`（公开） | 2 | `GET /page`、`GET /{id}` |
| `/category`（公开） | 1 | `GET /list` |

**这些细节是真的有质量，面试里应该主动讲（"我已经做了什么"，不是"还能加什么"）**

| 设计点 | 证据 | 为什么值得讲 |
|---|---|---|
| **逻辑删除后改名释放唯一索引** | `UserServiceImpl.java:267-275` | 逻辑删除的行还在表里，`username` 唯一索引会挡住同名新用户注册；删除时把用户名改成 `原用户名#deleted#{id}`，**既保历史又释放账号**。踩过坑才写得出这一行 |
| **"改用户就清缓存"贯穿 4 个写操作** | `UserServiceImpl.java:216,249,281,312` | 禁用/改角色/删除/重置密码全部 `evict`，逼 `JwtAuthenticationFilter` 回落数据库拿最新状态 |
| **旧 token 即时失效 —— 已实现且有用例** | `UserAdminTest.java:299,320,339` | 禁用 / 删除 / **降级**后旧 token 立刻失效。这不是"以后要做"，**是已经做了并且被测住了** |
| **禁止对自己做危险操作** | `UserController.java:55-86` | 不能禁用自己 / 改自己角色 / 删自己，防止管理员把自己锁在系统外 |
| **VO 永不外泄 password** | `UserServiceImpl.java:119-121,177-186` + 用例 `page_shouldNotExposePassword` | 实体→VO 手工映射，不图省事直接返回实体 |
| **分页参数兜底 + 上限保护** | `UserServiceImpl.java:90-95`（≤100）、`ArticleServiceImpl.java:68-69`（≤50） | 防止 `size=99999` 拖垮数据库，且有用例 `page_invalidSize_shouldBeClamped` |
| **排序字段白名单** | `UserServiceImpl.java:137-168`、`ArticleServiceImpl.java:344+` | 前端传 `id; DELETE FROM user` 也只走默认排序；非法字段不崩，有用例 |
| **列表不查 longtext** | `ArticleServiceImpl.java:91` + 用例 `page_shouldNotReturnContent` | 一次 10 篇正文全捞出来纯属浪费带宽 |
| **草稿返回 404 而不是 403** | `ArticleServiceImpl.java:107-112` + 用例 `detail_draft_shouldReturn404` | 返 403 等于告诉别人"这里确实有一篇草稿" |
| **清空封面真的写 NULL** | `ArticleServiceImpl.java:168-184` + 用例 `update_clearCover_shouldActuallySetNull` | 刻意不用 `updateById`（跳过 null 字段），改用手写 `SET` |
| **Controller 层取当前用户** | `UserController.java:100-108` | 故意不放 Service，避免 user 模块反向依赖 auth 模块 |
| **DTO 校验 + Service 二次兜底** | `ResetPasswordRequest.java:20` + `UserServiceImpl.java:293-296` | Service 可能被别处调用，不能只靠 Controller 的 `@Valid` |

**测试覆盖地图（76 个用例测的是真东西，不是"跑通就算"）**

| 测试类 | 用例数 | 覆盖重点 |
|---|---|---|
| `ArticleAdminTest` | 26 | 权限三态（ADMIN / GUEST / 无 token）、草稿默认值、空标题 400、非法分类 404、自动摘要、清空封面、逻辑删除、分页含草稿、分类筛选、置顶优先、非法排序字段不炸、`size` 被 clamp、列表不含正文 |
| `UserAdminTest` | 18 | 权限三态、关键词/角色筛选、密码不外泄、启禁用、**不能禁用/降级自己**、**禁用/删除/降级后旧 token 失效**、禁用用户无法登录 |
| `ArticlePublicTest` | 14 | 公开可读、写接口仍要认证、**草稿对前台 404**、**前台传 status 参数被忽略**、下架后消失、详情含分类名、浏览量自增、筛选与默认排序 |
| `JwtSecurityTest` | 4 | 无 token 401 / 有效 token 200 / 非法 token 401 / token 用户不存在 401 |
| `AuthLoginTest` | 4 | 登录返 token、密码错误、`/me` 带与不带 token |
| `GlobalExceptionHandlerTest` | 3 | 业务异常码透传、未知异常 500、`@Valid` 失败 |
| `UserRegisterTest` | 3 | 注册成功、重名、空用户名 |
| `YiguixingtuApplicationTests` | 4 | 上下文加载、JWT 生成解析、加载用户、BCrypt |

> **结论修正**：这个项目的底子比上一版写的厚——**权限三态、草稿隔离、逻辑删除的唯一索引冲突、旧 token 即时撤销**都是已落地且**有用例兜住**的。
> 上一版把它们压缩成一行"已有能力"，是我看浅了。下面的计划是在这个底子上加纵深。

### 0.2 扫出来的 13 个待办（带证据，并标了性质 —— 别把权衡当 bug 改）

**性质图例**：🔴 真缺口（会造成实际故障/损失） · 🟡 一致性缺口（不影响正确性，但不够专业） · 🟠 有意的权衡或上线前必须覆盖（别乱改） · 🔵 优化项（锦上添花）

| # | 待办 | 性质 | 证据（文件:行） | 说明 | 对应计划 |
|---|---|---|---|---|---|
| 1 | **`/auth/logout` 是空实现**，只打了一行日志 | 🔴 → ✅ **已解决** | `AuthController.java:84-87` | 登出后旧 token 依然可用到 24h 后自然过期。⚠️ **注意分寸**：禁用/删除/改角色的**即时撤销已经实现并有用例**（`UserAdminTest:299,320,339`），缺口只在"用户主动登出"这一条路径。**已由提交 5.2 修复**：签发时写入 `jti`，登出把它按剩余有效期写进 Redis 黑名单，过滤器每次解析后查一次；前端 w5.1 同步改为真的调用它。登出接口做成**幂等**（永远返回成功），否则前端会在用户点完退出时弹"登录已过期"的登录框 | ~~E2~~ **5.2** · w5.1 |
| 3 | **`getPublishedDetail` 读接口里带写操作**：每次看详情都 `UPDATE view_count = view_count + 1` | 🔴 | `ArticleServiceImpl.java:114` → `:230` | 纯写放大 + 热点文章行锁竞争。注释里已经解释过"为什么用 SQL 自增而不是读改写"，方向是对的，只是这个写不该在读路径上 | **A2** |
| 6 | **CORS 白名单写死 `http://localhost:3000`**，配置里没有任何地方能改 | 🔴 | `SecurityConfig.java:159,165` | 一上线跨域必挂 | **E1** |
| 9 | **8 个测试类全部 `@SpringBootTest`，强连本机 `MySQL:3310` / `Redis:6380`**，`src/test/resources` 不存在 | 🔴 → ✅ **已解决** | `src/test/`（无 resources 目录） | 没起容器就全红。**76 个用例这个最强资产目前只能在自己机器上演示**，CI 也跑不了。**已由提交 1.2 修复**：新增 `AbstractIntegrationTest` 基类用 Testcontainers 拉起 MySQL/Redis，8 个测试类继承它，`docker compose down` 之后 76 个用例仍全绿 | ~~B1~~ **1.2** |
| 13 | **空库里没有任何管理员账号，而且没有任何途径能造出一个** | 🔴 | `UserServiceImpl.java:53`（注册硬编码 `setRole("GUEST")`）vs `UserController.java:36`（改角色本身要求 ADMIN） | **鸡生蛋**：注册只产出 GUEST，而把某人提升为 ADMIN 又必须由 ADMIN 来操作。本地一直没暴露，是因为库里早就有 id=1 的管理员；换成全新数据库（比如部署到 ECS 那天、或者别人 clone 下来）会发现**后台根本进不去**。写演示数据文档时才发现 | **3.5** |
| 4 | `updateStatus` / `remove` 没有 `@Transactional`，是"先 select 再 update"两步 | 🟡 | `ArticleServiceImpl.java:188`、`:206` | 窗口极小、不涉及跨表不变量，**危害很低**；问题主要是和同样两步的 `create`/`update` 风格不一致 | **E8** |
| 8 | **没有任何日志配置**：无 `logback-spring.xml`，无文件、无滚动、无 traceId | 🟡 | `src/main/resources/` | 本地无所谓，线上排查时日志拼不起来 | **D2** |
| 10 | **README 与代码不符**：端口写 8081（实际 8082）、MySQL 写 3307（实际 3310）、"文章/分类在规划中"（已完成）、接口表只列 4 个（实际 18） | 🟡 → ✅ **已解决** | `README.md:90,119,120,153,177-182` | 面试官点开仓库第一眼看的就是它。**已由提交 0.1 修复**：端口、模块状态、18 个接口表、权限矩阵、建表方式、测试节全部重写对齐 | ~~G~~ **0.1** |
| 12 | `docker-compose.yaml` 只有 mysql/redis：无 backend、无 healthcheck、无 restart | 🟡 | `docker-compose.yaml` | 服务器重启后服务不会自己起来 | **B5** · **B8** |
| 5 | **HTTP 状态码与 body 里的 code 不一致**：除 `AccessDenied` 外，其余 handler 全返回 HTTP 200 | 🟠 | `GlobalExceptionHandler.java:25-60` vs `:79` | 这是**有意约定**（前端统一读 `body.code`），代码里 `:70-72` 也写明了为什么 `AccessDenied` 要特殊处理。改它是**破坏性改动**（前端全部错误分支要跟着改）——**要么现在成体系改，要么写进 README 当明示约定，别改一半** | **E8** |
| 7 | 全项目只有一个 `application.properties`，没有 profile 拆分 | 🟠 | `src/main/resources/` | 本地开发完全够用；但 Swagger、SQL 日志、数据源、CORS 都只能"改文件再打包"，上线前必须补 | **E1** |
| 11 | `log-impl=StdOutImpl` + MySQL `root/root` + Redis 无密码 + JWT 默认密钥 | 🟠 | `application.properties:6,8,12,17,20` | **本地这样写是对的**（jar 里带默认值方便 clone 就跑），README 也已经详细讲了怎么用环境变量覆盖（`:123-145`）。上线前逐条覆盖即可 | **E7** · **B5** |
| 2 | `/auth/me` 每次都查一次库，尽管过滤器刚从 Redis 拿到同一份用户（含 nickname） | 🔵 | `AuthController.java:100` vs `JwtAuthenticationFilter.java:113` | `/me` 是低频接口（前端一般启动时调一次），**不影响性能**；但这次查询确实是白花的，顺手去掉更干净 | **A1 附** |

> **别被这张表误导**：13 项里真正会造成故障的只有 **5 个**（#1 登出、#3 写放大、#6 跨域、#9 测试不可移植、#13 空库无管理员），
> 其中 **#9、#10 已随 M0/M1 修复**；#4/#5 是"看着不专业"和"有意的约定"，#2 是优化项。
> 这个项目当前的主要短板**不是"有 bug"，而是"代码里的好东西没有被交付出去"**（测试跑不起来、README 停在三个月前、没上线）。
> 所以计划的排序原则是：**先补交付（B1/B2/G），再做纵深（A/D/E），最后才是新业务（C）**。

### 0.3 完全还没有的东西

- `@Async` / `@Scheduled` / `@Cacheable` / 任何 `@Aspect` 切面 —— **全无**（各 0 处引用）
  ⚠️ 注意：**本项目也不打算加自研切面**（见 §20），这一行只是陈述现状
- `config/RedisConfig.java` —— **不存在**；`@EnableCaching` 未开启；Redis 目前只有 `UserAuthCache` 一处手写调用
- 消息队列 / 对象存储 SDK / Flyway / Testcontainers / JaCoCo / micrometer-registry-prometheus / Caffeine —— **全无**（`pom.xml` 里都没有）
- `Dockerfile` / `.dockerignore` / `.github/workflows` / `logback-spring.xml` / `src/test/resources` —— **全无**
- Tag（标签）、Comment（评论）、归档、搜索、上传、统计接口 —— **业务上全无**
- **无 XML Mapper**：`mybatis-plus.mapper-locations=classpath*:/mapper/**/*.xml` 指向的目录**不存在**，全部映射走 MyBatis-Plus 内置方法 + `LambdaQueryWrapper`
  → 这其实是好事（没退化成一堆 XML 里堆 SQL），但配置项留着容易让人误会，顺手清掉或补上目录

> ⚠️ **关于 A3 / A4**：本版**不再自研切面**（详见 §20）。所以**不需要** `spring-boot-starter-aop`，
> 而是要加三个主流依赖：`resilience4j-spring-boot3`（限流/熔断）、`redisson-spring-boot-starter`（分布式锁）、
> `micrometer-tracing-bridge-brave`（链路追踪）。这三样都是"配置文件 + 注解"就能用，不写一行基础设施代码。

---

## 1. 总览：批次与排期

| 批次 | 主题 | 内容 | 工期 | 简历关键词 |
|---|---|---|---|---|
| **A** | 技术纵深 | 缓存三件套、浏览量计数、**Nginx + Resilience4j 限流**、**Spring 事件驱动的操作审计** | 2–3 天 | Redis 缓存一致性、接口限流与熔断降级 |
| **B** | 工程化与交付 | Testcontainers、CI/CD、Flyway、OSS、**阿里云 ECS 上线** | 3–4 天 | 容器化集成测试、CI/CD、覆盖率、**真实部署** |
| **C** | 业务补全 | 标签、评论、敏感词、搜索、通知 | 各 2–4 天 | 消息队列、全文检索 |
| **D** | 性能与可观测 | 索引与慢查询、日志 traceId、压测调优、指标看板 | 2 天 | 索引优化 + EXPLAIN、链路追踪、压测数据 |
| **E** | 安全与配置 | 多环境、登出失效/双 Token、幂等、验证码、安全头、权限矩阵 | 2–3 天 | JWT 纵深、防重放、权限矩阵 |
| **F** | 前端站点（独立仓库） | SSG、SEO、sitemap、RSS、OSS 图片样式 | 1–2 天 | 首屏优化、SEO |
| **G** | 文档一致性 | README / BACKEND_PLAN / 本文件 / 简历 对齐 | 半天 | —— |

### 1.1 新增技术栈总表（本计划要落地的全部技术，一眼看全）

> 右边两列是这张表存在的意义：**每一项都要能落到一个提交、并且能在简历上说出口**。

| # | 技术 | 落在哪 | 解决什么问题（"不加会怎样"） | 对应提交 | 简历关键词 | 可写时机 |
|---|---|---|---|---|---|---|
| 1 | **Resilience4j（限流 + 熔断降级）** | `@RateLimiter` / `@CircuitBreaker` 注解 + `application-*.properties` 规则 | 手写限流注解 + Lua 脚本是**自研轮子**；Resilience4j 是 Spring 官方生态的现成方案，**纯库、零额外部署** | 4.1 | 接口限流、熔断降级 | M4 后 |
| 2 | **Nginx `limit_req`（入口层限流）** | `nginx.conf` | 应用层限流拦不住"请求还没进 JVM"的洪水；**入口先拦一道，成本为零**（Nginx 本来就有） | 4.1 | Nginx 限流 | M4 后 |
| 3 | **Spring 事件 + `@TransactionalEventListener(AFTER_COMMIT)` + `@Async`** | `event/OpLogEvent`、`listener/OpLogListener`；线程池走 `spring.task.execution.*` 配置 | 操作审计用 **Spring 原生事件机制**，**不自研 `@Aspect`**；`AFTER_COMMIT` 天然解决"业务回滚了日志却留下"的老问题 | 4.2 | 事件驱动、异步解耦 | M4 后 |
| 4 | **`@Scheduled`** | `task/ViewCountSyncTask` | 详情接口每次都 `UPDATE view_count`，读接口带写操作 | 2.3 | 定时任务 + 批量落库 | M2 后 |
| 5 | **Spring Cache + `RedisConfig`** | `config/RedisConfig`、`@Cacheable` / `@CacheEvict` | 现在**连 `RedisConfig` 都不存在**，`@EnableCaching` 没开，Redis 只有一处手写调用 | 2.1 · 2.2 | 缓存三件套、缓存一致性 | M2 后 |
| 6 | **Flyway** | `db/migration/V1__init.sql` | 表是手工建的，DDL 散在 md 里，换台机器就要照抄 SQL；**也是 Testcontainers 的前置**（容器起来是空库，没迁移就没表） | 1.1 | Schema 版本化 | M1 后 |
| 7 | **Testcontainers** | `AbstractIntegrationTest` | 76 个用例强连本机 3310/6380，没起容器全红，**CI 跑不了** | 1.2 | 容器化集成测试 | M1 后 |
| 8 | **GitHub Actions + JaCoCo** | `.github/workflows/ci.yml` | 有测试却没有 CI，覆盖率也没数字；之后接 CD 到 ECS（B6） | 1.3 · B6 | CI/CD、代码覆盖率 | M1 后 |
| 9 | **阿里云 OSS SDK** | `upload` 模块 | 封面图目前只能填外链 URL，没有上传能力 | 3.2 | 对象存储、RAM 最小权限、签名 URL | M3 后 |
| 10 | **Dockerfile 多阶段 + 三容器 compose** | `Dockerfile`、`docker-compose.yaml` | compose 现在只有 mysql/redis，没有 backend、没有 healthcheck/restart | 3.3 | 容器化部署 | M3 后 |
| 11 | **Micrometer Tracing（Brave）+ logback 结构化日志** | 依赖 `micrometer-tracing-bridge-brave` + `logback-spring.xml` | 全项目**没有任何日志配置**；**traceId 不自研 Filter**，由 Spring Boot 官方链路追踪自动注入，日志 pattern 直接取用 | 5.1 | 链路追踪、可观测性 | M5 后 |
| 12 | **micrometer + Prometheus** | actuator + 自定义业务指标 | actuator 已在 `pom.xml`，只差注册表；不知道缓存命中率/限流拒绝数就无法定位问题 | 5.7 | 可观测性、自定义指标 | M5 后 |
| 13 | **HikariCP 调优 + wrk 压测** | `application-*.properties` + README | 连接池默认值够不够、加缓存到底快了多少——**没有数字就只能靠感觉** | 5.8 | 性能压测、连接池调优 | M5 后 |
| 14 | **RabbitMQ** | 评论通知、搜索索引同步 | 评论/索引同步要可靠投递：手动 ACK + 死信队列 + 消费幂等 | 6.3 | 消息队列、可靠性投递 | M6 后（可选） |
| 15 | **Elasticsearch + IK** | 检索模块 | `LIKE '%kw%'` 前置通配符走不了索引（`ArticleServiceImpl.java:84`），这是 ES 的真实动机 | C 批 | 全文检索、倒排索引 | M6 后（可选） |
| 16 | **Caffeine** | 分类列表本地缓存 | 本地缓存 + Redis 组成多级缓存，进一步减少网络往返 | C 批 | 多级缓存 | M6 后（可选） |
| 17 | **Redisson（分布式锁 / 布隆过滤器）** | 缓存击穿重建、接口幂等 | 手写 `SETNX` 要自己处理**锁续期、误删、可重入**；Redisson 是现成主流方案 | 2.2 · 5.4 | 分布式锁 | M2 后 |
| 18 | **sensitive-word（敏感词过滤库）** | 评论内容过滤 | DFA 前缀树**不自研**，用成熟库（自带分词、跳过、白名单） | C 批 | 敏感词过滤 | M6 后（可选） |

**这张表怎么用**

- **做一项、勾一项**：每完成对应提交，就在本表打勾，并同步简历（见 §8 简历同步规则）。
- **1–13 是"确定要做"的**（M1–M5 已在 §16 拆成原子提交）；**14–16 是"有余力再说"**（C 批可选）。
- 简历上**只写 1–13 里真正做完的那些**。挑进简历的具体是哪几项，见下面「简历口径」。

### 1.2 简历口径（从上面 16 项里挑哪几个写）

**原则**：技术栈框里只放"能在面试里被追问三层还不露"的；写不下的一律进技能栏或降级成"了解"。

| 简历位置 | 写什么 | 依据 |
|---|---|---|
| **项目经历 · 技术栈框** | 加 `Testcontainers`、`Flyway`、`GitHub Actions`、`Resilience4j`、`Redisson`、`阿里云 ECS / OSS` | 都是"项目里真有一处配置/代码能指出来"的 |
| **技能栏 · 后端开发** | 加 `Spring AOP`、`Resilience4j` | `Spring AOP` 指 `@Transactional` / `@PreAuthorize` 这些**现成的**切面；`Resilience4j` 对应 4.1 的限流与熔断降级 |
| **技能栏 · 数据库与缓存** | `Redis` 从"缓存（Hash + TTL、主动失效）"升级为 **"缓存穿透 / 击穿 / 雪崩"**，并加 `Redisson 分布式锁` | 对应 2.1 / 2.2 / 5.4 |
| **技能栏 · 工程化与测试** | 加 `Flyway`、`Testcontainers`、`GitHub Actions + JaCoCo`、`Micrometer Tracing` | 对应 1.1 / 1.2 / 1.3 / 5.1 |
| **技能栏 · MySQL** | 加"索引优化" | 对应 5.3（D1） |
| **了解行** | `Redis 高并发` **移出**（已升级为掌握级）；保留 `Spring Cloud`，加 `线程池`、`RabbitMQ`，**加 `Sentinel`** | 知识声明；`Sentinel` 写上去的作用是**给"为什么不用它"留个话头**（见 §20）；每一项都要先答得上 §1.4 的三问 |

> ⚠️ **进场门槛**：以上每一条都必须在对应提交**真的完成**之后才留在简历上。
> `resume.html` 里那段不可见的 HTML 注释就是核对门，已按本表逐条列出"没做完该删哪一句"。

### 1.4 「了解」级不是免责声明 —— 每一项都要能答上三问

写进"了解"的技术**不需要项目里做过**（它是知识声明，不是项目陈述），但**面试官一定会顺着追问**。
所以每一项在写上去之前，先自测这三问；答不上就删掉，不要留在简历上。

| 简历"了解"行 | 必须能答上的三问 | 项目里的落点 |
|---|---|---|
| **线程池** | ① `corePoolSize` / `maxPoolSize` / 队列 / 拒绝策略 四个参数分别管什么<br>② 为什么不建议用 `Executors.newFixedThreadPool`（**无界队列 → OOM**）<br>③ 队列满了以后四种拒绝策略各是什么行为 | 4.2 的 `AsyncConfig`（**真会用到**，做完可升级进技能栏） |
| **RabbitMQ** | ① 四种交换机（direct / topic / fanout / headers）的区别<br>② 消息丢失的三个环节（生产者 → Broker → 消费者）分别怎么防<br>③ 手动 ACK、死信队列、消费幂等分别解决什么问题 | 6.3（可选，不做也行） |
| **Spring Cloud** | ① Nacos 做什么（注册中心 + 配置中心）<br>② Gateway 做什么（统一入口 / 鉴权 / 限流）<br>③ OpenFeign 做什么（声明式调用）<br>④ **能说清"为什么这个单机博客不拆微服务"**（这题比前三问更加分） | 不落地（在 §13「不做」清单里，**能答"为什么不做"反而是加分项**） |

| **Sentinel** | ① 它和 Resilience4j 的分工区别（Sentinel 强在**流控 + 熔断 + 系统自适应保护 + 控制台动态改规则**）<br>② **为什么这个项目没有用它**（要跑 Dashboard 才有动态规则，单机博客 +1 容器不划算） | 不落地（这是 §20 里"选了 Resilience4j"的对照组，**能答"为什么不选"就是加分**） |

> 这一级的作用是**主动给面试官一个追问方向**（显得你分得清"做过"和"知道"），**不是藏拙**。
> 要么答得上，要么别写 —— 写了答不上，比不写更伤。

### 1.3 推荐顺序 —— 先让"已经做好的东西"被看见，再加新东西

1. **M0 = G（半天）**：README / BACKEND_PLAN 与代码对齐。
   现状是"**代码写得比文档好**"——权限三态、草稿隔离、逻辑删除的唯一索引处理、旧 token 即时撤销，这些在 README 里一个字都没有。**这是全项目性价比最高的一步。**
2. **M1 = B3 + B1 + B2（1 天）**：Flyway → Testcontainers → CI，让 76 个用例在任何机器和 GitHub Actions 上都能跑。
   这一步不改业务代码，风险最低，却把"我最强的资产"从"只能在我电脑上演示"变成"点开仓库就能验证"。**建议排在缓存之前。**
   （Flyway 必须在 Testcontainers 前面：容器起来是空库，没有迁移就没有表。）
3. **A1 + A2（2 天）**：缓存纵深（三件套 + 浏览量计数），做完技能栏才升级得诚实。
4. **E1（半天）**：多环境配置，是 B4 / B5 的前置。
5. **B5（1 天）**：阿里云 ECS 上线，让徽章里那四个字「已上线」变成真的。
6. 其余按 §9 的里程碑继续。

---

## 2. 批次 A：技术纵深（★性价比最高）

> 做完这一批，「数据库与缓存」那行技能才**有资格**从"了解"升级成"掌握"。

### A1 · Redis 缓存文章列表与详情（含缓存三件套）

**涉及文件**
- 新增 `config/RedisConfig.java`：`RedisTemplate<String,Object>` + `GenericJackson2JsonRedisSerializer`（**必须注册 `JavaTimeModule`**，否则 `LocalDateTime` 序列化直接抛异常）
- 启动类加 `@EnableCaching`
- 改 `ArticleServiceImpl.pagePublished` / `getPublishedDetail`
- `application.properties` 加 `spring.data.redis.lettuce.pool.*`

**6 个关键设计决策（这就是面试要讲的全部内容）**

1. **只缓存前台，后台不缓存**
   后台 `pageAll` 一缓存，管理员刚改完的文章在列表里看不到，会以为保存失败。**缓存边界 = 权限边界。**

2. **详情缓存坚决不缓存 `view_count`**（最容易做错的一处）
   整个 `ArticleVO` 塞进缓存后，浏览量独立计数了，缓存命中时 `view_count` 永远不变。
   做法：缓存文章主体，浏览量走独立 Redis 计数，**返回时合并**。

3. **防穿透**：查不到的文章也写一个短 TTL 空值（60s），避免每次请求都打库。
   key 总量小，**不引入布隆过滤器**——讲清"为什么不用"也是加分项。

4. **防击穿**：热点文章的重建要串行化，但**不要手写 `SETNX`**（锁续期、误删、可重入都得自己处理 = 自研轮子）。用现成的：
   - **`@Cacheable(sync = true)`** —— Spring 自带的单机互斥，**一行配置解决**，单实例完全够用
   - 需要跨实例时用 **Redisson `RLock`**（看门狗自动续期，`tryLock` 带等待时间）
   另一条路是**逻辑过期**（不设 TTL，存 `expireAt`，异步重建）。**能对比这三者才是深度**：
   `sync=true` 最简单但只在本实例有效；Redisson 跨实例但多一次加锁往返；逻辑过期不阻塞但会返回旧数据。

5. **防雪崩**：TTL 加 0–300s 随机值，避免一批 key 同时过期。

6. **一致性：更新时"删缓存"而不是"更新缓存"**
   并发双写时"更新缓存"会写进脏值，"删缓存"最坏只多一次回源。
   顺序：**先更新数据库、再删缓存**。

**注解 vs 手写：混用，取舍本身就是答案**
- 列表 `pagePublished`：`@Cacheable`，key = `article:v1:page:{page}:{size}:{categoryId}:{keyword}`（**带版本前缀**，将来改结构可整体失效）
- 详情：`RedisTemplate` 手写**空值缓存**部分（注解表达不了"缓存 null"），重建互斥交给 `sync = true` / Redisson

**验收（可观测，不靠"感觉快了"）**
- `log-impl=StdOutImpl` 已开 → 第二次请求 `/article/page` 时**日志里不再出现 SQL**
- 发一篇新文章 → 列表立刻能看到（缓存被删）
- 手动 `DEL` 详情 key → 下一次请求能自愈重建
- 新增 `ArticleCacheTest`：⚠️ **`@Transactional` 只回滚 MySQL，不回滚 Redis** → 必须 `@AfterEach` 手动清 key（`UserAdminTest.java:361` 已经踩过这个坑，照着写）

**完成后简历可写**：文章列表/详情 Redis 缓存；空值缓存防穿透、互斥锁重建防击穿、TTL 随机化防雪崩；更新后删缓存保证一致性

**顺手修**：问题 #2（`/auth/me` 重复查库）——过滤器已经把 `User`（含 nickname）放进 `LoginUser` 了，`me()` 直接取即可，不必再 `selectById`。

---

### A2 · 浏览量 Redis 计数 + 定时批量落库

**痛点**：`ArticleServiceImpl.java:114` 每次看详情都触发 `:230` 的 `UPDATE`。

**做法**
1. 详情接口不再直接 UPDATE，改成 `INCR article:view:{id}`
2. 新增 `task/ViewCountSyncTask`：`@Scheduled(fixedDelay = 5min)` 把增量批量刷回 MySQL
3. 批量落库**用 MyBatis-Plus 现成的批处理**（`updateBatchById` / `saveOrUpdateBatch`）或一条 `UPDATE ... CASE WHEN`；
   **不要循环 N 次单条 UPDATE**，也不要自己写 JDBC batch（库已经做了）
4. 返回详情时 `view_count = DB 值 + Redis 增量`（配合 A1 的合并逻辑）

**要能回答的问题**
- 进程崩了丢多少？→ 最多一个批次窗口（5 分钟）的增量
- 为什么不用 MQ？→ 浏览量是可容忍丢失的弱数据，为它上 MQ 是过度设计（**"拒绝过度设计"本身就是加分项**）
- 并发为什么不能用 `GET` + `SET`？→ 读-改-写非原子会丢计数，和 `view_count = view_count + 1` 同一个道理（`:222-228` 的注释你已经写过这层分析）

**验收**：连续刷新详情 100 次 → 这 5 分钟内 SQL 日志里**没有任何 UPDATE**；5 分钟后 `view_count` 一次性 +100

**完成后简历可写**：浏览量 Redis 计数 + `@Scheduled` 定时批量落库，把写放大从读接口移出

---

### A3 · 接口限流：Nginx `limit_req` + Resilience4j（**不自研**）

> **本版修订**：原方案是"自研 `@RateLimit` 注解 + `@Aspect` 切面 + 手写 Redis Lua 令牌桶"，**整段换掉**（理由见 §20）。
> 限流不是业务逻辑，是基础设施；**自研基础设施 = 公司里没人这么干**。

**两层，各管各的**

| 层 | 方案 | 管什么 |
|---|---|---|
| **入口层** | **Nginx `limit_req_zone` + `limit_req`** | 挡住"还没进 JVM"的洪水（爬虫、CC）。**零代码、零成本**（Nginx 本来就在那） |
| **应用层** | **Resilience4j `@RateLimiter`** | 按业务维度细粒度限流（登录按 IP、发文按用户）；顺带用 `@CircuitBreaker` 做**熔断降级**（调 OSS 超时时快速失败，不拖垮线程） |

**为什么选 Resilience4j 而不是 Sentinel**（面试很可能问，这是加分回答）
- **Resilience4j 是纯库**：加依赖 + 注解 + `application.properties` 写规则，**零额外进程**
- **Sentinel 要额外跑 Dashboard** 才有动态规则能力 —— 对单机博客是 **+1 容器 + 更多内存**，收益不匹配
- 但必须知道 **Sentinel 强在哪**（流量控制 + 熔断降级 + 系统自适应保护 + 控制台动态改规则），
  所以它写进简历"了解"行，被问到就答"知道，但这个规模的单机项目用 Resilience4j 更划算"

**涉及文件**
- `pom.xml` 加 `resilience4j-spring-boot3`（**它自带的 aspect 就是现成的，不写 `@Aspect`**）
- `application-*.properties` 配 `resilience4j.ratelimiter.instances.login.*` 等实例规则
- 接口上标 `@RateLimiter(name = "login")`
- `ResultCode` 加 `TOO_MANY_REQUESTS(429, "请求过于频繁")`（现在枚举里没有 429，`ResultCode.java:6-28`）
- `GlobalExceptionHandler` 捕获 `RequestNotPermitted` → ⚠️ **必须返回 `ResponseEntity`** 才能让 HTTP 状态码也是 429
  （跟 `:79` 的 `handleAccessDenied` 同一套路，否则 HTTP 一直 200，前端和测试都判断不出来）
- 线上 Nginx 加 `limit_req`；⚠️ 应用层要按 IP 限流的话必须读 `X-Forwarded-For`（前面有 Nginx，否则全站共用一个 IP）

**验收**：循环打 6 次登录 → 第 6 次返回 **HTTP 429 + `code:429`**；窗口过后恢复；
Nginx 层用 `wrk`/`ab` 打到触发 `limit_req`（返回 503）——**两层都要能看到效果**

**完成后简历可写**：Nginx + Resilience4j 两层限流，并用熔断降级保护外部依赖调用

---

### A4 · 操作审计：Spring 事件 + `@TransactionalEventListener(AFTER_COMMIT)` + `@Async`（**不自研切面**）

> **本版修订**：原方案是"自研 `@OpLog` 注解 + `@Aspect` 切面 + 自建 `ThreadPoolTaskExecutor`"，**整段换掉**（理由见 §20）。

**为什么用事件而不是切面**
- `ApplicationEventPublisher` 是 **Spring 框架自带的**，不是自研轮子
- 关键收益：`@TransactionalEventListener(phase = AFTER_COMMIT)` —— **只有业务事务提交成功后才记录**，
  天然解决自研切面那个老问题："业务回滚了，日志却留下来了"
- 异步用 `@Async`，线程池参数走 `application.properties` 的 `spring.task.execution.*`，
  **不需要自己 `new ThreadPoolTaskExecutor`**

**做法**
1. 后台写操作（增删改文章/用户）在 Service 里 `publisher.publishEvent(new OpLogEvent(...))`
2. 写一个 `@TransactionalEventListener(AFTER_COMMIT)` + `@Async` 的监听器负责落库
3. 新增 `entity/OperationLog.java` + mapper（表 DDL 见 `BACKEND_PLAN.md:121`）

**唯一需要"自己配"的地方（而且只是改配置，不是写代码）**
- ⚠️ `spring.task.execution.pool.queue-capacity` **必须配有界值** —— Spring Boot 默认队列容量是 `Integer.MAX_VALUE`（等于无界），
  量一大直接 OOM。核心/最大线程数、队列容量、`thread-name-prefix` 全在 `application-*.properties` 里写

**要能回答的问题**
- 异步记录，业务回滚了怎么办？→ `AFTER_COMMIT` 阶段压根不触发，**天然一致**，不需要额外设计
- 为什么不用 MQ？→ 同一个 JVM 内 Spring 事件就够；**跨服务才需要 MQ**（拒绝过度设计）
- 队列满了会怎样？→ 按 Spring 的拒绝策略处理；审计日志可以丢，业务不能

**验收**：后台删一篇文章 → `operation_log` 有记录且**响应耗时不明显增加**；
故意让业务抛异常回滚 → **没有**日志记录（这一条证明 `AFTER_COMMIT` 真的生效）

**完成后简历可写**：Spring 事件驱动 + `@TransactionalEventListener(AFTER_COMMIT)` 异步记录操作审计

---

## 3. 批次 B：工程化与交付

### B1 · Testcontainers 重构测试 ⭐ 最推荐

**现状**：76 个用例全部 `@SpringBootTest`，连本机 `MySQL:3310` + `Redis:6380`，`src/test/resources` **不存在**（问题 #9）。

**做法**
- 新增 `src/test/resources/application-test.properties`
- `@Testcontainers` + `@Container`（`MySQLContainer` / `GenericContainer` 跑 redis）+ `@DynamicPropertySource` 注入连接信息
- 容器用 `static`，**每个测试类共用一个**——起容器是秒级的，每类起一次会让 76 个用例跑得像蜗牛

**收益**：CI 能真跑；简历可写"基于 Testcontainers 的容器化集成测试"

**注意**：`@Transactional` 回滚数据库但**不回滚 Redis**（和 A1 同一个坑），缓存相关测试要手动清

### B2 · GitHub Actions + JaCoCo

- `.github/workflows/ci.yml`：JDK 17 → `mvn -B verify` → 上传 surefire 报告 + JaCoCo 覆盖率 → README 放徽章
- 有 76 个用例打底，**覆盖率数字大概率不难看**，这是难得的"能拿数字说话"的机会
- 成本：半天。关键词：CI/CD、代码覆盖率

### B3 · Flyway 数据库版本化迁移

- 现状：README 只给 `user` 建表 SQL，`article`/`category` 的 DDL 躺在 `BACKEND_PLAN.md` 里 —— 说明**表是手工建的，没有任何迁移脚本**；而且 `docker-compose.yaml:9` 映射 **3310**，README 写 **3307**（已经对不上）
- 做法：`src/main/resources/db/migration/V1__init.sql`（把现有 user / article / category 版本化），之后新表走 `V2__`、`V3__`
- 已有数据库要设 `spring.flyway.baseline-on-migrate=true`，否则启动直接报错
- 成本：半天。关键词：Schema 版本化

### B4 · 封面图上传：阿里云 OSS + 文件校验

> 已确定用**阿里云 OSS**（不自建 MinIO）。

- 新增 `upload` 模块：`POST /upload`（ADMIN），依赖 `aliyun-sdk-oss`
- 要点：类型白名单（jpg/png/webp）、大小限制（`spring.servlet.multipart.max-file-size`）、**UUID 重命名**防覆盖、按日期分目录（`article/2026/09/xxx.jpg`）
- **不要用主账号 AccessKey**：用 **RAM 子账号 + 最小权限**（只允许对指定 bucket 前缀 `PutObject`）；进阶用 **STS 临时凭证**让前端直传、后端只签 token —— 能讲清"为什么临时凭证比长期密钥安全"就是加分
- **bucket 权限**：私有读 + 返回带过期时间的**签名 URL**，或公开读但只允许写——两条路的取舍要能说
- **OSS 图片处理**：用样式做缩略图 / 转 WebP（`?x-oss-process=image/resize,w_800`），列表页不必回原图
- **dev / prod 分离**：本地走本地磁盘或独立测试 bucket，用 Spring Profile 切换（依赖 **E1**）
- 简历可写：阿里云 OSS 对象存储（RAM 最小权限 / 签名 URL / 图片处理样式）

### B5 · 阿里云 ECS 部署上线

**服务器侧**
- [ ] 安全组**只开 22 / 80 / 443**；⚠️ MySQL(3310)、Redis(6380) **绝对不对公网开放**——现在 `docker-compose.yaml` 是把这两个端口映射到宿主机的，安全组一误开就是数据库裸奔（更稳的做法：生产 compose 里删掉这两个 `ports`，只留容器内网）
- [ ] 装 Docker + Docker Compose（官方源）
- [ ] `Dockerfile` **多阶段构建**（`maven:3.9-eclipse-temurin-17` 构建 → `eclipse-temurin:17-jre` 运行），不要用 `mvn spring-boot:run` 上线；`mvn -DskipTests package` 产出的 jar 只有运行期依赖
- [ ] `docker-compose.yml` 扩成三容器：backend + mysql + redis，数据卷持久化，加 `healthcheck` 与 `depends_on: condition: service_healthy`（**B8**）
- [ ] 环境变量注入 `JWT_SECRET`（`application.properties:20` 有默认值，必须覆盖）
- [ ] ⚠️ `mybatis-plus.configuration.log-impl=StdOutImpl`（`:17`）**生产必须关掉**（走 **E1** 的 prod profile）

**入口侧**
- [ ] Nginx 反代：`/api/` → `backend:8082`，`/` → 前端
- [ ] 前端形态决定怎么部：**SSG 静态产物**可直接放 OSS + CDN（省带宽、省钱）；**SSR** 就得在 ECS 上跑 Node 容器
- [ ] HTTPS 证书（阿里云免费证书或 Let's Encrypt + 自动续期）、Gzip、静态资源缓存头

**域名与合规**
- [x] ICP 备案 —— **已确认备好**（投递前把备案号记一下，偶有面试官问）
- [ ] 域名 A 记录指向 ECS 公网 IP

**待补信息（占位，你后续提供）**

| 项 | 值 |
|---|---|
| ECS 地域 / 实例规格 | `<待填>` |
| ECS 公网 IP | `<待填>` |
| OSS bucket / endpoint / 地域 | `<待填>` |
| 域名 | `www.yigalaxy.xin`（已申请） |
| 备案号 | `<待填>` |
| 前端形态（SSG 静态 / SSR Node） | `<待填>` —— 决定前端放 OSS + CDN 还是 ECS 跑容器 |
| 阿里云账号授权方式 | `<待填>` —— RAM 子账号 / STS 临时凭证 |

**运维**
- [ ] MySQL 定时备份（`mysqldump` + crontab），备份文件丢 OSS（**B7**）
- [ ] 容器加 `restart: unless-stopped`，服务器重启后自动恢复
- [ ] 出问题先看 `docker compose logs -f backend`

**验收**：手机 4G 打开域名能发文、能看到 OSS 上的图；`docker compose ps` 三容器健康；**重启 Docker 后服务自动恢复**

### B6 · 持续部署（CD）

- `.github/workflows/cd.yml`：`main` 分支打好 tag → build 镜像推到 **阿里云 ACR** → SSH 到 ECS `docker compose pull && up -d`
- 讲得清的细节：镜像 tag 用 git sha（可回滚）、部署前 `healthcheck` 通过才切流量、**失败自动回滚上一版**
- 成本：半天。关键词：CI/CD 全链路（B2 只做了一半）

### B7 · 备份与恢复演练

- `mysqldump --single-transaction`（不锁表）+ gzip + 上传 OSS + crontab 每日 3 点，保留 7 天
- **关键**：光备份不算会，"**恢复演练**"才算——真删一张表再恢复一次，把过程写进 README
- 成本：半天。关键词：数据安全、可恢复性（面试问"你数据怎么保证不丢"时的答案）

### B8 · 容器与停机加固

- `Dockerfile`：**非 root 用户运行**（`USER app`）、`.dockerignore`（别把 `target/` 和 14MB 图片打进构建上下文）、分层 jar（`layertools`）加速增量构建
- `application.properties`（prod profile）：`server.shutdown=graceful` + `spring.lifecycle.timeout-per-shutdown-phase=30s`
  → **为什么需要**：滚动更新/重启时正在处理的请求被硬砍，用户看到 502。优雅停机让在途请求跑完再退出
- 成本：半天

---

## 4. 批次 C：业务补全（有余力再挑，不必全做）

**前置**：C2 / C3 / C4 / C6 都需要先有**评论、标签**业务才挂得上，所以 **C1 是它们的前置**。

| 编号 | 技术 | 落点 | 拿到什么 |
|---|---|---|---|
| **C1** | 标签 + 评论模块 | Tag CRUD、文章打标签、游客评论 + 管理员审核 | 补全博客业务（C2/C3/C6 的前置） |
| **C2** | RabbitMQ | 评论后异步通知、文章发布后同步搜索索引 | 消息队列、解耦、**手动 ACK + 死信队列 + 消费幂等**（能聊 20 分钟） |
| **C3** | Elasticsearch + IK 分词 | 替掉 `LIKE '%kw%'`（`ArticleServiceImpl.java:84`，前置通配符走不了索引，这就是 ES 的真实动机） | 全文检索、倒排索引；**必须做数据同步，否则一问就露** |
| **C4** | 多级缓存 Caffeine + Redis | 分类列表这种极少变更的数据 | 多级缓存、本地缓存一致性问题 |
| **C5** | 敏感词过滤 | 评论发布前用 **`sensitive-word`** 库过滤（**不自研 DFA**） | 会用成熟库 + 知道 DFA 原理就够；被问"底层怎么实现"答库的原理 |
| **C6** | 邮件通知 | 评论回复提醒、找回密码 | 和 C2 搭配的真实业务 |
| **C7** | SSE 实时推送 | 首页"正在浏览"、评论实时提醒 | 比 WebSocket 简单，业务支撑真实 |

---

## 5. 批次 D：性能与可观测（本版新增）

### D1 · MySQL 索引与慢查询治理

**为什么现在就该做**：`article` 表已经有 DDL（`BACKEND_PLAN.md:78-96`，含 `idx_status_time`、`idx_category`），但**没有证据表明真的验证过它生效**；而 `LIKE '%kw%'`（`:84`）是全表扫描。

**做法**
1. 开慢查询日志（`slow_query_log` + `long_query_time=1`），把真实慢 SQL 捞出来
2. 对「前台列表」这条主查询 `WHERE status=1 [AND category_id=?] ORDER BY is_top DESC, create_time DESC` 做 **`EXPLAIN`**，看 `type` / `key` / `rows` / `Extra`
3. 目标：`type` 从 `ALL` 降到 `ref`/`range`，`Extra` 里不出现 `Using filesort`
4. 把 EXPLAIN 前后对比**截图/贴进 README**——这是能被追问的硬证据

**要能回答**：为什么建 `(status, create_time)` 而不是两个单列索引？→ 最左前缀 + 排序也能吃到索引，避免 filesort。（注意：`is_top` 插在排序中间会破坏索引有序性，这是真实要处理的取舍）

**验收**：`EXPLAIN` 输出对比 + 慢查询条数下降

### D2 · 日志体系与 traceId

**现状**：全项目**没有任何日志配置**（问题 #8）。`@Slf4j` 用得不少，但输出全靠 Spring Boot 默认格式，无文件、无滚动、无上下文。

**做法**
1. 新增 `logback-spring.xml`：控制台（开发彩色）+ **文件按天滚动**（`SizeAndTimeBasedRollingPolicy`，保留 15 天）+ 分级（`com.yigalaxy` 用 DEBUG，根用 INFO）
2. **不自研 Filter**：加 `micrometer-tracing-bridge-brave`，Spring Boot 会自动为每个请求生成/透传 traceId 与 spanId 并写入 MDC；logback pattern 里直接写 `[%X{traceId}]` 即可（**框架给的，不用手写 `OncePerRequestFilter`**）
3. `pattern` 里加 `[%X{traceId}]` → 一次请求跨 Controller/Service/Mapper 的日志就能串起来
4. 生产 profile 用 **JSON 格式**（便于日志采集），并**关掉 `StdOutImpl`**

**要能回答**：MDC 底层是 `ThreadLocal`，所以**异步线程（`@Async`、A2 的定时任务）里拿不到 traceId**——必须在提交任务时手动传递。这个坑讲出来非常加分，而且你的项目里**同时有 A2 的定时任务和 A4 的异步日志**，正好是真场景。

**验收**：一次请求的所有日志行都带同一个 traceId；响应头能拿到 `X-Trace-Id`；`logs/` 下有按天文件

### D3 · 压测与连接池调优

**做法**
1. 用 `wrk` 或 JMeter 对 `/article/page` 压测：先测**加缓存前**，再测**加缓存后**（依赖 A1）
2. 记录 QPS / 平均延迟 / P99 —— 这是**你唯一能拿出的真实性能数字**（简历按你的要求不写数字，但面试可以讲）
3. HikariCP 显式配置：`maximum-pool-size`、`minimum-idle`、`connection-timeout`、`max-lifetime`
4. **调优要有依据**：连接数不是越大越好（DB 侧线程/内存受限），顺手回答"默认 10 个连接够不够"

**验收**：README 里有一张"加缓存前 vs 后"的对比表

### D4 · 指标与健康检查

- `micrometer-registry-prometheus` + `/actuator/prometheus`（actuator 已经在 `pom.xml:126-129`，只差注册表）
- **自定义业务指标**：缓存命中率（`Counter` 命中/未命中）、限流拒绝数（A3）、浏览量同步批次数（A2）
  → 比"接了 Prometheus"值钱得多，因为它证明你**知道该观测什么**
- `/actuator/health` 用于 B5 的容器 healthcheck —— ⚠️ 注意 `SecurityConfig.java:97` 的 `.anyRequest().authenticated()` 会把 `/actuator/**` 一起拦掉，要么放行 `health`，要么给探针配 token
- 可选：Grafana 面板截图进 README

---

## 6. 批次 E：安全与配置（本版新增）

### E1 · 多环境配置分离（其它项的前置）

**现状**：只有一个 `application.properties`（问题 #7），CORS 白名单写死在 Java 里（问题 #6）。

**做法**
1. `application.properties`（公共）+ `application-dev.properties` + `application-prod.properties`，`spring.profiles.active` 决定激活哪个
2. 按 profile 分开的东西：数据源、Redis、**Swagger 开关**（`springdoc.api-docs.enabled=false`）、**`log-impl`**、日志级别与格式、文件上传大小
3. **把 CORS 白名单挪进配置**（`app.cors.allowed-origins`），`SecurityConfig` 用 `@Value`/`@ConfigurationProperties` 读——dev 是 `localhost:3000`，prod 是 `https://www.yigalaxy.xin`
4. `.gitignore` 已经忽略 `application-local.yml` / `application-secret.properties`（`:34-35`），继续沿用这个约定

**要能回答**：为什么配置文件也要按环境拆？→ 打包一次、处处运行（12-Factor 的 Config 原则），而不是"改一行再重新打包"

### E2 · 登出真正失效 + 双 Token

**现状（问题 #1）**：`AuthController.java:84-87` 的 `logout()` 只打了一行日志。
**要说清楚**：项目里确实有一条"即时撤销"通道——禁用/删除/改角色时 `UserAuthCache.evict`（`UserServiceImpl`）→ 过滤器回落数据库 → 发现 `status != 1` 就不认证（`JwtAuthenticationFilter.java:131`）。**但它救不了登出**：登出后用户状态没变，旧 token 照样验签通过。

**做法（二选一，能对比更好）**
- **方案一 · 黑名单**：签发时给 token 加 `jti`；登出把 `jti` 写进 `jwt:blacklist:{jti}`，TTL = token 剩余有效期；过滤器多查一次 Redis
- **方案二 · 版本号**：`auth:ver:{userId}` 存一个整数，签发时写进 claim；改密码/登出时 `INCR`，版本对不上即失效。**好处：一次 Redis 读搞定，不用为每个 token 建 key**

**进阶**：**access token（15min）+ refresh token（7d）**，refresh 存 Redis 可撤销 → 这是"JWT 怎么兼顾安全和体验"的标准答案

**验收**：登出后拿旧 token 调 `/auth/me` → 401

### E3 · 接口幂等（防重复提交）

- **主力：数据库唯一索引**（最稳、最主流）—— 重复提交直接被 DB 唯一约束拦下，不依赖任何中间件
- **辅助：Redisson `RLock`** 按业务 key（`idem:{userId}:{接口}:{参数摘要}`，TTL 几秒）加锁，**不自研 `@Idempotent` 切面**
- 前端配合：提交按钮 loading + 禁用（对应 §18 的 web-5.4）
- **真实场景**：双击"发布文章"、网络重试导致的重复提交
- 和 A3 的区别要能说清：**限流防"太快"，幂等防"重复"**——两件事

### E4 · 登录验证码 + 失败锁定

- Redis 存图形验证码（TTL 5min，一次性消费）+ 登录失败计数（如 5 次锁定 15 分钟）
- 和 A3 的**三层防护关系**要能讲：验证码（挡脚本）→ 失败锁定（挡单账号爆破）→ IP 限流（挡单来源高频）
- 验证码图片生成建议直接用 `java.awt` 或轻量库，不引重框架

### E5 · 安全响应头与内容清洗

- 加 `X-Content-Type-Options: nosniff`、`X-Frame-Options: DENY`、`Referrer-Policy`、**HSTS**（HTTPS 上线后）；Spring Security 有现成的 `headers(...)` 配置项
- **XSS 的真实位置**：文章正文是 Markdown，前端渲染；评论（C1）是用户输入 → 服务端入库前做 HTML 转义/白名单过滤
- **`allowCredentials(true)` + 宽松 `allowedHeaders("*")`**（`SecurityConfig.java:163,165`）上线前收紧

### E6 · 权限矩阵与越权补漏（**大部分已经做了**）

**先纠正上一版的判断**：这一条不是"从零开始查漏洞"。项目里已经有的东西：

- `/user/**` 与 `/admin/article/**` 都是**类级 `@PreAuthorize("hasRole('ADMIN')")`**（`UserController.java:36`、`AdminArticleController.java:27`）
- **不能对自己做危险操作**：禁用自己 / 改自己角色 / 删自己都被明确拒绝并抛业务异常（`UserController.java:55-86`），且**有用例**（`disableSelf_shouldBeRejected`、`updateOwnRole_shouldBeRejected`）
- 权限三态（ADMIN 正常 / GUEST 403 / 无 token 401）在**每个管理类测试里都测了一遍**

**真正还缺的只有两件事**：

1. **一张"接口 × 角色 × 应否可访问"的矩阵表写进 README** —— 现在这些规则散在代码和测试里，面试官要自己读才能看明白。把 18 个接口一次列清，成本 1 小时，收益很高。
2. **确认"文章归属"这个维度要不要引入**：目前所有管理接口都是 ADMIN 独占，**没有"作者只能改自己的文章"的概念**。单人博客里这是合理简化（甚至更简单），但要**准备好被问**："如果多作者怎么办？"标准答法是加 `author_id` 归属校验或引入 `ROLE_AUTHOR` + 数据权限。
   ⚠️ **别为了"显得完整"硬加多作者体系** —— 单人博客没有这个业务需求，加了反而显得是教程式堆料。

### E7 · 密钥治理与依赖扫描

- `jwt.secret` 的默认值（`application.properties:20`）**只留开发用**，prod 必须环境变量覆盖；README 已有说明（`README.md:123-145`），把它落到 E1 的 prod profile 里
- **MySQL `root/root` + Redis 无密码**（`:8`、`:12`）：生产新建专用低权限库用户，Redis 设 `requirepass` 且不对公网暴露
- GitHub **Dependabot** 开起来（`pom.xml` 依赖版本较新，但会有 CVE）+ 可选 `dependency-check`

### E8 · 事务与错误码的一致性

**问题 #4、#5**，两个具体修法：

1. `updateStatus`（`ArticleServiceImpl.java:188`）、`remove`（`:206`）补 `@Transactional(rollbackFor = Exception.class)`——它们是"先查后写"的两步操作，和 `create`/`update` 保持一致
2. **统一 HTTP 状态码**：现在只有 `handleAccessDenied` 返回真实状态码（`GlobalExceptionHandler.java:79`），其余 5 个 handler 一律 HTTP 200 + body 里带 code（问题 #5）
   - 建议改成全部返回 `ResponseEntity`，让 **HTTP 状态码 == body 里的 code**
   - **要能解释为什么**：网关、监控、Nginx 日志、前端 axios 拦截器看的都是 HTTP 状态码；一直返 200 等于放弃了这一层
   - 注意：这是一次**破坏性改动**，前端（Nuxt）所有请求的错误判断要同步改——所以要么现在改，要么明确记录"这是有意的约定"

**顺手修**：`ResultCode` 里 `BAD_CREDENTIALS(400)` 和 `PARAM_ERROR(400)` 撞码、`UNAUTHORIZED(401)` 与登录失败语义混用（`ResultCode.java:10-18`）——补 429、并把"认证失败"和"未登录"分成两个码。

---

## 7. 批次 F：前端站点（独立仓库，可选）

> ⚠️ 本节是**初版概述**。前端的完整现状盘点、分阶段提交清单与前后端对齐关系见 **§17 / §18 / §19**，
> 那三节是逐文件读过 `D:\Project\yiguixingtu-web` 之后写的，颗粒度更细、以此为准。

| 项 | 做法 | 拿到什么 |
|---|---|---|
| Nuxt 4 **SSG** 预渲染 | 文章详情走 `prerender` / `ssr: true`，静态产物推 OSS + CDN | 首屏、SEO、省钱（也简化 B5 部署） |
| **SEO** | 每页 `title`/`description`、`og:` 标签、语义化 HTML | 博客能被搜到 |
| `sitemap.xml` + `robots.txt` | 由后端接口生成或在构建时产出 | 爬虫友好 |
| **RSS/Atom** | 后端加 `/feed.xml`（或前端构建时生成） | 技术博客的标配 |
| OSS 图片 | 列表用 `?x-oss-process` 缩略图，不拉原图 | 省流量（配合 B4） |

> 注意：这个仓库是**后端**（`pom.xml` 只有 backend），前端在另一个仓库。上面这些要么在前端仓库做，要么以后端接口形式提供。

---

## 8. 批次 G：文档一致性（半天，收益/成本比最高）

| 文件 | 现状 | 要做 |
|---|---|---|
| `README.md` | 端口 8081（实际 8082）、MySQL 3307（实际 3310）、"文章/分类在规划中"（已完成）、接口表 4 个（实际 18）、目录结构缺 article/category（问题 #10） | 全面重写：技术栈、**18 个接口表**、目录结构、部署文档（ECS 步骤 / 必需环境变量 / OSS 配置项）、EXPLAIN 与压测对比、接口权限矩阵 |
| `BACKEND_PLAN.md` | 写"4 个集成测试类"（实际 **8 个 / 76 用例**）、写"文章/分类/标签/评论完全没有"（实际文章+分类已完成） | 更新或标注"已完成"；标签/评论部分保留为规划 |
| `TECH_ROADMAP.md` | 本文件 | 每完成一项就勾掉，别让它也变成过期文档 |
| `resume.html` | 部署声明已按最终状态预写 | 见下面「简历同步规则」+ §9 |

### 简历同步规则（`resume.html`）

1. **部署声明是"预写"的，带一道核对门**：项目经历最后一条 bullet 结尾已写「经 Nginx 反向代理部署到**阿里云 ECS**，图片资源存**阿里云 OSS**」，标题右侧徽章写「已上线」。
   `<ul>` 后面留了一段**不可见的 HTML 注释**（不打印到 PDF）做投递前核对：若 B4 的 OSS 上传没做，就删掉 OSS 那半句；若还没上线，就删掉 ECS 部分与徽章里的「已上线」。**核对完删掉注释。**
2. **技能栏那条"了解"行不单独占行**：四行技能表之后跟一行普通小字「了解 **Redis 高并发**、**Spring Cloud**」——这是**知识声明**，不是项目陈述，被深挖时答概念即可，不算虚报。
   它**刻意不列概念清单**（穿透/击穿/雪崩、Nacos/Gateway 那些）：写上去既臃肿，又等于给面试官递上一串必答题。那些词是你的**复习提纲**，不写在简历上。
   **M1 完成后升级**：缓存三件套变成真做过 → 从这一行**移出**、升级进「数据库与缓存」的掌握级；这一行只留 `Spring Cloud`。
3. **AOP 的措辞分寸**：项目经历里「切面与事务」写的是「基于 Spring AOP 的声明式事务（`@Transactional`）」+「方法级 `@PreAuthorize` 在切面中统一校验」。
   ⚠️ **这两个都是 Spring 提供的现成切面，不是你写的 `@Aspect`**，措辞要一直保持这个分寸。
   **M4 完成后**：项目里多了「Nginx + Resilience4j 限流」与「Spring 事件驱动的操作审计」，可补进技能栏；
   ⚠️ 但**不要说"自研切面"** —— 本项目从头到尾没有自研切面（见 §20）。
4. **简历里不写任何数字**（你的决定）：18 个接口、8 个测试类、76 个用例都已从简历删掉。
   但**代码里的 76 个用例还在，它依然是你的底气** —— 面试时口头讲比印在纸上更有冲击力。**简历不写 ≠ 不能说。**
5. **简历与照片不进仓库**：`resume.html` / `photo_small.jpg` 已在 `.gitignore`（含手机号、邮箱、证件照）。仓库可能为了让面试官看代码而转公开，个人信息一旦进 git 历史就很难彻底删除。

---

## 9. 执行顺序与里程碑

| 里程碑 | 状态 | 内容 | 完成后能改简历的哪一句 |
|---|---|---|---|
| **M0** | ✅ 完成 | **G**：README / `BACKEND_PLAN.md` / 本文件与代码对齐 | 不改简历，但**面试官点开仓库看到的第一眼** |
| **M1** | ✅ 完成（1.1 Flyway · 1.2 Testcontainers · 1.3 CI+JaCoCo） | **B3 + B1 + B2**：Flyway → Testcontainers → CI/JaCoCo | 技能栏「工程化」加 **Testcontainers / CI-CD / JaCoCo / Flyway**；项目经历「测试与部署」那条可以写容器化集成测试 |
| **M2** | ⬜ 待做 | **A1 + A2**：缓存三件套 + 浏览量计数 | 「数据库与缓存」升级为掌握级；那条 `了解 Redis 高并发` **从"了解"行移出** |
| **M3** | ✅ 完成（3.1 多环境 · 3.2 OSS上传 · 3.3 Dockerfile+四容器 · 3.4 部署文档 · 3.5 管理员引导） | **E1 + B4 + B5 + B6**：多环境 → OSS 上传 → 上线 → CD | 徽章「已上线」与结尾「阿里云 ECS + OSS」**变成真的**；过一遍 §8「简历同步规则」第 1 条的不可见注释核对门 |
| **M4** | ⬜ 待做 | **A3 + A4**：Nginx + Resilience4j 限流、Spring 事件驱动的操作审计 | 技能栏加 `Resilience4j`（限流与熔断降级）、`Redisson`；「切面与事务」那条**保持原样** —— `@Transactional` / `@PreAuthorize` 就是最主流的用法，不需要改 |
| **M5** | 🔄 进行中（5.2 ✅ 完成 / 其余待做） | **D + E 其余**：索引 EXPLAIN、traceId、压测对比、登出失效、幂等 | 面试纵深：**这些是"做过才答得出"的细节** |
| **M6** | ⬜ 待做 | **C / F**：标签评论、搜索、消息队列、前端站点 | 按需，别为了关键词硬做 |

**顺序理由**：B1/B2 只是给测试和 CI 加壳、**不改业务代码**，风险最低，却立刻把 76 个用例从"只能自己跑"变成"任何人都能验证"，所以排在最前面；A 批改的是现有业务代码，收益最快但风险略高，紧随其后；E1/B5 负责把东西真正交付出去；D/E 其余是**在 A/B 建好的东西上加纵深**（traceId 的价值要靠 A4 的异步日志才体现）；C 需要新业务模块，放最后。

> **前端并行推进（§18）**：前端的**第一优先级也是"交付"** —— `web-0 仓库抢救`（把已经写好的 2200 行代码按功能补进仓库，
> 现在 clone 下来连 `npm install` 都跑不了）。之后按 **§19 的对齐表**与后端逐行推进：**后端一行 → 前端一行 → 一次联调**。

**建议的依赖关系（别做反）**：
`E1（多环境）→ B4 / B5 / D2` · `A1（缓存）→ A2（浏览量合并）→ D3（压测对比）` · `C1（评论/标签）→ C2 / C3 / C5 / C6`

---

## 10. 风险与坑（都是这个项目里真实会踩的）

1. **`@Transactional` 不回滚 Redis** —— 缓存测试必须 `@AfterEach` 手动清 key，否则测试互相污染、随机红（`UserAdminTest.java:361` 已有先例）
2. **详情缓存与浏览量冲突** —— 最容易做错的一处，浏览量必须独立计数、返回时合并
3. **Spring 异步线程池默认队列无界** —— `spring.task.execution.pool.queue-capacity` 默认是 `Integer.MAX_VALUE`，必须配成有界值并想清拒绝策略（**这是改配置，不是写代码**）
4. **限流取 IP 要过 Nginx** —— 读 `X-Forwarded-For`，否则全站用户共用一个 IP
5. **缓存 key 带版本前缀**（`article:v1:`）—— 将来改缓存结构能整体失效，不用手工清库
6. **后台 `pageAll` 不要缓存** —— 否则管理员改完看不到，以为保存失败
7. **`log-impl=StdOutImpl`** —— 现在是缓存验收的手段，**上线必须关**（生产打全量 SQL 日志是事故）
8. **JWT 默认密钥**（`application.properties:20`）—— 部署必须环境变量覆盖
9. **`docker-compose.yaml` 端口（3310）与 README（3307）不一致** —— 顺手修
10. **限流的两层要分清算法** —— Nginx `limit_req` 是**漏桶**（按速率排队，超了直接返 503）；Resilience4j 默认是**周期性配额**（每周期刷新许可数）。被问"你的限流是什么算法"要答得出来
11. **MDC + 线程池** —— traceId 存在 `ThreadLocal` 里，异步/定时任务里拿不到，必须手动传递（这是 D2 和 A4 的交叉坑）
12. **改 HTTP 状态码是破坏性改动**（E8）—— 前端所有错误分支都要跟着改，改之前想清楚
13. **Resilience4j 注解不生效的静默失效** —— 必须加 `resilience4j-spring-boot3`（带 aspect 自动配置）；只加 `resilience4j-ratelimiter` 核心包时 `@RateLimiter` **不生效也不报错**，最容易查半天

---

## 11. 验收总表（做完怎么证明，不靠"感觉"）

| 技术 | 可观测证据 |
|---|---|
| 列表/详情缓存 | 第二次请求 SQL 日志无输出；发文章后列表立即更新；删 key 后自愈重建 |
| 缓存三件套 | 查不存在 id 不反复打库；并发打热点 key 只有一次回源；key 的 TTL 不集中过期 |
| 浏览量计数 | 100 次刷新期间无 UPDATE；5 分钟后 DB 一次性 +100 |
| 限流 | 第 6 次登录返回 **HTTP 429 + `code:429`**；窗口过后恢复 |
| 操作日志 | 后台写操作后 `operation_log` 有记录；响应耗时不明显增加 |
| **索引优化（D1）** | `EXPLAIN` 前后对比：`type` 不再是 `ALL`，`Extra` 无 `Using filesort` |
| **traceId（D2）** | 一次请求的日志行共享同一个 `[traceId]`；响应头回写 `X-Trace-Id` |
| **压测（D3）** | README 有"加缓存前/后"QPS 与 P99 对比表 |
| **登出失效（E2）** | 登出后旧 token 调 `/auth/me` → **401** |
| **幂等（E3）** | 连点两次发布 → 只产生一篇文章 |
| **权限矩阵（E6）** | README 有"18 个接口 × 角色"矩阵，且每一条都能在 `UserAdminTest` / `ArticleAdminTest` 里找到对应用例 |
| Testcontainers | `docker compose down` 之后 `mvn test` 仍全绿 |
| CI | push 后 Actions 跑 `mvn verify` + 覆盖率报告 + README 徽章 |
| OSS 上传 | 传 jpg 成功返回可访问地址；传 .exe / 超限文件被拒；RAM 子账号只对指定前缀有写权限 |
| 阿里云部署 | 公网域名可访问；三容器健康；**重启 Docker 后自动恢复**；**MySQL/Redis 不对公网暴露** |
| 文档（G） | README 的端口/接口数/模块状态与代码 100% 一致 |

---

## 12. 面试追问自检（每项都要能一句话答"不加会怎样"）

| 问题 | 答案要点 |
|---|---|
| 为什么用 Redis 缓存而不用本地缓存？ | 单机可以 Caffeine，多实例必须 Redis（本地缓存各存一份，会不一致）；两者结合就是多级缓存（C4） |
| 缓存和数据库不一致怎么办？ | 先更库再删缓存 + TTL 兜底；讲清"更新缓存"为什么在并发下会写脏 |
| 为什么浏览量不用 MQ？ | 弱数据、可容忍丢失，上 MQ 是过度设计 |
| 限流为什么不自研？ | 限流是**基础设施不是业务**；手写要处理脚本原子性 / key 过期 / 规则热更新，成熟库都解决了。**自研基础设施 = 教程项目特征** |
| 为什么用 Resilience4j 不用 Sentinel？ | 纯库、零额外部署，单实例够用；Sentinel 要跑 Dashboard 才有动态规则，这个规模不划算 —— 但要知道 Sentinel 强在哪（流控 + 熔断 + 系统自适应保护 + 控制台动态改规则） |
| 你这个项目的 AOP 用在哪？ | `@Transactional` 声明式事务 + `@PreAuthorize` 方法级权限，**都是 Spring 提供的现成切面，没有自研切面**。这么答不丢分，反而显得务实 |
| 为什么登出不能只靠前端删 token？ | 删的是本地副本，服务端仍认这个签名；必须黑名单或版本号 |
| 单机博客为什么不做微服务？ | 没有业务理由，服务治理/分布式事务的复杂度换不来收益——**教程项目特征，一问就露** |
| 76 个用例的价值在哪？ | 覆盖认证链路、权限边界、草稿隔离、异常处理；**能跑（Testcontainers）才有价值** |
| 你怎么定位一次线上问题？ | traceId 串日志 → 看 SQL 与慢查询 → 看指标（缓存命中率/限流拒绝数）→ 复现 |

---

## 13. 本版"不做"清单（被问到就这么答）

| 不做 | 原因 |
|---|---|
| Spring Cloud Alibaba 微服务（Nacos / Gateway / OpenFeign）**在项目里实现** | 单机个人博客拆微服务没有业务理由。**注意区分**：简历里写"了解 Spring Cloud"是知识声明；"在博客里拆微服务"才是减分项，两件事不矛盾 |
| ShardingSphere 分库分表、Redis 集群/哨兵、读写分离 | 没有数据量支撑，答不上"为什么需要" |
| Kafka | 这个量级 RabbitMQ 足够；用 Kafka 会被问"你需要它的什么特性" |
| Kubernetes / Service Mesh | 单机 1 个后端容器，上 K8s 是拿复杂度换"看起来很厉害" |
| 为凑关键词硬塞 Spring AI / 大模型 | 除非真做一个具体小功能（如 AI 生成摘要），否则不碰 |
| 再引入 Sa-Token 换一套鉴权 | 已有 Spring Security，堆第二套只显得没主见 |
| **自研基础设施轮子**（限流注解、操作日志切面、`TraceIdFilter`、DFA 敏感词树、分布式锁） | 公司里没人这么干。这些都有成熟方案：**Resilience4j**（限流熔断）、**Spring 事件**（审计）、**Micrometer Tracing**（链路追踪）、**sensitive-word**（敏感词）、**Redisson**（分布式锁）。自研它们只会暴露"没做过真实项目" |

---

## 14. 需要你确认的事（确认后我就开始实施）

**A. 实施范围（挑一个或多个）**

- [ ] **第一步（最稳，推荐先做这个）**：**M0 + M1** = G（文档对齐）+ B1/B2（Testcontainers + CI）+ B3（Flyway）—— 约 1.5 天，**完全不动业务代码**，做完仓库就变成"任何人 clone 下来都能跑测试、有 CI 徽章、README 与代码一致"
- [ ] **最小组合**：A1 + A2 + B1 + B2 + B5 + G —— 约 4–5 天，覆盖"技术纵深 + 能跑的测试 + 真上线"
- [ ] **完整批次 A + B**：缓存/切面/工程化/交付 —— 约 6–7 天
- [ ] **再加 D（性能与可观测）**：+2 天，多出 EXPLAIN、traceId、压测数据
- [ ] **再加 E（安全与配置）**：+2–3 天，多出多环境、登出失效、幂等、权限矩阵
- [ ] **C / F**：业务补全与前端站点，等前面做完再定

**A2. 前端范围（`yiguixingtu-web`，详见 §17–§19）**

- [ ] **web-0 仓库抢救（半天，最紧急）**：把已经写好的 2200 行按功能补进仓库 —— 现在 `git ls-tree -r HEAD` 只有 7 个文件，**clone 下来连 `npm install` 都跑不了**
- [ ] **web-1 前端工程化**：Vitest 测试环境 + CI + API 地址环境变量化（+1 天）
- [ ] **web-2 前台补齐**：分类筛选、统计接口、去掉硬编码假数据（+1 天）
- [ ] **web-3 上线准备**：封面上传组件、静态资源上 OSS、Dockerfile、README（+1–2 天）
- [ ] **web-5 安全体验**：登出链路、记住密码明文问题、traceId 提示、防连点（+1 天）
- [ ] **web-7 SEO 与性能**：首页 SSR、meta/OG、sitemap、按需引入（+1 天）

**B. 需要你提供的信息（没有就先占位）**

- [ ] ECS 地域/规格/公网 IP、OSS bucket/endpoint/地域、备案号
- [ ] 前端形态：**SSG 静态**还是 **SSR Node**（决定前端放 OSS+CDN 还是 ECS 跑容器，也决定 **w3.3** 怎么写）
- [ ] 阿里云授权方式：RAM 子账号（简单）还是 STS 临时凭证（更安全，要写后端签发接口）
- [ ] 前端是否要建 GitHub remote？（现在 `git remote -v` 是空的，后端是 `yigalaxy-blog-new`）

**C. 需要你拍板的设计决策**

- [ ] **E8 · HTTP 状态码改造**：现在改（干净、但要同步改前端），还是先记录为"有意约定"（不动前端）
- [ ] **E2 · 登出失效方案**：Redis 黑名单（按 token）还是版本号（按用户）
- [ ] **G · README/BACKEND_PLAN 现在就更新**，还是等代码写完一起更新（建议现在改，因为面试官可能随时点开仓库）
- [ ] **w0.1 那个 IDE 空模板** `app/pages/login.vue`：确认是误 `git add`，直接 `git rm --cached` 删掉（方案已写进 §18）

---

## 15. 开发约束（硬规则 —— 每个功能都必须遵守）

> 这一节和上面的技术方案同等重要。**技术方案决定做什么，这一节决定怎么做才允许提交。**
> 所有规则都是从仓库现有历史里总结出来的，不是额外加的门槛。

### R1 · 一个功能 = 一份测试，没有测试不允许提交

- **每个新功能或改动，必须同时提交对应的测试代码。** 没有测试的改动不许进仓库。
- 测试位置：`src/test/java/com/yigalaxy/yiguixingtu/`，按模块建类，命名**沿用现有风格**：
  `XxxAdminTest`（后台/权限）、`XxxPublicTest`（前台公开）、`XxxSecurityTest`（认证）、`XxxCacheTest`（缓存）
- 用例方法名沿用现有格式：**`动作_条件_should预期`**，参考历史上已有的写法
  `disableSelf_shouldBeRejected`、`page_invalidSize_shouldBeClamped`、`disabledUser_oldToken_shouldBeRejected`、`update_clearCover_shouldActuallySetNull`
- 每个功能**至少覆盖四类**（这是 §0.1 里现有 76 个用例已经在做的标准）：

  | 类别 | 要求 | 现有示例 |
  |---|---|---|
  | 正常路径 | 成功返回**且校验数据库真的写进去了** | `create_draft_shouldSaveAsDraft` |
  | 边界 | 空值 / 超长 / 非法值 / size 上限 | `page_invalidSize_shouldBeClamped`、`create_blankTitle_shouldReturn400` |
  | 权限 | 无 token → 401、游客 → 403 | `noToken_shouldReturn401`、`guest_shouldReturn403` |
  | 失败路径 | 不存在 → 404、非法参数 → 400 | `update_notExist_shouldReturn404`、`invalidStatus_shouldReturn400` |

- **断言要落到数据库**：用 `JdbcTemplate` 直查原生 SQL 验证，不要只断言 HTTP 状态码。
  现有 `ArticleAdminTest` 就是这么验"删除是逻辑删除"的（物理行还在、`deleted=1`）——因为 `@TableLogic` 会自动过滤，用 Mapper 查不出来。
- **分页/总数类断言必须用唯一标记**：先造带唯一标记的数据（现有用 `ZZT + 纳秒时间戳`）并用 `keyword` 圈定范围，
  否则库里已有的真实数据会让 `total` 断言飘忽不定。**这种测试比没有还糟。**
- **纯文档提交（README/本文件）不需要新测试**，但必须跑一次全量测试确认没顺手弄坏东西。

### R2 · 不绿不提交

```powershell
docker compose up -d          # 本地依赖（M1 之后测试不再依赖这一步）
.\mvnw.cmd -B test            # 必须全绿，76 个用例是当前基线
git status                    # 确认没有 resume.html / 照片 / _preview.* / target/
git add <本次相关的具体文件>    # 用具体路径，别用 git add -A
git commit -F .git/COMMIT_MSG_TMP
```

- **测试全绿才允许 `git commit`**。不许 `-DskipTests` 提交。
- **不许靠注释掉 / 删除失败的测试来"变绿"**。改测试必须在提交信息的【测试】节说明为什么改。
- 当前基线 **76 个用例全通过**；每个阶段结束后基线只增不减。
- 提交前自检四条：
  1. `git status` 里**没有** `resume.html` / `photo_small.jpg` / `_preview.*`（`.gitignore` 已覆盖，但要确认没被 `-f` 强加）
  2. 代码里**没有**真实密钥 / 密码 / AccessKey
  3. 新增的 `application*.properties` 里没有真实凭据
  4. **README 已按 R5 的清单过了一遍**（改了 / 或已在【文档】节写明无需变更）

### R3 · 一个提交只做一件事（分阶段）

- 按 **§16** 的原子步骤推进：**做一步 → 补测试 → 跑绿 → 提交 → 再做下一步**。
- 判断标准：如果提交标题里需要用「并」连接**两件不相关**的事，就该拆成两个提交。
- 不要攒一个大提交。历史上 `c0c8177` 已经把"文章模块 + 分类 + 前台后台拆两套 + 配置调整"放在一起了，
  那是**一个内聚的功能**（同一个模块），可以接受；"顺手把 Redis 缓存也加进去"就不行。

### R4 · 提交信息风格（照抄现有历史，别自创格式）

**结构**

```
<动词开头的中文标题，可用「、」并列、「并」递进，模块名后接全角冒号>

【分节标题】
- 条目
  · 子条目，用来解释"为什么这么做"

【测试】
- 新增/修改了几个用例、覆盖了什么、基线总数

【文档】
- README 同步了哪几处（接口表 / 技术栈 / 已实现清单 / 配置 / 测试节……）
  或：README 无需变更：<说明原因>

Signed-off-by: 别太在亿啦 <2175548220@qq.com>
```

**规则**

| 项 | 要求 |
|---|---|
| 语言 | 中文 |
| 标题 | **动词开头**：新增 / 完成 / 完善 / 修复 / 调整 / 清理 / 进行。**不要**用 `feat:` `fix:` `chore:` 前缀——历史里从来没有 |
| 标题粒度 | 一句话说清"这个提交加了什么"，长内容放正文。参考长度 20–35 字 |
| 分节 | 常用【新增功能】【问题修复】【性能】【配置调整】【数据库】【接口设计】【测试】 |
| 列表 | 条目 `- `；子条目 `  · `（两个空格 + 全角中点） |
| 强调 | 关键概念用【】框住（历史里大量使用） |
| **写"为什么"** | **这是本仓库最重要的风格**：每个非平凡决定都要写取舍理由。历史里有"为什么用 Hash 不用 JSON"、"为什么草稿返 404 不返 403"、"为什么用 LambdaUpdateWrapper 不用 updateById"——继续这个风格 |
| 【测试】节 | **必写**：新增/修改了几个用例、覆盖了什么、全量基线多少 |
| 【文档】节 | **必写**：README 同步了哪几处，或写明"README 无需变更 + 原因"（见 R5）。**和【测试】节同等重要** |
| 结尾 | 固定 `Signed-off-by: 别太在亿啦 <2175548220@qq.com>` |
| 短提交 | 纯配置/文档的提交可以只有 1–3 条 `- ` 条目（参照 `a9040de`） |

**反例（不要这样写）**

- ❌ `update` / `fix bug` / `123` —— 零信息量
- ❌ `feat: add redis cache` —— 与仓库历史风格不一致
- ❌ 标题里塞 200 字 —— 长内容应该放正文分节
- ❌ 正文只写"改了什么"不写"为什么" —— 这个仓库的标准比这高

**完整示例（M1.2 那次提交，可直接照这个格式写）**

```
新增Testcontainers容器化测试环境，移除对本地MySQL与Redis的依赖

【测试基础设施】
- 此前 8 个测试类的 76 个用例全部 @SpringBootTest，直连本机 MySQL:3310
  与 Redis:6380，src/test/resources 不存在
  · 后果一：换台机器 clone 下来没起容器就全红
  · 后果二：CI 根本跑不了 —— 项目最强的资产只能在自己机器上演示
- 新增 src/test/resources/application-test.properties 与 AbstractIntegrationTest 基类
  · 用 @Testcontainers + @Container 起 MySQLContainer 与 redis GenericContainer
  · 用 @DynamicPropertySource 把容器地址注入数据源与 Redis 配置
  · 容器声明为 static 且不加 @DirtiesContext：每个测试类共用一个实例，
    否则每类起一次容器，76 个用例会跑得像蜗牛
- 8 个测试类改为继承基类

【注意】
- @Transactional 回滚数据库但回滚不了 Redis，缓存相关用例仍需 @AfterEach 手动清 key
  （UserAdminTest 已有先例，照抄即可）

【测试】
- 原有 76 个用例全部通过，且是【在没有本地容器的前提下】通过
- 验收方式：先 docker compose down，再 ./mvnw -B test，必须全绿

【文档】
- README「测试」一节补上容器化说明：不再需要先 docker compose up -d
- README「环境要求」补 JDK 17 与 Docker（Testcontainers 需要能起容器）
- README「快速开始」的依赖启动步骤标注为"仅本地调试需要，跑测试不需要"

Signed-off-by: 别太在亿啦 <2175548220@qq.com>
```

### R5 · 每提交一次，就跟着更新一次 README（硬规则）

**不是"改动大了才更新"，是每一次提交都要过一遍 README。**
这正是问题 #10 的教训：README 现在写着端口 8081、MySQL 3307、"文章/分类在规划中"、接口表只有 4 个——
**这些内容每一句都曾经是对的**，只是每次提交时想"下次再改"，攒到最后就没人改了。

**提交前的 README 检查清单（逐条过，命中就当场改，和代码放同一个提交里）**

| 检查项 | README 里对应位置 |
|---|---|
| 新增 / 修改了接口？ | 接口列表（路径、说明、是否需要登录） |
| 改了权限（谁能访问）？ | 接口 × 角色权限矩阵 |
| 加了依赖 / 中间件 / 框架？ | 技术栈表 |
| 有功能从"规划中"变成"已实现"？ | 「✅ 已实现」/「🚧 规划中」两节 |
| 改了端口 / 配置项 / 环境变量？ | 配置表 + JWT 密钥章节 |
| 改了启动方式 / 部署步骤？ | 「快速开始」+ 部署章节 |
| 测试类或用例数变了？ | 「测试」一节 |
| 目录结构变了？ | 「目录结构」小节 |

**规则**

- **README 的改动必须和代码出现在同一个提交里**，不许单独攒一个"更新文档"的提交（会忘、会漏、会拖延）。
- 本次提交**确实完全不影响 README** 时（例如纯内部重构、只动测试），**在提交信息的【文档】节写明**：
  `- README 无需变更：本次仅内部重构，接口 / 配置 / 技术栈均未变`
  —— **要写出来，不许默默跳过**。写出来这个动作本身会逼你确认一遍"真的不影响吗"。
- **【文档】节和【测试】节一样，是提交信息的固定组成部分**（见 R4）。
- 完成一个批次时，顺手把本文件 §9 的里程碑打勾。

> 成本是每次几分钟；收益是 **README 永远等于代码的当前状态**，面试官随时点开都不会翻车。

### R6 · 联调关卡：后端绿测试 ≠ 功能做完

**定义**：一个功能算"做完"，必须同时满足两条 —— ① 后端接口可用且测试全绿；② **前端页面真的调通了**。
只跑通后端测试就宣布完成，是本项目最容易犯的错。

- **每个提交都要写明"联调验收动作"**：打开哪个页面 → 点哪个按钮 → 期望看到什么。写不出来说明这个功能没有可验证的终点。
- 联调必须在**前后端都真实跑起来**的情况下做（后端 8082 + 前端 3000），不是只跑 `mvn test`：

  ```powershell
  # 终端 1 · 后端
  cd D:\Project\yiguixingtu
  docker compose up -d
  .\mvnw.cmd spring-boot:run          # http://localhost:8082

  # 终端 2 · 前端
  cd D:\Project\yiguixingtu-web
  npm run dev                          # http://localhost:3000
  ```

  ⚠️ **前端必须跑在 3000**：后端 CORS 白名单目前写死 `http://localhost:3000`（`SecurityConfig.java:159`），
  换端口就会跨域失败。这也正是 3.1 要把它配置化的原因。
- **联调发现的问题，改哪个仓库就在哪个仓库提交**，并在两边的提交信息里互相引用：
  后端写【联调】`前端 admin.vue 封面上传组件已验证`；前端写【联调】`对应后端 POST /upload（3.2）`。
- **联调不通过 → 功能不算完成，不许往下走。** 宁可停下修，也不要带着"前端还没接"继续加新功能。

### R7 · 两个仓库分开提交，各自保持可运行

| | 后端 | 前端 |
|---|---|---|
| 路径 | `D:\Project\yiguixingtu` | `D:\Project\yiguixingtu-web` |
| 远程 | `github.com/YiGalaxy/yigalaxy-blog-new` | 暂无 remote |
| 提交风格 | R4 | **同一套 R4 风格**（中文动词标题 + 【分节】+【测试】+【文档】+ `Signed-off-by`） |

- **一次功能改动通常产生两个提交**（后端一个、前端一个），**分属两个仓库、各自独立**，不要试图合并。
- **每个仓库的提交必须自洽**：单独 checkout 后端，后端能跑、测试全绿；单独 checkout 前端，前端能 `npm run build`。
  禁止出现"必须前后端同时更新才能工作"的提交——那会让 `git bisect` 失效。
- **提交顺序固定：后端先、前端后**（前端依赖后端接口）。做法：新接口**向后兼容地先上后端**，前端再跟上调用。
- 破坏性变更（例如 5.6 统一 HTTP 状态码）必须两端在**同一个时间窗内**完成，且后端在过渡期保留旧行为，否则中间态会挂。

### R8 · 前端也要有测试，联调要留痕

**现状：前端零测试** —— `package.json` 里没有 `test` script，没有 vitest / playwright，`src` 下没有任何测试文件。
所以前端的第一件事是**把测试环境搭起来**，之后才谈"新功能要配测试"。

- **最低要求**
  - `vitest` + `@nuxt/test-utils`：测组合式函数（`useApi` 的 `code≠200` / 401 / 403 / 网络异常四条分支、`useAuth` 登录与登出、`useAuthUi` 开关互斥）
  - `vitest` 组件测试：关键组件（文章卡片、登录弹窗、封面上传）
- **推荐**：`playwright` 跑 3 条关键链路 —— 登录→进后台→发文→前台可见；草稿不出现在前台；未登录访问 `/admin` 被拦回首页
- `package.json` 新增 `test` script，**提交前 `npm run test` 必须全绿**（与 R2 同等要求）
- 前端提交信息的【测试】节同样必写：新增/修改了几个用例、覆盖了什么

---

## 16. 分阶段提交清单（后端仓库 `D:\Project\yiguixingtu`）

> 每一步都是「**做 → 补测试 → 跑绿 → 更新 README → 提交**」。四个动作缺一不可。
> 「测试要求」列写明了这一步**必须新增哪些测试**；README 的同步内容写在每个阶段表格下面。
> 阶段对应 §9 的里程碑；**建议先做 M0 + M1**（完全不动业务代码）。

### M0 · 文档与演示数据（半天，2 个提交）

| # | 提交标题 | 内容 | 测试要求 |
|---|---|---|---|
| **0.1** | `完善README与后端规划文档，与当前代码实现对齐` | README：端口改 8082、MySQL 改 3310、"已实现"补全文章/分类模块、**18 个接口表**、接口 × 角色**权限矩阵**、目录结构补 `article`/`category`、测试节改 8 类 76 用例；`BACKEND_PLAN.md` 标注已完成部分并修正测试类数量 | 无代码改动 → **跑一次全量 `-B test` 确认仍全绿** |
| **0.2** | `新增本地演示数据脚本，供后台用户管理页面演示` | `docs/demo-data/demo_users.sql`：100 个 GUEST 演示用户（用户名 `demo_001`~`demo_100`、中文昵称、注册时间按天散开约 100 天、每 7 个禁用 1 个）；**⚠️ 绝不能放进 `src/main/resources/db/migration/`**（会被 Flyway 在生产环境执行） | 脚本只在本地执行；执行后 `GET /user/page` 能翻出 100 条；`DELETE FROM user WHERE username LIKE 'demo%'` 可一键清理 |

### M1 · 让已有资产可验证（1 天，3 个提交）

> ⚠️ **执行顺序相对初版做了调整：Flyway 提到 Testcontainers 前面。**
> 原因很实在：Testcontainers 起的是一个**全新的空库**，里面一张表都没有。
> 如果先做 Testcontainers，76 个用例会因为"表不存在"全红；
> 要么给测试单独写一份建表脚本（**表结构就有了两份定义，迟早不一致**），
> 要么先让 Flyway 把建表这件事变成"启动时自动完成"——**开发库、测试库、生产库共用同一份 `V1__init.sql`**。
> 所以顺序是 **1.1 Flyway → 1.2 Testcontainers → 1.3 CI**。

| # | 提交标题 | 内容 | 测试要求 |
|---|---|---|---|
| **1.1** | `新增Flyway数据库版本化迁移，统一表结构初始化方式` | `src/main/resources/db/migration/V1__init.sql`（user/category/article 现有结构，**照数据库实录**）、`baseline-on-migrate=true`；README 删掉手工建表 SQL | 空库启动自动建表；现有 76 用例仍全绿 |
| **1.2** | `新增Testcontainers容器化测试环境，移除对本地MySQL与Redis的依赖` | `AbstractIntegrationTest` 基类（**单例容器** + `@DynamicPropertySource`，不用 `@Testcontainers` 的按类启停）、8 个测试类继承 | **`docker compose down` 之后 76 个用例仍全绿**（这就是验收） |
| **1.3** | `新增GitHub Actions持续集成与JaCoCo覆盖率报告` | `.github/workflows/ci.yml`（JDK **17**，与 `pom.xml` 的 `java.version=17` 一致）、JaCoCo 插件、surefire 报告上传、README 徽章 | CI 上跑通；本地 `mvn -B verify` 能产出覆盖率报告 |

> **README 同步（3 个提交各过一遍 R5 清单）**
> 1.1 → **删掉手工建表 SQL**，改成"启动时 Flyway 自动迁移"，配置表补 Flyway 项；1.2 → 「测试」节补容器化说明（不再需要先 `docker compose up -d`）、「环境要求」补 Docker；1.3 → 顶部加 CI 徽章 + 新增构建/覆盖率说明。

### M2 · 缓存纵深（2 天，4 个提交）

> ### ⚠️ 2.1 已暂停：两个未查清的现象（2026-09-10 记录，避免重复踩）
>
> 2.1 的代码**写完并跑起来过**（`RedisConfig` + `@Cacheable` + 版本号失效 + 12 个用例），
> 但用例存在**偶发失败**，按"不绿不提交"没有提交，改动暂存在 `git stash` 里。
> 下面两条是已经查实/待查清的结论，下一次继续时从这里开始，不要从零重来：
>
> **① 已查实：`@CacheEvict(allEntries = true)` 不能用 —— Spring 的 `RedisCache.clear()` 是异步的**
>    · 用一个"只碰缓存、不碰数据库"的探针实测（同一线程内顺序打印）：
>      | 操作 | 立刻查 Redis | 500ms 后再查 |
>      |---|---|---|
>      | `cache.put(k, v)` | 已写入 | —— |
>      | `cache.clear()` | **还在！** | 已删除 |
>      | `cache.evict(k)` | 已删除 | —— |
>    · 也就是说 `clear()` 返回时删除还没完成，后台线程继续删。
>      业务表现是"发布文章后刷新前台，有时看得到有时看不到"，测试则是随机红
>    · **显式指定 `BatchStrategies.keys()` 也不能把它变同步**（试过），
>      说明这是该 API 的语义而不是配置问题
>    · 因此 2.1 改成**版本号方案**：Redis 里存 `article:page:version`，
>      缓存 key 形如 `article:page:v1:{版本号}:{分页条件}`，
>      写操作只做一次 `INCR`（原子、同步）——版本一变旧 key 再也拼不出来，
>      等于瞬间全部失效，且一条数据都不用删。写入代价也从 O(缓存条数) 降到 O(1)
>
> **② 待查清：缓存写入偶发"不落地"**
>    · 现象 A：**空结果（total=0 的分页）完全没有被写进 Redis**——
>      TRACE 日志已打印 `Creating cache entry`，但紧接着 `keys "*"` 是空的
>    · 现象 B：非空结果的用例**大部分通过，但每次失败的用例不一样**；
>      例如"造 8 条缓存"的用例偶尔只数到 7 条
>    · 现象 C：单独跑某条用例时它通过，跟其他用例一起跑就不一定
>    · 已排除：缓存管理器确为 `RedisCacheManager`、key 前缀与预期一致
>      （`article:page:v1:...`）、`put` 本身是同步的、Docker 环境正常
>    · 下一次的排查建议：启动一个真实的 Redis 容器并在测试期间跑
>      `redis-cli MONITOR`，把**命令流**抓出来对照 —— 现在缺的就是"到底有没有发出 SET"
>      这个直接证据；也可以先做一个最小复现（不依赖 Spring，直接 `RedisCache.put` 多次）
>
> **③ 顺带记录：这一版把"防缓存穿透"降级为待办**
>   原本计划"空结果也缓存一个短 TTL"来防穿透，因为现象 A 没能验证，
>   按"没验证过的行为不进提交"先不做。**当前版本没有防穿透**，这是已知缺口。
>
> 另外记一条环境事实：这次排查期间 Docker Desktop 自己退出了，
> 导致测试报 `Could not find a valid Docker environment`（表现为
> `ExceptionInInitializerError` 而不是"连不上容器"）。遇到这个报错先看 Docker 是否在跑。

| # | 提交标题 | 内容 | 测试要求 |
|---|---|---|---|
| **2.1** | `新增RedisConfig与文章列表缓存，处理缓存穿透与雪崩` | `config/RedisConfig.java`（Jackson + **`JavaTimeModule`**）、`@EnableCaching`、`pagePublished` 加 `@Cacheable`（key 带 `article:v1:` 版本前缀）、空值缓存防穿透、TTL 随机防雪崩 | 新增 `ArticleCacheTest`：第二次请求不再打库、查不存在 id 不反复回源、TTL 有随机性、**`@AfterEach` 清 key**（`@Transactional` 管不了 Redis） |
| **2.2** | `新增文章详情缓存与互斥锁重建，保证更新后缓存一致性` | 详情手写 `RedisTemplate`、`SETNX` 互斥锁 + 双重检查、写操作后**先更库再删缓存** | 并发打热点 key **只有一次回源**；发表/编辑文章后列表立刻更新；手动 `DEL` 后自愈重建 |
| **2.3** | `新增浏览量Redis计数与定时批量落库，消除详情接口的写放大` | `getPublishedDetail` 改为 `INCR article:view:{id}`；新增 `task/ViewCountSyncTask`（`@Scheduled`）+ 一条 `CASE WHEN` 批量 UPDATE；返回时 `DB 值 + Redis 增量` 合并 | 连续刷新 100 次详情 → 这 5 分钟内 SQL 日志**无 UPDATE**；5 分钟后 DB 一次性 +100 |
| **2.4** | `新增站点统计接口，供首页与后台概览展示全站数据` | 新增 `GET /article/stats`：全站已发布文章数 / `SUM(view_count)` / 分类数（**为前端 B1、D1 补的接口**，见 §17.4）；走缓存 + 短 TTL | 新增用例：统计值 = DB 真实聚合值；空库时返回 0 不报错；草稿不计入 |

> **README 同步（4 个提交各过一遍 R5 清单）**
> 2.1 → 技术栈表 Redis 行改为"缓存认证信息 + 文章列表缓存"，「已实现」补"文章列表缓存"；2.2 → 「已实现」补"详情缓存与更新后一致性"；2.3 → 「已实现」补"浏览量异步落库"，配置章节说明 Redis key 命名（`article:v1:*` / `article:view:*`）；2.4 → **接口表加 `GET /article/stats`**（标注公开可读），说明返回口径（只统计已发布）。

> ⚠️ 2.1/2.2 做完再回来**升级简历技能栏**（见 §8 简历同步规则第 2 条）：缓存三件套从"了解"行移进「数据库与缓存」掌握级。

### M3 · 交付上线（2 天，5 个提交）

| # | 提交标题 | 内容 | 测试要求 |
|---|---|---|---|
| **3.1** | `新增多环境配置拆分，将跨域白名单与Swagger开关移入配置` | `application-dev/prod.properties`、CORS 白名单配置化（现写死在 `SecurityConfig.java:159`）、prod 关 Swagger 与 `StdOutImpl` | 新增用例：dev 与 prod profile 下行为差异（Swagger 是否可访问、SQL 日志是否输出） |
| **3.2** | `新增阿里云OSS封面图上传接口，采用RAM最小权限与签名URL` | `upload` 模块、`POST /upload`（ADMIN）、类型白名单 + 大小限制 + UUID 重命名 + 按日期分目录 | 新增 `UploadTest`：正常 jpg 成功；`.exe` 被拒 400；超限被拒；**非 ADMIN 403** |
| **3.3** | `新增Dockerfile多阶段构建与三容器编排，支持一键部署` | 多阶段 `Dockerfile`（非 root 运行）、`.dockerignore`、compose 扩成 backend+mysql+redis（healthcheck + restart + `depends_on: service_healthy`）、prod 不映射数据库端口 | `docker compose up -d` 后容器 healthy；**重启 Docker 服务自动恢复** |
| **3.4** | `完善部署文档与生产环境必需配置说明` | README 部署章节：ECS 步骤、必需环境变量（`JWT_SECRET`）、OSS 配置项、备份与恢复步骤 | 无代码改动 → 跑一次全量测试 |
| **3.5** | `新增管理员初始化引导，解决空库无法登录后台的问题` | 解决 §0.2 #13：全新库注册只能得到 GUEST，而提升角色又要求 ADMIN。做法二选一 —— **①**（推荐）`app.bootstrap-admin.*` 配置项 + 启动时若"一个 ADMIN 都不存在"则按配置创建管理员并打印一次性提示；**②** 提供 `docs/bootstrap/init_admin.sql` 引导脚本。两种都要保证**幂等**（重复启动不会重复建、已有 ADMIN 时静默跳过） | 新增用例：空库启动后存在管理员且能登录后台；**已有 ADMIN 时重复启动不新增账号**；未配置 bootstrap 时不报错 |

> **README 同步（5 个提交各过一遍 R5 清单）**
> 3.1 → 「配置」章节按 profile 重写（dev/prod 各自的值），说明"打包一次、处处运行"；3.2 → **接口表加 `POST /upload`**（标注仅管理员）+ 配置表加 OSS 项（bucket / endpoint / 授权方式）；3.3 → 「快速开始」补 Docker 启动方式、目录结构补 `Dockerfile`/`.dockerignore`、「已实现」补容器化部署；3.4 → 部署章节补全（ECS 步骤、必需环境变量、备份与恢复演练步骤）；3.5 → 「初始化表结构」一节补**「怎么得到第一个管理员」**，把当前的引导 SQL 换成正式做法。

### M4 · 限流与审计（1 天，2 个提交 —— **全部用主流现成方案，不写一行自研基础设施**）

| # | 提交标题 | 内容 | 测试要求 |
|---|---|---|---|
| **4.1** | `新增Nginx与应用层两层限流，并用Resilience4j实现熔断降级` | `resilience4j-spring-boot3` + `@RateLimiter` 实例规则（**库自带 aspect，不写 `@Aspect`**）；`ResultCode` 加 429；`GlobalExceptionHandler` 捕获 `RequestNotPermitted`（**返回 `ResponseEntity`**）；Nginx 配 `limit_req`；落点 `/auth/login`、`/article/page` | 第 6 次登录返回 **HTTP 429 + `code:429`**；窗口过后恢复；Nginx 层压测能触发 503；外部调用超时走熔断降级 |
| **4.2** | `新增Spring事件驱动的操作审计，事务提交后异步落库` | `event/OpLogEvent` + `@TransactionalEventListener(AFTER_COMMIT)` + `@Async` + `OperationLog` 实体；线程池走 `spring.task.execution.*`（**配 `queue-capacity` 为有界值**） | 后台写操作后 `operation_log` 有记录且响应耗时不明显增加；**故意让业务回滚 → 不产生日志**（证明 `AFTER_COMMIT` 生效） |

> **README 同步（2 个提交各过一遍 R5 清单）**
> 4.1 → 接口表给 `/auth/login`、`/article/page` 标注"有限流"，「统一返回」章节补 429，Nginx 章节补 `limit_req` 配置与"为什么两层都要"；4.2 → 「已实现」补"操作审计"、数据库章节补 `operation_log` 表、配置章节说明 `spring.task.execution` 参数。

> ⚠️ 4.x 做完再回来**升级简历**（§8 第 3 条）：技能栏加 `Resilience4j` / `Redisson`；
> 那条「切面与事务」**保持原样不要改**（`@Transactional` / `@PreAuthorize` 就是主流用法），
> **全篇都不要出现"自研切面/自定义注解"这类词**（见 §20）。

### M5 · 可观测与安全（2–3 天，9 个提交）

| # | 提交标题 | 内容 | 测试要求 |
|---|---|---|---|
| **5.1** | `新增Micrometer Tracing链路追踪与logback结构化日志` | `micrometer-tracing-bridge-brave`（**自动生成/透传 traceId，不自研 Filter**）+ `logback-spring.xml`（按天滚动 + 保留 15 天）、pattern 加 `[%X{traceId}]`；用 B3 传播以便响应头直接带 `X-B3-TraceId` | 一次请求的日志行共享同一 traceId；⚠️ **异步线程（4.2 的审计监听器）默认拿不到 traceId**，需要给线程池加 `ContextPropagatingTaskDecorator` —— 这一步必须验证到位 |
| **5.2** | `新增登出接口的Token失效机制` | Redis 黑名单（按 `jti`）或版本号（按用户），二选一；前端登出后旧 token 立即不可用 | 登出后旧 token 调 `/auth/me` → **401**；未登出的 token 不受影响 |
| **5.3** | `新增数据库索引优化与慢查询治理` | 慢查询日志、`EXPLAIN` 验证 `(status, create_time)` 复合索引、把前后对比贴进 README | 用例不受影响；README 有 EXPLAIN 对比 |
| **5.4** | `新增基于唯一索引与Redisson锁的接口幂等` | 数据库唯一索引兜底（**主力**）+ Redisson `RLock` 辅助（**不自研 `@Idempotent` 切面**） | 连续两次发布只产生一篇文章；唯一索引冲突时返回友好提示而不是 500 |
| **5.5** | `新增登录验证码与失败次数锁定`（可选） | Redis 存验证码 + 失败计数 | 验证码错误被拒；失败 5 次后锁定 |
| **5.6** | `统一异常响应的HTTP状态码`（**破坏性，需先确认**） | 5 个 handler 改返回 `ResponseEntity` | 全部异常用例同时断言 HTTP 状态码与 `body.code`；**前端需同步改** |
| **5.7** | `新增Prometheus指标暴露与自定义业务指标` | `micrometer-registry-prometheus`、`/actuator/prometheus`、自定义 **缓存命中率**（2.x）与 **限流拒绝数**（4.1）；放行 `/actuator/health` 供容器探针使用 | 指标端点可访问且带自定义指标；health 探针返回 200；现有用例不受影响 |
| **5.8** | `新增数据库连接池调优与压测对比数据` | HikariCP 显式参数（`maximum-pool-size` / `minimum-idle` / `connection-timeout` / `max-lifetime`）；用 `wrk` 对 `/article/page` 压测，记录**加缓存前 vs 后**的 QPS / 平均延迟 / P99 | 用例不受影响；README 有压测对比表；连接池参数有依据（不是抄数字） |
| **5.9** | `新增安全响应头与生产凭据治理` | `X-Content-Type-Options` / `X-Frame-Options` / `Referrer-Policy` / HSTS；prod 专用低权限库账号（替换 `root/root`）、Redis 设 `requirepass`、开启 Dependabot | 响应头在集成测试里断言；用例全绿 |

> **README 同步（每个提交各过一遍 R5 清单）**
> 5.1 → 配置章节补日志项（文件位置、保留天数）与链路追踪说明；5.2 → 认证流程章节补"登出后 token 立即失效"；5.3 → **新增「性能」章节**，贴 EXPLAIN 前后对比；5.4 → 接口表标注"幂等"，说明用的是唯一索引；5.5 → 认证章节补验证码与锁定策略；5.6 → **「统一返回与错误处理」章节**，把"HTTP 状态码 == body.code"这个新约定写清楚（破坏性改动，必须在 README 里落成明文）；5.7 → 「可观测」章节补指标端点与自定义指标口径；5.8 → 「性能」章节补压测对比表与连接池参数说明；5.9 → 「安全」章节补响应头与生产凭据清单。

### M6 · 业务补全（按需）

| # | 提交标题 | 内容 | 测试要求 |
|---|---|---|---|
| **6.1** | `新增标签模块与文章标签关联` | Tag 实体/CRUD/`article_tag` | 标签 CRUD 四类用例 + 文章打标签 |
| **6.2** | `新增评论模块与管理员审核` | 游客评论 + 审核状态流转 | 评论四类用例 + 草稿/未审核评论对前台不可见 |
| **6.3** | `新增RabbitMQ异步通知与搜索索引同步` | 手动 ACK + 死信队列 + 消费幂等 | 重复投递不产生重复数据；死信队列能收到消息 |

> **README 同步（每个提交各过一遍 R5 清单）**
> 6.1/6.2 → 接口表补标签/评论接口、「已实现」更新、数据库章节补 `tag` / `article_tag` / `comment` 表；6.3 → 技术栈表加 RabbitMQ、配置章节补 MQ 连接与队列说明。

---

> **执行纪律（四个动作，缺一不可）**：
> **做** → **补测试** → **跑 `.\mvnw.cmd -B test` 全绿** → **过一遍 README（R5 清单）** → 按 §15 R4 的格式写提交信息 → 提交 → 再动下一行。
> **不要跳步，也不要合并步骤。** 分阶段提交的价值是：出问题时 `git bisect` 能精准定位，面试时 `git log` 本身就是一份"我做了什么、为什么这么做"的证据。

---

## 17. 前端现状盘点（`D:\Project\yiguixingtu-web`）

> 技术栈：Nuxt 4 + Vue 3 + Element Plus + md-editor-v3；11 个源文件、约 2200 行。
> **结论先说**：这个前端的完成度比它"看起来"的高，但**仓库里几乎什么都没有** —— 见 17.1。

### 17.1 🔴 第一优先级：仓库里的代码和本地工作区不是一回事 —— ✅ **已修复（web-0）**

> **修复结果**：仓库 HEAD 从 7 个文件补到 **30 个文件**，三个提交分别是
> `32e5eae`（工程配置 + 请求封装 + 静态资源）、`491e0c5`（应用外壳 + 错误页 + 详情页）、
> `6713354`（后台页 + 路由守卫）。
> 过程中为了让"每个提交都能独立构建"（R7），把原计划的分组做了微调：
> `public/` 静态资源提前到第一个提交，因为 `index.vue` 里 `<img src="/cover-1.png">`
> 是**构建期解析**的，缺文件直接 `UNRESOLVED_IMPORT` 构建失败。
> `a5a921b` 描述与内容不符的问题，已在后两个提交的信息里写明并纠正。
> 另外索引里那个 IDE 空模板 `app/pages/login.vue`（93 字节、内容是 `$END$`）已 `git rm --cached` 移除。

| 事实 | 证据 |
|---|---|
| HEAD 里只有 **7 个文件** | `git ls-tree -r HEAD`：`useAuth.ts` / `index.vue` / `element-plus.ts` + 3 张 png |
| **下列文件全部未纳入版本管理** | `package.json`、`package-lock.json`、`nuxt.config.ts`、`tsconfig.json`、`.gitignore`、`app/app.vue`、`app/error.vue`、`app/pages/admin.vue`（**1002 行**）、`app/pages/article/[id].vue`、`app/middleware/admin.ts`、`app/composables/useApi.ts`、`useAuthUi.ts`、`useReveal.ts`、`public/` 下全部图片/视频/音频 |
| **后果** | 别人 clone 下来**连 `npm install` 都跑不了**（没有 `package.json`）；1000 行的后台、文章详情页、全局错误页**一行都不在仓库里** |
| **提交信息与内容不符** | `a5a921b` 写"接入文章管理：后台 Markdown 编辑器、前台文章流与详情页"，实际只改了 2 个文件（`useAuth.ts` + `index.vue`，+140 / −20）。面试官点开会发现"写明的东西不在" |
| **索引里躺着 IDE 空模板** | `app/pages/login.vue` 状态 `AD`（已 `git add`、工作区已删），内容只有 IDEA 的文件模板 `<template>\n  $END$\n</template>` |

**所以前端的第一件事不是加功能，是"把已经写好的代码按功能补进仓库"** —— 而且要拆开提交，不是一把 `git add -A`。

### 17.2 已经实现的部分（及其调用的后端接口）

| 页面 / 能力 | 文件 | 调用的后端接口 |
|---|---|---|
| 首页文章流（"加载更多"分页 + 关键词搜索） | `index.vue` | `GET /article/page` |
| 分类数据 | `index.vue:215` | `GET /category/list`（**只用于计数，没有筛选 UI**） |
| 文章详情（SSR + Markdown 渲染 + 软 404） | `article/[id].vue` | `GET /article/{id}` |
| 登录 / 注册弹窗 | `app.vue` + `useAuth.ts` | `POST /auth/login`、`POST /auth/register` |
| 刷新后恢复昵称与角色 | `app.vue:136`、`admin.vue:366` | `GET /auth/me` |
| 后台用户管理（筛选 / 排序 / 启禁用 / 改角色 / 重置密码 / 删除） | `admin.vue:371-490` | `GET /user/page`、`PUT /user/{id}/status`、`PUT /user/{id}/role`、`PUT /user/{id}/password`、`DELETE /user/{id}` |
| 后台文章管理 + Markdown 编辑器 | `admin.vue:519-670` | `GET /admin/article/page`、`GET /admin/article/{id}`、`POST /admin/article`、`PUT /admin/article/{id}`、`PUT /admin/article/{id}/status`、`DELETE /admin/article/{id}` |
| 路由守卫（未登录 → 弹登录框 + 回首页） | `middleware/admin.ts` | —— |
| 全局错误页（404 / 403 / 401 / 500 分类文案） | `error.vue` | —— |
| 统一请求封装（带 token、判 `code`、401/403 处理、**SSR 安全的 toast**） | `useApi.ts` | 全部 |
| 音乐播放器 / 时钟面板 / 可拖拽浮窗 / 背景视频 | `index.vue`、`app.vue` | 本地资源，不涉及后端 |

> 后端 **18 个接口前端用了 17 个**；唯一没用的 `POST /auth/logout` —— 正好也是后端那个空实现。**两边都缺，要一起补。**

### 17.3 还没实现的功能（按性质分组，带证据）

**A · 认证链路（要和后端 5.2 一起改）**

| # | 问题 | 证据 | 性质 |
|---|---|---|---|
| A1 | **登出只清本地 cookie，不调后端接口**，也没清 `useState('user')` → 退出后 `user.value` 残留，`isAdmin` 到刷新前仍为 true | `app.vue:120` | 🔴 |
| A2 | **"记住密码"把明文密码写进 cookie**（`rememberMe={username,password}`） | `app.vue:151` | 🔴 安全问题 |
| A3 | `useAuth.logout()` 跳转到**不存在的 `/login`**（无此页面），且全项目没人调用它 | `useAuth.ts:96` | 🟡 死代码 + 死路由 |
| A4 | 401 时直接清 token 弹登录框，**用户正在填的表单内容全丢** | `useApi.ts:74-79` | 🟡 |
| A5 | `app.vue` 与 `admin.vue` 各自调一次 `/auth/me`（重复请求） | `app.vue:136`、`admin.vue:366` | 🔵 |

**B · 首页与前台展示**

| # | 问题 | 证据 | 性质 |
|---|---|---|---|
| B1 | **首页统计数字不准**：浏览量是"已加载文章的合计"，不是全站总数；分类数是列表长度 | `index.vue:173-181`（注释里自己也承认了，需要后端 `GET /article/stats`） | 🟡 |
| B2 | **分类数据拉了却没有筛选 UI**，只用来计数 | `index.vue:215` vs `:168` | 🟡 |
| B3 | **搜索条件不写进 URL** → 刷新即丢、无法分享、无法被收录；也没有输入防抖 | `index.vue:220` | 🟡 |
| B4 | **"留言面板"是硬编码假数据**（`msgs` 两三条写死的访客留言），且只对管理员可见 | `index.vue:233-236`、`:107` | 🔴 一眼假 |
| B5 | **"1 人正在看"是硬编码字符串** | `index.vue:102` | 🟡 |
| B6 | 个人卡片上 **GitHub / 邮箱 / RSS 三个按钮是死的**（`<span>`，无链接无事件） | `index.vue:28-30` | 🟡 |
| B7 | **导航 6 个入口是"开发中"占位**：文章下拉（技术 / 读书 / 随笔）、收藏、项目、友链、⚙ 设置 | `app.vue:18,22-24,28` → `onDev()` | 🟡 |
| B8 | 没有**归档页 / 标签页 / 关于页 / 独立搜索页** | `app/pages/` 只有 index / admin / article | 🟡 |
| B9 | 没有骨架屏，加载态只有一行"加载中…" | `index.vue:71`、`[id].vue:9` | 🔵 |

**C · SEO 与内容分发**

| # | 问题 | 证据 | 性质 |
|---|---|---|---|
| C1 | **首页用 `onMounted` 拉数据（纯 CSR）**，文章列表不在服务端 HTML 里 → 首页文章**不被搜索引擎收录** | `index.vue:252-255`（注释里写"列表页对 SEO 没那么敏感"——博客首页恰恰最需要） | 🟡 |
| C2 | 只有详情页设了 `useHead` 标题；首页无 title / description，全站无 OG 标签、无 canonical | `[id].vue:88` | 🟡 |
| C3 | `public/robots.txt` 只有两行、**没有 sitemap.xml** | `public/robots.txt` | 🟡 |
| C4 | **没有 RSS**（但个人卡片上挂着 RSS 按钮） | —— | 🟡 |

**D · 后台**

| # | 问题 | 证据 | 性质 |
|---|---|---|---|
| D1 | **概览页数字依赖点击顺序**：`artTotal` / `total` / `articles` 只在访问对应菜单时才加载，先点"概览"全是 0，还会显示"还没有文章" | `admin.vue:22-31` vs `:371` / `:519` | 🔴 |
| D2 | 「分类 / 标签」「设置」两个菜单是**占位**（"该模块开发中"） | `admin.vue:219-222`、`:340-341` | 🟡（依赖后端分类 CRUD） |
| D3 | `admin.vue` **1002 行单文件**，表格 / 弹窗 / 表单全挤在一起 | `admin.vue` | 🟡 可维护性 |
| D4 | Element Plus **全量注册**，未按需引入 | `plugins/element-plus.ts:5` | 🔵 打包体积 |

**E · 工程与部署**

| # | 问题 | 证据 | 性质 |
|---|---|---|---|
| E1 | **零测试**：`package.json` 没有 `test` script，没有 vitest / playwright，没有任何测试文件 | `package.json` | 🔴（对应 R8） |
| E2 | **无 CI** | 无 `.github/` | 🔴 |
| E3 | **API 地址硬编码** `http://localhost:8082`，无环境变量覆盖 | `nuxt.config.ts:7` | 🔴 上线必改 |
| E4 | **SSR 未区分服务端 / 浏览器地址**：生产环境服务端要走内网（`http://backend:8082`），浏览器要走公网域名，现在只有一个 `apiBase` | `nuxt.config.ts:5-9` | 🟡 部署坑 |
| E5 | **大资源进 public**：`bg-star.mp4` **12.4 MB** + `bg-music.mp3` 2 MB，会进构建产物和仓库 | `public/` | 🟡 应走 OSS/CDN（正好接后端 3.2） |
| E6 | **前端形态未定**：没有 `ssr` / `routeRules` / nitro preset 配置，SSG 还是 SSR 没决定 | `nuxt.config.ts` | 🟡 决定 3.3 怎么部 |
| E7 | 封面图仍是本地 `public` 资源（后端做 OSS 后前端要接） | `index.vue:184` | 🟡 |
| E8 | README 是 **Nuxt 官方 starter 原文**（全英文模板），未定制；也没有 `.env.example`（`.gitignore` 忽略了 `.env`） | `README.md`、`.gitignore` | 🟡 |
| E9 | 没有 remote 仓库 | `git remote -v` 为空 | 🟡 |

### 17.4 前端需要后端新增/改造的接口（颗粒度对齐的关键）

> **这一节是"前后端颗粒度对齐"的落点**：前端有需求 → 后端要出接口 → 两边各一个提交。

| 需要的接口 | 用途 | 状态 | 对应后端提交 |
|---|---|---|---|
| `GET /article/stats` | 首页个人卡片 + 后台概览的**全站**文章数 / 浏览总数 / 分类数 | **新增** | 2.4（见 §16） |
| `POST /auth/logout` | 真正让 token 失效 | **改造**（现在是空实现） | 5.2 |
| 分类 CRUD（增删改） | 后台「分类 / 标签」菜单 | **新增** | 6.1 |
| 标签 CRUD + 文章打标签 | 标签页、标签筛选 | **新增** | 6.1 |
| `POST /upload` | 后台封面图上传（替换现在的"填 URL"输入框） | **新增** | 3.2 |
| 评论接口（发 / 查 / 审） | 文章评论 | **新增** | 6.2 |
| `GET /article/archive` | 归档页（按年月分组） | **新增（可选）** | C 批 |
| `GET /feed.xml` 或 `/article/rss` | RSS 订阅 | **新增（可选）** | C 批 |
| 分类筛选、关键词搜索 | 首页筛选 | **已支持**（`ArticleQuery` 已有 `categoryId` / `keyword`） | —— |

---

## 18. 前端分阶段提交清单（`D:\Project\yiguixingtu-web`）

> 规则与后端**完全一致**：**做 → 补测试 → 跑绿 → 过一遍前端 README → 提交**（§15 R1–R5）。
> 每个提交在**前端仓库**单独提交；提交信息用 R4 那套格式，结尾同样 `Signed-off-by`。

### web-0 · 仓库抢救（半天，3 个提交）⭐ 最紧急 —— ✅ 已完成

| # | 提交标题 | 内容 | 测试要求 |
|---|---|---|---|
| **w0.1** | `补齐前端工程配置与依赖清单，修复克隆后无法运行的问题` | `package.json`、`package-lock.json`、`nuxt.config.ts`、`tsconfig.json`、`.gitignore`、`.env.example`；**删除索引里的 IDE 空模板 `app/pages/login.vue`**（`git rm --cached`） | 无测试环境时以 `npm install && npm run build` 通过为准 |
| **w0.2** | `补齐应用外壳、全局错误页与文章详情页` | `app/app.vue`、`app/error.vue`、`app/pages/article/[id].vue` | `npm run build` 通过；手动验证：404 页、详情页 SSR 有正文 |
| **w0.3** | `补齐后台管理页与请求封装，使提交记录与实际代码一致` | `app/pages/admin.vue`、`composables/useApi.ts`、`useAuthUi.ts`、`useReveal.ts`、`middleware/admin.ts`、`public/` 静态资源 | 手动验证：登录 → 进后台 → 用户/文章两页能加载 |

> ⚠️ web-0 是"把已有代码补交"，**不改逻辑**，所以测试要求只能是构建 + 手动验证；
> 同时在提交信息里**说清 `a5a921b` 的描述与内容不符**，把历史纠正过来。

### web-1 · 前端工程化（1 天，3 个提交）—— ✅ 已完成

| # | 提交标题 | 内容 | 测试要求 |
|---|---|---|---|
| **w1.1** | `新增前端测试环境（Vitest + Nuxt Test Utils）` | 加依赖、`test` script、首个测试文件 | **新增 `useApi` 四条分支用例**：`code≠200` / 401 / 403 / 网络异常；`useAuthUi` 开关互斥 |
| **w1.2** | `新增前端CI工作流（lint + test + build）` | `.github/workflows/ci.yml`、README 徽章 | CI 跑通；本地 `npm run test` 与 `npm run build` 全绿 |
| **w1.3** | `将API地址改为环境变量注入，分离服务端与浏览器地址` | `runtimeConfig` + `NUXT_PUBLIC_API_BASE`；**SSR 的服务端请求走内网地址**（对应 E3 / E4） | 用例：两种 baseURL 下都能正确拼接 |

### web-2 · 前台功能补齐（1–2 天，与后端 2.x 对齐）

| # | 提交标题 | 内容 | 测试要求 |
|---|---|---|---|
| **w2.1** | `新增首页分类筛选，并将搜索条件同步到URL` | 分类筛选 UI（现在数据拉了没用）、关键词与分类写入 query、加输入防抖（对应 B2 / B3） | 用例：URL 变化 → 请求参数正确；刷新后筛选状态保持 |
| **w2.2** | `对接站点统计接口，修正个人卡片与后台概览数字` | 用 `GET /article/stats` 替换前端本地求和；**后台概览改为进入时主动加载**（对应 B1 / D1） | 用例：统计接口返回 → 渲染正确数字；接口失败降级不崩 |
| **w2.3** | `移除首页硬编码假数据，改为真实数据或删除` | 删掉假留言面板、硬编码"1 人正在看"；RSS / GitHub / 邮箱按钮接真实地址或移除（对应 B4 / B5 / B6） | 用例：无后端数据时不出现任何假内容 |

### web-3 · 上线准备（1–2 天，与后端 3.x 对齐）—— 🔄 3/4 完成

> 已完成：w3.1 后台上传封面组件 → `POST /upload`（提交 `5616535`）、
> w3.3 前端 Dockerfile 与生产构建配置（提交 `38f9385`）、
> w3.4 前端 README 的部署与环境变量章节（同上）。
> 未做：w3.2（12.4MB 背景视频/音频迁到 OSS/CDN）——
> 目前它们仍在构建产物里，构建体积 12.7MB，主要就是被这个视频撑起来的。

| # | 提交标题 | 内容 | 测试要求 |
|---|---|---|---|
| **w3.1** | `新增后台封面图上传组件，对接OSS上传接口` | 替换 `artForm.cover` 的纯文本输入框（对应 E7） | 用例：上传成功后回填 URL；失败给出提示；类型/大小校验 |
| **w3.2** | `将背景视频与音频迁移至OSS/CDN` | 12.4 MB 视频不再进构建产物（对应 E5） | `npm run build` 产物体积明显下降 |
| **w3.3** | `新增前端Dockerfile与生产构建配置` | 按 SSG / SSR 选定形态 + `routeRules` + Dockerfile（对应 E6） | `docker compose up` 后页面可访问；SSR 页面源码含正文 |
| **w3.4** | `完善前端README（开发、构建、部署与环境变量）` | 替换 Nuxt 官方模板原文（对应 E8） | 无代码改动 → 跑一次 `npm run test` |

### web-4 · 与限流对齐（半天）

| # | 提交标题 | 内容 | 测试要求 |
|---|---|---|---|
| **w4.1** | `新增接口限流的提示与请求节流` | 429 单独提示"请求过于频繁"（不再是"网络异常"）；登录等按钮加冷却（对应后端 4.1） | 用例：HTTP 429 → 走到 429 分支而非通用错误 |

### web-5 · 安全与体验（1 天，与后端 5.x 对齐）

| # | 提交标题 | 内容 | 测试要求 |
|---|---|---|---|
| **w5.1** | `修复登出链路：调用后端登出接口并清理用户状态` | 调 `POST /auth/logout` + 清 `user` state；删掉指向不存在 `/login` 的死代码（对应 A1 / A3） | 用例：登出后 `user` 为空、token 被清；接口被调用一次 |
| **w5.2** | `修复记住密码的明文存储问题` | 只记用户名，或改存"记住登录状态"标记（对应 A2） | 用例：勾选后 cookie 里**不含密码** |
| **w5.3** | `错误提示携带traceId便于联合排查` | 把 traceId 显示在错误提示里（后端用 **B3 传播**时响应头会带 `X-B3-TraceId`，前端直接读即可）（对应后端 5.1） | 用例：响应头有 traceId 时提示文案包含它 |
| **w5.4** | `新增写操作的幂等保护与loading态` | 发布 / 删除按钮防重复点击（对应后端 5.4） | 用例：连点两次只发一次请求 |

### web-6 · 内容功能（按需，与后端 6.x 对齐）

| # | 提交标题 | 内容 | 测试要求 |
|---|---|---|---|
| **w6.1** | `新增标签页与标签筛选` | 标签云 / 标签页（后端 6.1） | 用例：标签跳转带上筛选参数 |
| **w6.2** | `新增文章评论组件` | 发表 / 列表 / 待审核提示（后端 6.2） | 用例：提交成功后列表刷新；未登录引导登录 |
| **w6.3** | `新增归档页与RSS订阅` | 按年月分组（后端 C 批） | 用例：归档分组正确 |

### web-7 · SEO 与性能（前端专属，1 天）

| # | 提交标题 | 内容 | 测试要求 |
|---|---|---|---|
| **w7.1** | `首页改为服务端渲染，并补齐全站SEO元信息` | `index` 从 `onMounted` 改 `useAsyncData`；首页 title/description、OG、canonical（对应 C1 / C2） | 用例/验证：`npm run build` 后首页 HTML 里**含文章列表与 meta 标签** |
| **w7.2** | `新增sitemap.xml生成与完善robots.txt` | 构建时产出 sitemap（对应 C3） | 构建产物里有 sitemap.xml 且含已发布文章 URL |
| **w7.3** | `Element Plus改为按需引入以减小打包体积` | `unplugin-vue-components` + `unplugin-auto-import`（对应 D4） | 构建通过；产物体积下降 |
| **w7.4** | `拆分后台单文件组件` | `admin.vue` 1002 行拆成 用户表格 / 文章表格 / 编辑器弹窗等组件（对应 D3） | 拆分后 `npm run test` 与手动回归全通过 |

---

## 19. 前后端颗粒度对齐表（一个功能 = 两个提交 = 一次联调）

> **读法**：同一行里，后端做完 → 前端接着做 → 两边都提交后做一次联调 → 联调通过才算这个功能完成（§15 R6）。
> **顺序永远是后端在前**（前端依赖接口），且**两个仓库各自独立提交**（§15 R7）。

| 后端提交 | 前端提交 | 联调验收动作（真实起 8082 + 3000） |
|---|---|---|
| **0.1** 文档对齐 | **web-0.1 ~ w0.3** 仓库抢救 | 前端 `npm run dev` 能起；首页 / 详情 / 后台三个页面都能打开并拉到数据 |
| **1.1** Flyway | —— | 用一个**全新空库**启动后端 → 前端首页仍能正常拉到文章（说明建表迁移没问题） |
| **1.2** Testcontainers | —— | 不涉及前端 |
| **1.3** CI + JaCoCo | **w1.2** 前端 CI | 两个仓库的 Actions 都是绿的 |
| **2.1** 列表缓存 | **w2.1** 分类筛选 + URL 同步 | 点分类 → 列表过滤正确；刷新页面筛选状态还在；搜索 → 清除 → 恢复正常 |
| **2.2** 详情缓存 | （w2.1 已覆盖） | 后台改标题 → 前台刷新详情**立刻**是新标题（不必等 TTL 到期） |
| **2.3** 浏览量计数 | **w2.2** 统计接口 | 前台刷详情 → 数字 +1；后台 / 概览的数字与 DB 最终值一致 |
| **2.4** `GET /article/stats`（新增） | **w2.2** 对接统计 | 首页三个数字与后台概览数字**两边一致**；不再受"先点了哪个菜单"影响 |
| ——（无后端改动） | **w2.3** 去假数据 | 首页不再出现任何写死的留言 / 在线人数；RSS 按钮要么可用要么不在 |
| **3.1** 多环境配置 | **w1.3** 环境变量 | dev 连 8082、prod 连域名，各构建一次都能跑通 |
| **3.2** OSS 上传 | **w3.1** 上传组件 | 后台上传封面 → 前台卡片和详情显示 OSS 图片；传 `.exe` / 超大文件被拒并有提示 |
| **3.3** Dockerfile + 三容器 | **w3.3** 前端 Dockerfile | `docker compose up -d` 一次性起全栈 → 浏览器访问域名可用 |
| **3.4** 部署文档 | **w3.4** 前端 README | 手机 4G 打开域名：能发文、能看到图、能登录后台 |
| **3.5** 管理员初始化引导（新增） | —— | 用一个**全新空库**启动后端 → 能创建出管理员并登录后台（当前会卡在没有管理员这一步） |
| **4.1** 限流 | **w4.1** 429 处理 | 连点登录 6 次 → 前端提示"请求过于频繁"（**不是**"网络异常"） |
| **4.2** 操作日志 + `@Async` | —— | 后台写操作后前端无感；`operation_log` 有记录 |
| **5.1** logback + traceId | **w5.3** 错误带 traceId | 前端报错时拿 traceId 去后端日志里能定位到同一次请求 |
| **5.2** 登出 token 失效 | **w5.1** 登出链路 | 点"退出" → 后端收到登出 → 再点需要登录的操作 → 401 → 弹登录框且 `user` 已清空 |
| **5.3** 索引优化 | —— | 首页列表加载主观/客观变快 |
| **5.4** 幂等 | **w5.4** 防连点 | 连点"发布"两次 → 只产生一篇文章，提示不重复 |
| **5.6** 错误码统一（破坏性） | 前端错误分支同步改 | **两端必须在同一时间窗内完成**；改完后所有错误提示走 HTTP 状态码 |
| **5.7** Prometheus 指标 | —— | 不涉及前端 |
| **5.8** 连接池 + 压测 | —— | 不涉及前端 |
| **5.9** 安全响应头 + 凭据治理 | —— | 不涉及前端（响应头由浏览器/网关消费） |
| **6.1** 标签 | **w6.1** 标签页 + 后台分类管理（替换 D2 占位） | 后台建标签 → 文章打标签 → 前台标签页可筛选 |
| **6.2** 评论 | **w6.2** 评论组件 | 前台发评论 → 后台审核 → 前台可见；未审核的不可见 |
| **6.3** RabbitMQ | —— | 评论后通知异步到达，接口响应不阻塞 |
| —— | **w7.1 ~ w7.4** SEO / 性能（前端专属） | 构建后首页 HTML 含文章列表与 meta；sitemap 可访问 |

**这张表的用法**：每完成一行，就在后端仓库和前端仓库各提交一次，然后**做一次联调并把结果写进两边提交信息的【联调】节**。
联调没通过的行，不许往下走。

---

## 20. 自研 vs 主流：方案修订表（本版最大的一处方向调整）

> **原则**：**能用主流现成方案就不自研。** 自研轮子在公司里没人这么干，面试官一眼就看出是"教程项目特征"；
> 而"知道该用哪个现成方案、为什么选它、它的边界在哪"才是工程判断力。

| # | 原方案（v2 初版：自研） | 现方案（主流） | 为什么换 | 提交 |
|---|---|---|---|---|
| 1 | 自研 `@RateLimit` 注解 + `@Aspect` 切面 + 手写 Redis Lua 令牌桶 | **Nginx `limit_req`（入口层）+ Resilience4j `@RateLimiter`（应用层）** | 手写要处理脚本原子性 / key 过期 / 规则热更新，成熟库都解决了；**限流是基础设施不是业务** | 4.1 |
| 2 | 自研 `@OpLog` + `@Aspect` + 自建 `ThreadPoolTaskExecutor` | **Spring 事件 + `@TransactionalEventListener(AFTER_COMMIT)` + `@Async`**（线程池走 `spring.task.execution.*`） | `ApplicationEventPublisher` 是框架自带的；`AFTER_COMMIT` 顺手解决了"业务回滚、日志却留下"的老问题 | 4.2 |
| 3 | 手写 `SETNX` 互斥锁防缓存击穿 | **`@Cacheable(sync = true)`**（单实例）/ **Redisson `RLock`**（跨实例） | `SETNX` 的锁续期、误删、可重入全要自己写 | 2.2 |
| 4 | 手写 `UPDATE ... CASE WHEN` 批量落库 | **MyBatis-Plus `updateBatchById` / `saveOrUpdateBatch`** | 库已经做了，没必要手写 | 2.3 |
| 5 | 自研 `filter/TraceIdFilter` | **Micrometer Tracing（Brave）** | Spring Boot 官方链路追踪，自动把 traceId/spanId 注入 MDC | 5.1 |
| 6 | 自研 `@Idempotent` 注解 + AOP + `SETNX` | **数据库唯一索引（主力）+ Redisson `RLock`（辅助）** | 唯一索引最稳、不依赖任何中间件 | 5.4 |
| 7 | 自研 DFA 前缀树敏感词 | **`sensitive-word` 库** | 用成熟库，知道 DFA 原理即可 | C 批 |

**保留不变（这些本来就是主流方案，不算自研）**

- **Spring Security + JWT 认证链路** —— 其中的 `JwtAuthenticationFilter` 是**框架规定的扩展点**，必须自己写，不算自研轮子
- Spring Cache `@Cacheable` / `@CacheEvict`；MyBatis-Plus 分页插件 / 逻辑删除 / 条件构造器
- **`@Transactional` / `@PreAuthorize` 声明式切面 —— 这两个本身就是"主流做法"**，不需要也不应该被"自研切面"替换
- `@Scheduled` 定时任务（Spring 自带）、`@Async`（Spring 自带）
- Testcontainers / Flyway / GitHub Actions / Docker 多阶段构建 / Nginx 反向代理 / 阿里云 OSS SDK

**这次调整对简历的影响**

| | 内容 |
|---|---|
| ❌ 删掉 | `Redis Lua 限流`、`自定义注解 + AOP 切面`、`自建异步线程池` |
| ✅ 换成 | `Resilience4j（限流与熔断降级）`、`Nginx 限流`、`Redisson 分布式锁`、`Micrometer Tracing 链路追踪`、`Spring 事件驱动` |
| 🔒 保持原样 | **项目经历里「切面与事务」那条不动** —— `@Transactional` / `@PreAuthorize` 就是最主流用法，改了反而多话 |

**这样改会不会显得"技术含量低"？不会，反而相反。**

面试官更想看的是**判断力**：说得出"我评估过 Sentinel，但这个规模用 Resilience4j 更划算"，
比"我手写了一个限流注解"值钱得多。
**手写基础设施只证明你会写代码；选对方案、讲清取舍，才证明你会做工程。**

---

> **核心心法**：每加一项技术，先写下"不加会怎样"。面试官问的永远是 **为什么选它** 和 **出问题怎么排查**，
> 不是"你用过哪些框架"。76 个用例 + 缓存三件套 + 一个能讲透的部署，比堆十个框架名管用得多。


