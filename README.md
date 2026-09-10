# yiguixingtu — 个人博客系统（后端）

[![CI](https://github.com/YiGalaxy/yigalaxy-blog-new/actions/workflows/ci.yml/badge.svg)](https://github.com/YiGalaxy/yigalaxy-blog-new/actions/workflows/ci.yml)
![Java](https://img.shields.io/badge/Java-17-blue)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-4.1.1-brightgreen)
![Tests](https://img.shields.io/badge/tests-76%20passing-success)
![Coverage](https://img.shields.io/badge/coverage-86%25-brightgreen)

> 基于 Spring Boot 4 + MyBatis-Plus + JWT 的个人博客后端服务
> Spring Boot 4.1.1 / Java 17 / MySQL 8 / Redis 7
>
> **76 个集成测试全部通过**（覆盖行 86%），测试自带 MySQL / Redis 容器，clone 下来即可验证。

## 项目简介

**yiguixingtu** 是一个个人博客系统的后端服务，已实现 **认证与用户管理**、**文章管理**、**分类** 三个模块，
采用 **JWT 无状态认证**，**MySQL** 存储数据、**Redis** 缓存认证信息，接口文档由 **springdoc** 自动生成。

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
| 缓存 | Redis 7（当前用于缓存认证信息，见「认证与鉴权」） |
| 安全 | Spring Security 7 + JWT（jjwt 0.12.6）+ BCrypt |
| 参数校验 | Spring Validation（`spring-boot-starter-validation`） |
| 接口文档 | springdoc-openapi 3.0.0（OpenAPI / Swagger UI） |
| 运维监控 | Spring Boot Actuator |
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

**分类**
- 分类列表（游客可访问）

**基础设施**
- 统一返回 `{code, message, data}`
- 全局异常处理（业务异常 / 参数校验 / 认证失败 / 账号禁用 / 权限不足 / 兜底）
- 分页与排序参数安全处理（见「接口安全约定」）
- 自动生成 OpenAPI 接口文档
- **Flyway 数据库版本化迁移**：空库启动自动建表，表结构只有一份定义
- **Testcontainers 容器化集成测试**：测试自带数据库与 Redis，clone 下来就能验证
- **GitHub Actions 持续集成**：每次 push / PR 自动构建、跑测试、出覆盖率报告
- **图片上传**：扩展名白名单 + 大小限制 + UUID 重命名 + 按日期分目录；
  存储可切换（本地磁盘 / 阿里云 OSS，见「配置」章节）
- 集成测试 9 个类 **105 个用例**，行覆盖率 **86%**

### 🚧 规划中

- 标签（tag）与文章标签关联
- 评论 / 留言与审核
- 分类的后台增删改（目前只有只读列表接口）
- 文章封面图上传（OSS）
- 文章列表 / 详情缓存、浏览量异步落库
- 站点统计接口
- 归档
- 容器化部署（Dockerfile）与 CI
- 管理员初始化引导（空库目前无法产生管理员，见「怎么得到第一个管理员账号」）

> 详细的开发计划、技术选型取舍与分阶段提交清单见仓库根目录 `TECH_ROADMAP.md`。

## 目录结构

```
com.yigalaxy.yiguixingtu
├── YiguixingtuApplication          # 启动类
├── common
│   ├── Result                      # 统一返回包装 {code, message, data}
│   ├── ResultCode                  # 结果码枚举
│   └── exception
│       ├── BusinessException       # 自定义业务异常
│       └── GlobalExceptionHandler  # 全局异常处理器（6 类异常）
├── config
│   ├── MybatisPlusConfig           # 分页插件
│   └── SecurityConfig              # Spring Security 过滤链 + JWT + CORS + 401/403 JSON
├── auth
│   ├── controller/AuthController   # 登录 / 注册 / 登出 / 当前用户
│   ├── service/UserDetailsServiceImpl
│   ├── util/JwtUtil                # JWT 签发与解析
│   ├── filter/JwtAuthenticationFilter
│   ├── cache/UserAuthCache         # 用户认证信息 Redis 缓存 + token 即时撤销
│   ├── dto/                        # LoginRequest / RegisterRequest / LoginVO
│   ├── JwtProperties               # JWT 配置绑定
│   └── LoginUser                   # 认证用户包装（含 status/role）
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
│   └── dto/                        # ArticleForm / ArticleQuery / ArticleVO
└── category
    ├── controller/CategoryController      # 分类列表（公开）
    ├── service/CategoryService(+Impl)
    ├── entity/Category
    ├── mapper/CategoryMapper
    └── dto/CategoryVO

src/main/resources
├── application.properties
└── db/migration
    └── V1__init.sql                # Flyway 迁移脚本：user / category / article 建表

src/test/java/com/yigalaxy/yiguixingtu
├── AbstractIntegrationTest         # 集成测试基类：起 MySQL/Redis 容器 + 注入连接信息
├── AuthLoginTest                   # 登录链路
├── UserRegisterTest                # 注册
├── JwtSecurityTest                 # JWT 与 Security 过滤链
├── GlobalExceptionHandlerTest      # 全局异常处理
├── UserAdminTest                   # 用户管理（含 token 即时失效）
├── ArticleAdminTest                # 后台文章管理
├── ArticlePublicTest               # 前台公开接口（草稿隔离）
├── PaginationLimitTest             # 分页全局上限（从 Mapper 层验证插件兜底）
├── ProfileDevConfigTest            # dev 环境行为：Swagger 开着 / SQL 日志 / 跨域白名单
├── ProfileProdConfigTest           # prod 环境行为：Swagger 关闭 / 凭据必须来自环境变量
├── AdminBootstrapInitTest          # 管理员初始化引导（空库也能进后台）
└── YiguixingtuApplicationTests     # 冒烟

docs/demo-data
└── demo_users.sql                  # 本地演示数据（100 个用户），切勿在生产执行

Dockerfile                          # 后端镜像（多阶段构建，非 root 运行）
.dockerignore                       # 构建上下文排除清单（含个人材料，见文件内说明）
docker-compose.yaml                 # 本地开发：只有 mysql + redis
docker-compose.prod.yaml            # 生产：backend + mysql + redis 三容器（详见「部署」章节）
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

应用启动时由 **Flyway** 自动执行 `src/main/resources/db/migration/V1__init.sql`，
建好 `user` / `category` / `article` 三张表，并把执行记录写进 `flyway_schema_history` 表。
**空库直接启动就能用。**

表结构的定义**只有一份**（就是那个 SQL 文件），开发库、测试库、生产库共用它——
这也是引入 Flyway 的原因：以前 DDL 散在文档和聊天记录里，换台机器就要照抄一遍，
抄漏一个索引也没人会发现。

```
src/main/resources/db/migration/
└── V1__init.sql          # user / category / article 三张表
```

命名规则是 `V{版本}__{描述}.sql`（**两个下划线**）。Flyway 靠文件名排序，
执行过的版本记在 `flyway_schema_history` 里，不会重复执行。

> ⚠️ **已经执行过的迁移脚本不要再改。** Flyway 会比对校验和，
> 改动已应用的 `V1__init.sql` 会让下次启动直接报错。
> 改表结构的正确做法是**新增** `V2__xxx.sql`，已上线的迁移脚本是历史。

**关于 `baseline-on-migrate`（一个容易被误解的配置）**

本地的库是先手工建好表、Flyway 才接进来的，属于"已存在的非空库"。
不做处理的话，Flyway 会去执行 `V1__init.sql` 并因为"表已存在"直接失败。
所以配置了 `baseline-on-migrate=true`，两种库状态的行为是：

| 库的状态 | Flyway 的行为 |
|---|---|
| **空库**（新部署、Testcontainers 容器） | 正常执行 `V1__init.sql`：建表 + 写历史记录 |
| **已有表的库**（本地开发库） | 打一个"基线 = 版本 1"的标记，**不执行 V1**，当作它已经应用过 |

两种情形共用同一份配置，不需要分环境改。

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
| `UPLOAD_STORAGE` | ⬜ | `local`（默认）/ `oss`。**上线建议设成 `oss`** |
| `OSS_ENDPOINT` / `OSS_BUCKET` | 用 OSS 时必填 | 例如 `https://oss-cn-hangzhou.aliyuncs.com` / `你的-bucket` |
| `OSS_ACCESS_KEY_ID` / `OSS_ACCESS_KEY_SECRET` | 用 OSS 时必填 | ⚠️ RAM 子账号的密钥，只授权该 Bucket 读写 |
| `OSS_SIGNED_URL` | ⬜ | 默认 `false`（公有读）；Bucket 私有读时设 `true` |
| `UPLOAD_BASE_URL` | ⬜ | 仅 `local` 时用：图片对外的地址前缀，例如 `https://你的域名` |

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

#### 文件上传与对象存储

```properties
# local（本地磁盘，默认）/ oss（阿里云 OSS）
app.upload.storage=${UPLOAD_STORAGE:local}
app.upload.allowed-extensions=jpg,jpeg,png,gif,webp
app.upload.max-size=5MB
```

**为什么存储做成可切换的**：本地开发和测试不应该依赖真实 OSS 凭据与外网
（否则 clone 下来的人根本没法验证上传功能，CI 也跑不了）；
上线初期也可以先用本地存储把站点跑起来，等 OSS 配好改一个配置切过去，
不用改代码、不用重新构建。

| 存储 | 图片地址 | 适用 |
|---|---|---|
| `local` | `{base-url}/uploads/cover/2026/09/{uuid}.png`，由后端提供 | 本地开发、测试、还没配 OSS 时 |
| `oss` | `https://{bucket}.{endpoint}/{key}` | 生产环境（推荐） |

切到 OSS 需要这些配置（生产环境请用**环境变量**注入）：

```properties
app.upload.storage=oss
app.upload.oss-endpoint=${OSS_ENDPOINT}          # https://oss-cn-hangzhou.aliyuncs.com
app.upload.oss-bucket=${OSS_BUCKET}
app.upload.oss-access-key-id=${OSS_ACCESS_KEY_ID}
app.upload.oss-access-key-secret=${OSS_ACCESS_KEY_SECRET}
app.upload.oss-key-prefix=${OSS_KEY_PREFIX:cover}
app.upload.oss-signed-url=${OSS_SIGNED_URL:false}  # Bucket 私有读时设为 true
```

> ⚠️ **必须用 RAM 子账号的 AccessKey，不要用主账号密钥。**
> 主账号密钥等于整个账号的权限（能改账单、能删所有资源），一旦泄漏后果不可控；
> RAM 子账号可以只授权这一个 Bucket 的读写，就算泄漏，损失也被限制在一个桶里。
> 这是最小权限原则最典型的一个应用场景。
>
> 缺配置时**启动会直接失败**并告诉缺哪一项，而不是等用户第一次上传才发现 ——
> 与下面 prod 凭据不给默认值是同一个取向。

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

共 **18 个接口**。「是否需要登录」一列指**访问该接口本身**的要求，
具体到角色见下方「接口 × 角色权限矩阵」。

| # | 方法 | 路径 | 说明 | 是否需要登录 |
|---|------|------|------|:---:|
| 1 | POST | `/auth/register` | 用户注册 | 否 |
| 2 | POST | `/auth/login` | 登录（返回 token） | 否 |
| 3 | POST | `/auth/logout` | 退出登录（把当前 token 拉黑，幂等） | 否 |
| 4 | GET | `/auth/me` | 获取当前登录用户 | 是 |
| 5 | GET | `/article/page` | 前台文章分页列表（仅已发布） | 否 |
| 6 | GET | `/article/{id}` | 前台文章详情（仅已发布） | 否 |
| 7 | GET | `/category/list` | 分类列表 | 否 |
| 8 | GET | `/user/page` | 用户分页查询 | 是（ADMIN） |
| 9 | PUT | `/user/{id}/status` | 启用 / 禁用用户 | 是（ADMIN） |
| 10 | PUT | `/user/{id}/role` | 修改用户角色 | 是（ADMIN） |
| 11 | PUT | `/user/{id}/password` | 重置用户密码 | 是（ADMIN） |
| 12 | DELETE | `/user/{id}` | 删除用户（逻辑删除） | 是（ADMIN） |
| 13 | GET | `/admin/article/page` | 后台文章分页（含草稿，多条件筛选） | 是（ADMIN） |
| 14 | GET | `/admin/article/{id}` | 后台文章详情（含正文） | 是（ADMIN） |
| 15 | POST | `/admin/article` | 新增文章 | 是（ADMIN） |
| 16 | PUT | `/admin/article/{id}` | 编辑文章 | 是（ADMIN） |
| 17 | PUT | `/admin/article/{id}/status` | 发布 / 下架文章 | 是（ADMIN） |
| 18 | DELETE | `/admin/article/{id}` | 删除文章（逻辑删除） | 是（ADMIN） |
| 19 | POST | `/upload` | 上传图片（封面图），返回可访问 URL | 是（ADMIN） |

接口文档（`/v3/api-docs`、`/swagger-ui/**`、`/swagger-ui.html`）也无需登录。
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
| `POST /auth/logout` | ✅ | ✅ | ✅ |
| `GET /auth/me` | ❌ 401 | ✅ | ✅ |
| `/user/**`（全部 5 个） | ❌ 401 | ❌ 403 | ✅ |
| `/admin/article/**`（全部 6 个） | ❌ 401 | ❌ 403 | ✅ |
| `POST /upload` | ❌ 401 | ❌ 403 | ✅ |
| `GET /uploads/**`（本地存储的图片） | ✅ | ✅ | ✅ |

**几个刻意的设计决定：**

- **区分 401 和 403**：没带 token 是「你是谁我不知道」→ 401；带了 token 但角色不够是「我知道你是谁，但你不能干这个」→ 403。
- **草稿对所有人返回 404，包括管理员走前台接口**。管理员的入口是 `/admin/article/{id}`，
  前台接口就是给游客看的，统一返回 404 可以避免"探测"到某篇草稿的存在。
- **`/user/**` 的权限写在类上**（`@PreAuthorize("hasRole('ADMIN')")`），
  这样新增方法时不会漏加注解——比每个方法写一遍更安全。

## 统一返回与错误处理

所有接口统一返回：

```json
{ "code": 200, "message": "成功", "data": { } }
```

结果码（`common/ResultCode.java`）：

| code | 含义 |
|------|------|
| 200 | 成功 |
| 400 | 参数校验失败 / 用户名或密码错误 / 账号已存在 |
| 401 | 未登录或登录已过期 |
| 403 | 无权限访问 / 账号已被禁用 |
| 404 | 用户不存在 / 文章不存在 / 分类不存在 |
| 500 | 服务器内部错误 |

### ⚠️ 关于 HTTP 状态码（一个必须说清楚的现状）

本项目**目前不是**"HTTP 状态码 == body.code"。实际规则是：

| 场景 | 由谁处理 | HTTP 状态码 | body.code |
|------|---------|:---:|:---:|
| 未登录 / token 无效 | `SecurityConfig` 的 `authenticationEntryPoint` | **401** | 401 |
| 权限不足（过滤器层） | `SecurityConfig` 的 `accessDeniedHandler` | **403** | 403 |
| 权限不足（`@PreAuthorize`） | `GlobalExceptionHandler#handleAccessDenied` | **403** | 403 |
| 业务异常 / 参数校验 / 认证失败 / 账号禁用 / 未知异常 | `GlobalExceptionHandler` 其余 5 个 handler | **200** | 400 / 401 / 403 / 500 |

也就是说：**鉴权类错误返回真实的 401/403，业务类错误返回 HTTP 200 + 业务 code**。
前端需要判断两次（先看 HTTP 状态码，再看 `body.code`）。

这是一个**已知的、有意保留的现状**，不是遗漏：改成"HTTP 状态码与 body.code 完全一致"属于**破坏性变更**，
必须前后端在同一个时间窗内一起改，目前排在计划的 5.6 项。

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
| `article` | 文章（含草稿与浏览量） | `idx_status_create(status, create_time)`、`idx_category` | Flyway `V1__init.sql` |
| `flyway_schema_history` | Flyway 自己的迁移记录表 | —— | Flyway 自动创建 |

> 表结构**不要手动改**。需要改表就新增一个迁移脚本（`V2__xxx.sql`），
> 让开发库、测试库、生产库走同一条路径。

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
                                    封面图 ──▶ 阿里云 OSS（不走 ECS 磁盘）
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

### 3. Nginx 反向代理

在宿主机配一个站点，把前端和后端分别代理出去：

```nginx
server {
    listen 443 ssl;
    server_name 你的域名;

    # 前端（Nuxt 容器）
    location / {
        proxy_pass http://127.0.0.1:3000;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        # ⚠️ 这一行必须有：后端将来做限流要按真实 IP 统计，
        #    没有它所有请求的来源都会是 Nginx 的地址
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
    }

    # 后端（Spring Boot 容器）
    location /api/ {
        proxy_pass http://127.0.0.1:8082/;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
    }
}
```

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

### 5. 备份与恢复

```bash
# —— 备份（建议加进 crontab 每天跑一次）——
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

> **备份要验证过才算备份**。建议演练一次：拷一份库出来、导进一个新库、
> 启动应用确认文章都在。没验证过的备份，真出事时大概率用不了。

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
| 行覆盖 | **86.2%**（507 / 588） |
| 方法覆盖 | **95.2%**（119 / 125） |
| 指令覆盖 | **86.3%**（2,227 / 2,581） |
| 分支覆盖 | 55.2%（111 / 201） |

> 分支覆盖率明显低于行覆盖率，是因为大量的**参数校验分支、异常兜底分支、
> 空值判断分支**不会被每个用例都走到——这是正常的，不必为了刷数字硬凑用例。
> 真正该关心的是关键路径有没有被覆盖：认证、权限、草稿隔离、逻辑删除这几条都在 100% 附近。

覆盖率**只统计本项目自己的代码**（JaCoCo 配了 `includes: com/yigalaxy/yiguixingtu/**`），
不把依赖库算进去。

### 持续集成（GitHub Actions）

`.github/workflows/ci.yml`，在 **push 到 master** 和 **PR** 时触发：

1. 装 JDK **17**（与 `pom.xml` 的 `java.version=17` 一致）
2. `./mvnw -B verify` —— 构建 + 跑 76 个用例 + 出覆盖率
3. 上传 `surefire-reports` 与 `jacoco-report` 两个 artifact（`if: always()`，测试失败时报告最需要看）

**CI 上不需要配置任何 MySQL / Redis 服务** —— 测试用 Testcontainers 自己拉起容器，
GitHub 的 ubuntu runner 自带 Docker。这正是把测试容器化的价值所在。

### 测试环境自带容器

> ✅ **测试不需要事先启动任何服务。** 测试启动时由 **Testcontainers**
> 自己拉起 MySQL 与 Redis 容器、跑完自动销毁，所以
> **即使先执行 `docker compose down`，`mvn test` 也照样全绿** —— 只需要本机装了 Docker。
>
> 这意味着：任何人 clone 下来就能验证这 76 个用例，CI 上也能跑
> （在此之前，测试直连本机 3310/6380，换台机器不先起容器就全红，CI 更是跑不了）。

**8 个测试类，76 个用例，全部通过：**

| 测试类 | 用例数 | 覆盖 |
|--------|:---:|------|
| `AuthLoginTest` | 4 | 登录成功/失败、禁用账号、返回体结构 |
| `UserRegisterTest` | 3 | 注册成功、用户名重复、参数校验 |
| `JwtSecurityTest` | 4 | JWT 签发/解析、无 token 401、游客 403 |
| `GlobalExceptionHandlerTest` | 3 | 异常到统一返回体的映射 |
| `UserAdminTest` | 18 | 用户管理全部接口 + 自我保护 + 分页夹取 + **禁用/删除/降级后的 token 即时失效** |
| `ArticleAdminTest` | 26 | 后台文章增删改查、草稿隔离、状态流转、权限、逻辑删除（用 `JdbcTemplate` 直查物理行） |
| `ArticlePublicTest` | 14 | 前台列表与详情、只返回已发布、浏览量、分页边界 |
| `YiguixingtuApplicationTests` | 4 | 冒烟：上下文加载、数据库读写、JWT 签发解析、UserDetailsService、BCrypt |

所有测试类都继承 `AbstractIntegrationTest`，它负责：
启动容器 → 把容器地址通过 `@DynamicPropertySource` 注入 Spring → 事务自动回滚。

> 📌 **容器为什么用 static 代码块启动，而不是 `@Testcontainers` + `@Container`？**
> `@Testcontainers` 扩展管理的是"每个测试类"的生命周期：每个类都重新 start/stop 一次容器。
> 而 Spring 的 ApplicationContext 是**跨测试类复用**的，数据源在第一个类时就绑到了
> 第一个容器的端口上——第二个类起来的是新容器（新端口），复用的却是老 context，
> 结果就是连一个已经被 stop 掉的容器。
> 用 static 代码块（singleton container pattern）只拉一次、全程共用，容器的回收交给
> Testcontainers 的 Ryuk（JVM 退出时自动清理），不需要手写 `@AfterAll`。

**写测试的四条约定（不是随便定的）：**

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

## 许可证

尚未指定许可证。若计划开源，建议补充一份 MIT 的 `LICENSE` 文件。
