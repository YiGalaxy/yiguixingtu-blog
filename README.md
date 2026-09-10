# yiguixingtu — 个人博客系统（后端）

> 基于 Spring Boot 4 + MyBatis-Plus + JWT 的个人博客后端服务
> Spring Boot 4.1.1 / Java 17 / MySQL 8 / Redis 7

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
| 缓存 | Redis 7（当前用于缓存认证信息，见「认证与鉴权」） |
| 安全 | Spring Security 7 + JWT（jjwt 0.12.6）+ BCrypt |
| 参数校验 | Spring Validation（`spring-boot-starter-validation`） |
| 接口文档 | springdoc-openapi 3.0.0（OpenAPI / Swagger UI） |
| 运维监控 | Spring Boot Actuator |
| 工具库 | Lombok |
| 测试 | JUnit 5 + MockMvc（`spring-boot-starter-webmvc-test`） |

## 功能特性

### ✅ 已实现

**认证与用户**
- 用户注册（BCrypt 密码加密，用户名唯一）
- 用户登录（签发 JWT）、退出登录、获取当前登录用户
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
- 集成测试 8 个类 **76 个用例**

### 🚧 规划中

- 标签（tag）与文章标签关联
- 评论 / 留言与审核
- 分类的后台增删改（目前只有只读列表接口）
- 文章封面图上传（OSS）
- 文章列表 / 详情缓存、浏览量异步落库
- 站点统计接口
- 归档
- Docker 化部署与 CI

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
```

## 快速开始

### 1. 环境要求

- JDK 17+
- Maven 3.6+（也可直接用仓库自带的 `mvnw.cmd` / `mvnw`，无需本地装 Maven）
- Docker（用于启动 MySQL / Redis）

### 2. 启动依赖（MySQL + Redis）

```bash
docker compose up -d
```

启动后：

- MySQL：`localhost:3310`（库 `yiguixingtu`，用户 `root/root`）
- Redis：`localhost:6380`

### 3. 初始化表结构

> 目前还没有引入数据库迁移工具（Flyway 在计划中），首次启动前需手工建表。
> 下面三张表是**当前代码实际使用的表结构**，与数据库完全一致。

```sql
-- ============ 用户表 ============
CREATE TABLE `user` (
  `id`          bigint       NOT NULL AUTO_INCREMENT COMMENT '主键',
  `username`    varchar(64)  NOT NULL COMMENT '登录账号',
  `password`    varchar(100) NOT NULL COMMENT '密码(BCrypt哈希)',
  `nickname`    varchar(64)  DEFAULT NULL COMMENT '昵称',
  `role`        varchar(20)  NOT NULL DEFAULT 'GUEST' COMMENT '角色：ADMIN/GUEST',
  `status`      tinyint      NOT NULL DEFAULT '1' COMMENT '状态：1正常 0禁用',
  `create_time` datetime     DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted`     tinyint      NOT NULL DEFAULT '0' COMMENT '逻辑删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_username` (`username`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户表';

-- ============ 文章分类表 ============
CREATE TABLE `category` (
  `id`          bigint       NOT NULL AUTO_INCREMENT COMMENT '分类ID',
  `name`        varchar(50)  NOT NULL COMMENT '分类名称',
  `description` varchar(255) DEFAULT NULL COMMENT '分类描述',
  `sort`        int          NOT NULL DEFAULT '0' COMMENT '排序值，越小越靠前',
  `create_time` datetime     DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted`     tinyint      NOT NULL DEFAULT '0' COMMENT '逻辑删除：0未删 1已删',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_name` (`name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='文章分类表';

-- ============ 文章表 ============
CREATE TABLE `article` (
  `id`          bigint       NOT NULL AUTO_INCREMENT COMMENT '文章ID',
  `title`       varchar(200) NOT NULL COMMENT '标题',
  `summary`     varchar(500) DEFAULT NULL COMMENT '摘要（列表页展示）',
  `content`     longtext     COMMENT '正文（Markdown 源码）',
  `cover`       varchar(255) DEFAULT NULL COMMENT '封面图URL',
  `category_id` bigint       DEFAULT NULL COMMENT '分类ID',
  `status`      tinyint      NOT NULL DEFAULT '0' COMMENT '状态：0草稿 1已发布',
  `view_count`  int          NOT NULL DEFAULT '0' COMMENT '浏览量',
  `is_top`      tinyint      NOT NULL DEFAULT '0' COMMENT '是否置顶：0否 1是',
  `author_id`   bigint       DEFAULT NULL COMMENT '作者用户ID',
  `create_time` datetime     DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted`     tinyint      NOT NULL DEFAULT '0' COMMENT '逻辑删除：0未删 1已删',
  PRIMARY KEY (`id`),
  KEY `idx_status_create` (`status`, `create_time`),
  KEY `idx_category` (`category_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='文章表';
```

**两个建表细节，说明一下为什么这么设计：**

1. **`user` 是 MySQL 保留字**，建表和查询时都要用反引号包住表名（`` `user` ``）。
2. **`idx_status_create(status, create_time)` 是为首页列表专门建的复合索引**。
   前台列表的查询条件是 `status = 1 ORDER BY create_time DESC`，
   字段顺序不能反——`status` 在前才能先用等值条件把范围缩小，
   再用 `create_time` 有序取出，避免 `ORDER BY` 触发额外排序。

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

#### ⚠️ 怎么得到第一个管理员账号？

**先说结论：一个全新的空库，是进不去后台的。**

原因是一条"鸡生蛋"：
- 注册接口**只会创建 GUEST**（`UserServiceImpl` 里写死 `user.setRole("GUEST")`）
- 而把用户提升为 ADMIN 的接口 `PUT /user/{id}/role`，**本身要求调用者已经是 ADMIN**

所以刚建好的库没有任何管理员，也就没有任何办法造出一个管理员。
**这是当前版本的一个已知缺口**，修复它的提交排在 `TECH_ROADMAP.md` 的 **3.5**（管理员初始化引导）。

在 3.5 完成之前，本地起新库时的引导做法是「**先正常注册，再用 SQL 提升**」：

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

### 4. 配置

#### 基础配置

配置文件：`src/main/resources/application.properties`

| 项 | 值 |
|----|-----|
| 服务端口 | `8082` |
| MySQL | `localhost:3310`，库 `yiguixingtu`，用户 `root/root` |
| Redis | `localhost:6380` |
| 分页插件 | MyBatis-Plus `PaginationInnerInterceptor`（已注册） |
| SQL 日志 | `mybatis-plus.configuration.log-impl=StdOutImpl`（开发期打印 SQL） |

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

```bash
# 方式一：Maven
mvn spring-boot:run

# 方式二：仓库自带 wrapper（无需本地装 Maven）
./mvnw spring-boot:run
```

应用默认监听 `http://localhost:8082`。

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
这带来一个真实问题——**管理员把某个用户禁用、删除或降级成游客之后，那个人手里的旧 token 还能继续用。**

解决办法是把用户的认证信息（角色、状态）缓存进 Redis，并且**在四个写操作里主动删掉对应缓存**：
禁用/启用、改角色、重置密码、删除用户。

效果：这些操作生效后，该用户的下一次请求就会查不到缓存 → 回源数据库 →
读到新的 `status` / `role` → **旧 token 立刻被拒绝**（`UserAdminTest` 里有 3 个用例专门验证这条链路）。

> 这里选 Redis 缓存而不是"每次请求都查库"，是因为 JWT 鉴权发生在**每个请求**上，
> 查库会成为最热的 SQL；而认证信息变更频率极低，缓存命中率极高。

## 接口列表

共 **18 个接口**。「是否需要登录」一列指**访问该接口本身**的要求，
具体到角色见下方「接口 × 角色权限矩阵」。

| # | 方法 | 路径 | 说明 | 是否需要登录 |
|---|------|------|------|:---:|
| 1 | POST | `/auth/register` | 用户注册 | 否 |
| 2 | POST | `/auth/login` | 登录（返回 token） | 否 |
| 3 | POST | `/auth/logout` | 退出登录 | 是 |
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

接口文档（`/v3/api-docs`、`/swagger-ui/**`、`/swagger-ui.html`）也无需登录。

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
| `POST /auth/logout` | ❌ 401 | ✅ | ✅ |
| `GET /auth/me` | ❌ 401 | ✅ | ✅ |
| `/user/**`（全部 5 个） | ❌ 401 | ❌ 403 | ✅ |
| `/admin/article/**`（全部 6 个） | ❌ 401 | ❌ 403 | ✅ |

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
| **分页上限** | `size` 超过上限会被夹到上限（前台 ≤ 50，后台 ≤ 100），避免一次拉全表 |
| **排序白名单** | 排序字段只允许白名单内的列，防止把任意字段拼进 `ORDER BY` |
| **列表不返回正文** | 列表接口的 VO 不含 `content`（`longtext`），详情才返回 |
| **出参一律用 VO** | 用户相关接口绝不返回 `User` 实体，避免 `password` / `deleted` 泄露 |
| **双重校验** | DTO 上 `@Valid` 注解 + Service 里业务校验，不依赖单一层 |
| **逻辑删除** | `@TableLogic`，删除是 `deleted = 1`；用户名删除后被改写为 `原名#deleted#id`，释放唯一索引 |
| **自我保护** | 管理员不能禁用/删除/降级自己，防止把自己锁在系统外 |

## 数据库表

| 表 | 说明 | 关键索引 |
|----|------|---------|
| `user` | 用户 | `uk_username` 唯一 |
| `category` | 文章分类 | `uk_name` 唯一 |
| `article` | 文章（含草稿与浏览量） | `idx_status_create(status, create_time)`、`idx_category` |

## 测试

```bash
# Maven
mvn test

# 仓库自带 wrapper
./mvnw.cmd -B test
```

> ⚠️ **当前测试需要本地依赖先起来**：测试通过 `@SpringBootTest` 直连本机的
> MySQL `localhost:3310` 与 Redis `localhost:6380`，所以跑测试前必须先执行 `docker compose up -d`。
> 容器化测试环境（Testcontainers）在计划中，做完之后测试将不再依赖本地依赖。

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
| `YiguixingtuApplicationTests` | 4 | 上下文加载与基础链路 |

**写测试的三条约定（不是随便定的）：**

1. **断言要落到数据库**。用 `JdbcTemplate` 直查原生 SQL 验证，而不是只断言 HTTP 状态码。
   比如验证"删除文章是逻辑删除"，必须确认**物理行还在且 `deleted = 1`**——
   因为 `@TableLogic` 会自动过滤，用 Mapper 是查不出来的。
2. **分页/总数类断言必须先用唯一标记圈定范围**（现有用 `ZZT + 纳秒时间戳`）。
   否则库里已有的真实数据会让 `total` 断言飘忽不定，**这种测试比没有还糟**。
3. **`@Transactional` 让每个用例跑完自动回滚**，不污染数据库。
   注意它**回滚不了 Redis**，所以涉及缓存的用例要在 `@AfterEach` 里手动清 key。

## 许可证

尚未指定许可证。若计划开源，建议补充一份 MIT 的 `LICENSE` 文件。
