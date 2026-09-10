# 亿轨星途 · 后端开发计划

> 基于当前代码真实状态制定（已通读全部源码）。
> 目标：把「只有登录的架子」补成「能发布内容的真博客」。

---

> ## 📌 进度说明（后续更新）
>
> **本文件是项目早期的规划文档，记录的是"当时打算怎么做"。**
> §0「现状盘点」已按**当前真实代码**重写；其余章节保留原文，
> 便于对照"当初的设想"和"实际做出来的东西"。
>
> **实际完成情况：** Phase 1（Article 模块）✅ 完成、Phase 2（Category + Tag）⚠️ 只做了 Category、
> Phase 3（Comment）❌ 未做、Phase 4（用户管理）✅ 完成、Phase 5（统计）❌ 未做、
> Phase 6（Redis 缓存）⚠️ 只做了认证信息缓存、Phase 7（上传 + 部署）❌ 未做。
> 下面各 Phase 标题后都标了真实状态。
>
> **后续的开发计划（技术选型、分阶段提交清单、验收标准）请看 `TECH_ROADMAP.md`。**
> 两份文档不冲突：本文件管**业务功能有没有做**，`TECH_ROADMAP.md` 管**工程能力和技术纵深**。

---

## 0. 现状盘点（真实情况）

### ✅ 已完成（可直接复用，不要重写）
| 能力 | 位置 |
|------|------|
| 统一返回 Result{code,message,data} + ResultCode | common/Result.java、common/ResultCode.java |
| 全局异常处理（6 类） | common/exception/GlobalExceptionHandler.java |
| 业务异常 | common/exception/BusinessException.java |
| 登录 / 注册 / 退出 / 当前用户 | auth/controller/AuthController.java |
| JWT 签发解析 / 过滤器 | auth/util/JwtUtil.java、auth/filter/JwtAuthenticationFilter.java |
| **用户认证信息 Redis 缓存 + 禁用/删除/降级后 token 即时失效** | auth/cache/UserAuthCache.java |
| Spring Security 过滤链 + 401/403 JSON + CORS | config/SecurityConfig.java |
| 角色模型（ROLE_ADMIN / ROLE_GUEST）+ 方法级权限 | auth/LoginUser.java、`@EnableMethodSecurity` |
| 用户实体 / Mapper / Service | user/ |
| **用户管理接口（分页/启禁用/改角色/重置密码/删除）** | user/controller/UserController.java |
| **文章模块（后台增删改查 + 前台列表/详情 + 草稿隔离）** | article/ |
| **分类模块（公开列表）** | category/ |
| 分页插件 + 分页夹取 + 排序白名单 | config/MybatisPlusConfig.java、article/service/ArticleServiceImpl.java |
| Swagger（springdoc） | pom.xml，Security 已放行 |
| **8 个集成测试类、76 个用例** | src/test/java/... |

### ❌ 尚未实现（留给 `TECH_ROADMAP.md`）
- 标签 tag、评论 comment —— **完全没有**
- 分类的后台增删改（只有公开的 `GET /category/list`）
- 上传接口、统计接口 —— 没有
- Redis —— 目前**只用于缓存认证信息**，还没用在文章列表/详情缓存上
- 文章列表/详情缓存、浏览量异步落库 —— 没有
- Flyway 数据库迁移、Docker 化、CI —— 没有

---

## 1. 总目标与里程碑

| 里程碑 | 内容 | 产出 |
|--------|------|------|
| **M1** | 文章模块跑通 | 能发文章、能列表分页、能看详情 |
| **M2** | 分类 + 标签 | 文章可归类、可打标签 |
| **M3** | 权限落地 | 管理端接口被 @PreAuthorize 保护，游客调返回 403 |
| **M4** | 前端接真数据 | 首页文章瀑布流来自数据库，不再是写死的 mock |
| **M5** | Redis 缓存 | 文章列表走缓存，有真实缓存逻辑可讲 |
| **M6** | 统计 + 上传 | 后台概览有真数字，封面能上传 |
| **M7** | 部署 | Docker 打包 + 线上可访问 |

---

## 2. 数据表设计（SQL）

> **实际建成情况**：`user` ✅、`category` ✅、`article` ✅ —— 三张表**已按下面设计建好并在使用**。
> `tag` / `article_tag` / `comment` / `operation_log` ❌ **尚未创建**（对应模块还没做）。
> 实际表结构与下面的设计有少量差异（列名 `top` → `is_top`、索引名等），
> **当前真实表结构以 README「初始化表结构」一节为准**。

> 规范：所有表统一带 create_time、update_time、deleted（配合 MyBatis-Plus 逻辑删除）。
> 字符集 utf8mb4，引擎 InnoDB。

```sql
-- ============ 分类 ============
CREATE TABLE `category` (
  `id`          BIGINT       NOT NULL AUTO_INCREMENT,
  `name`        VARCHAR(64)  NOT NULL COMMENT '分类名',
  `sort`        INT          DEFAULT 0 COMMENT '排序',
  `create_time` DATETIME     DEFAULT CURRENT_TIMESTAMP,
  `update_time` DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted`     TINYINT      DEFAULT 0,
  PRIMARY KEY (`id`),
  KEY `idx_name` (`name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='文章分类';

-- ============ 标签 ============
CREATE TABLE `tag` (
  `id`          BIGINT      NOT NULL AUTO_INCREMENT,
  `name`        VARCHAR(64) NOT NULL COMMENT '标签名',
  `create_time` DATETIME    DEFAULT CURRENT_TIMESTAMP,
  `update_time` DATETIME    DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted`     TINYINT     DEFAULT 0,
  PRIMARY KEY (`id`),
  KEY `idx_name` (`name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='文章标签';

-- ============ 文章 ============
CREATE TABLE `article` (
  `id`          BIGINT       NOT NULL AUTO_INCREMENT,
  `title`       VARCHAR(200) NOT NULL COMMENT '标题',
  `summary`     VARCHAR(500) DEFAULT NULL COMMENT '摘要',
  `content`     LONGTEXT     COMMENT '正文(Markdown)',
  `cover`       VARCHAR(255) DEFAULT NULL COMMENT '封面图URL',
  `category_id` BIGINT       DEFAULT NULL COMMENT '分类ID',
  `author_id`   BIGINT       DEFAULT NULL COMMENT '作者(用户ID)',
  `status`      TINYINT      DEFAULT 0 COMMENT '0草稿 1已发布',
  `view_count`  INT          DEFAULT 0 COMMENT '浏览量',
  `top`         TINYINT      DEFAULT 0 COMMENT '是否置顶',
  `create_time` DATETIME     DEFAULT CURRENT_TIMESTAMP,
  `update_time` DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted`     TINYINT      DEFAULT 0,
  PRIMARY KEY (`id`),
  KEY `idx_category`    (`category_id`),
  KEY `idx_status_time` (`status`, `create_time`),
  KEY `idx_title`       (`title`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='文章';

-- ============ 文章-标签 关联 ============
CREATE TABLE `article_tag` (
  `article_id` BIGINT NOT NULL,
  `tag_id`     BIGINT NOT NULL,
  PRIMARY KEY (`article_id`, `tag_id`),
  KEY `idx_tag` (`tag_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='文章标签关联';

-- ============ 评论 / 留言 ============
CREATE TABLE `comment` (
  `id`          BIGINT        NOT NULL AUTO_INCREMENT,
  `article_id`  BIGINT        DEFAULT NULL COMMENT '所属文章(为空则是全站留言)',
  `user_id`     BIGINT        DEFAULT NULL COMMENT '留言用户',
  `nickname`    VARCHAR(64)   DEFAULT NULL COMMENT '昵称(未登录留言用)',
  `content`     VARCHAR(1000) NOT NULL COMMENT '内容',
  `status`      TINYINT       DEFAULT 0 COMMENT '0待审核 1已通过',
  `create_time` DATETIME      DEFAULT CURRENT_TIMESTAMP,
  `deleted`     TINYINT       DEFAULT 0,
  PRIMARY KEY (`id`),
  KEY `idx_article` (`article_id`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='评论/留言';

-- ============ 操作日志（AOP 用，可选） ============
CREATE TABLE `operation_log` (
  `id`          BIGINT       NOT NULL AUTO_INCREMENT,
  `user_id`     BIGINT       DEFAULT NULL,
  `username`    VARCHAR(64)  DEFAULT NULL,
  `module`      VARCHAR(64)  DEFAULT NULL COMMENT '模块',
  `action`      VARCHAR(64)  DEFAULT NULL COMMENT '动作',
  `method`      VARCHAR(255) DEFAULT NULL,
  `params`      TEXT         DEFAULT NULL,
  `ip`          VARCHAR(64)  DEFAULT NULL,
  `cost_ms`     BIGINT       DEFAULT NULL COMMENT '耗时毫秒',
  `create_time` DATETIME     DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='操作日志';
```

> user 表已存在，不用重建。

---

## 3. 关键技术决策（先定好，少返工）

### 3.1 包结构约定
沿用现有风格：**按业务模块分包**，每个模块自带 entity/mapper/service/controller/dto。

```
com.yigalaxy.yiguixingtu
├── common/            # 已完成：Result / 异常 / 枚举
├── config/            # 已完成：Security / MyBatisPlus
├── auth/              # 已完成：登录注册
├── user/              # 已完成（后续加管理接口）
├── article/           # ★ 新增
│   ├── entity/Article.java  ArticleTag.java
│   ├── mapper/ArticleMapper.java  ArticleTagMapper.java
│   ├── service/ArticleService.java  impl/ArticleServiceImpl.java
│   ├── controller/ArticleController.java
│   └── dto/ArticleRequest.java  ArticleQuery.java  ArticleVO.java
├── category/          # ★ 新增（同 article 结构，更简单）
├── tag/               # ★ 新增
├── comment/           # ★ 新增
└── stats/             # ★ 新增：统计接口
```

> @MapperScan("com.yigalaxy.yiguixingtu.**.mapper") 和
> type-aliases-package=com.yigalaxy.yiguixingtu.**.entity 都已配好，
> 新模块放在对应包下**无需改配置**。

### 3.2 ⚠️ 权限方案（**最容易踩的坑，必读**）

你现在的 SecurityConfig 里写的是：

```java
.requestMatchers("/auth/login", "/auth/register", ...).permitAll()
.anyRequest().authenticated()   // ← 所有其他接口都要登录！
```

**这意味着**：新加的 GET /article/page（文章列表）**也会被要求登录**，
游客打开首页会直接 401。

**解决**：把「公开读接口」加进 permitAll，其余走登录校验。

```java
import org.springframework.http.HttpMethod;

.requestMatchers(
        "/auth/login", "/auth/register",
        "/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html"
).permitAll()
// 【新增】公开的只读接口：游客也能看文章/分类/标签
.requestMatchers(HttpMethod.GET,
        "/article/page", "/article/*",
        "/category/list", "/tag/list",
        "/comment/list"
).permitAll()
.anyRequest().authenticated()
```

**URL 约定**（照此命名，权限规则才清晰）：

| 类型 | 前缀 | 权限 |
|------|------|------|
| 公开读 | GET /article/page、GET /article/{id}、GET /category/list | 游客可访问 |
| 匿名写 | POST /comment（游客留言） | 游客可访问 |
| 管理写 | POST/PUT/DELETE /article/**、/category/**、/tag/**、/user/**、/upload、/stats/** | @PreAuthorize("hasRole('ADMIN')") |

### 3.3 自动填充时间字段
User 表让数据库 DEFAULT CURRENT_TIMESTAMP 兜底了。建议改成 MyBatis-Plus **自动填充**，
代码更干净、也不依赖数据库默认值：

```java
// config/MyMetaObjectHandler.java
@Component
public class MyMetaObjectHandler implements MetaObjectHandler {
    @Override
    public void insertFill(MetaObject metaObject) {
        this.strictInsertFill(metaObject, "createTime", LocalDateTime.class, LocalDateTime.now());
        this.strictInsertFill(metaObject, "updateTime", LocalDateTime.class, LocalDateTime.now());
    }
    @Override
    public void updateFill(MetaObject metaObject) {
        this.strictUpdateFill(metaObject, "updateTime", LocalDateTime.class, LocalDateTime.now());
    }
}
```
实体字段上加注解：
```java
@TableField(fill = FieldFill.INSERT)
private LocalDateTime createTime;
@TableField(fill = FieldFill.INSERT_UPDATE)
private LocalDateTime updateTime;
```

### 3.4 DTO / VO 约定
- **入参**用 xxxRequest（加 @Valid 校验）
- **出参**用 xxxVO（**绝不直接返回 entity**，避免把 password、deleted 泄露给前端）
- **查询条件**用 xxxQuery

---

## 4. 分阶段实施

### 🚩 Phase 1：Article 模块跑通（**最重要，先做这个**）—— ✅ 已完成

**目标**：能发布文章、能分页查列表、能看详情。

**新增文件**（照 User 模块的套路写）：
```
article/entity/Article.java
article/mapper/ArticleMapper.java          extends BaseMapper<Article>
article/dto/ArticleRequest.java            新增/编辑入参
article/dto/ArticleQuery.java              分页查询条件
article/dto/ArticleVO.java                 列表/详情出参
article/service/ArticleService.java
article/service/impl/ArticleServiceImpl.java
article/controller/ArticleController.java
```

**Controller 骨架**：
```java
@Tag(name = "文章")
@RestController
@RequestMapping("/article")
public class ArticleController {

    private final ArticleService articleService;
    public ArticleController(ArticleService articleService) { this.articleService = articleService; }

    /** 分页列表（公开） */
    @Operation(summary = "文章分页列表")
    @GetMapping("/page")
    public Result<IPage<ArticleVO>> page(ArticleQuery query) {
        return Result.success(articleService.pageArticle(query));
    }

    /** 详情（公开，浏览量+1） */
    @Operation(summary = "文章详情")
    @GetMapping("/{id}")
    public Result<ArticleVO> detail(@PathVariable Long id) {
        return Result.success(articleService.getDetail(id));
    }

    /** 新增（管理员） */
    @Operation(summary = "新增文章")
    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public Result<Long> create(@Valid @RequestBody ArticleRequest req) {
        return Result.success(articleService.createArticle(req));
    }

    /** 编辑（管理员） */
    @Operation(summary = "编辑文章")
    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<Void> update(@PathVariable Long id, @Valid @RequestBody ArticleRequest req) {
        articleService.updateArticle(id, req);
        return Result.success();
    }

    /** 删除（管理员，逻辑删除） */
    @Operation(summary = "删除文章")
    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<Void> remove(@PathVariable Long id) {
        articleService.removeArticle(id);
        return Result.success();
    }

    /** 发布/下架（管理员） */
    @Operation(summary = "修改文章状态")
    @PutMapping("/{id}/status")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<Void> changeStatus(@PathVariable Long id, @RequestParam Integer status) {
        articleService.changeStatus(id, status);
        return Result.success();
    }
}
```

**Service 分页核心写法**：
```java
@Override
public IPage<ArticleVO> pageArticle(ArticleQuery query) {
    Page<Article> page = new Page<>(query.getPage(), query.getSize());
    LambdaQueryWrapper<Article> w = new LambdaQueryWrapper<Article>()
            .eq(query.getStatus() != null, Article::getStatus, query.getStatus())
            .eq(query.getCategoryId() != null, Article::getCategoryId, query.getCategoryId())
            .like(StrUtil.isNotBlank(query.getKeyword()), Article::getTitle, query.getKeyword())
            .orderByDesc(Article::getTop)
            .orderByDesc(Article::getCreateTime);
    return articleMapper.selectPage(page, w).convert(this::toVO);
}
```

**验收标准**：
- [ ] GET /article/page 游客可访问，返回分页数据
- [ ] 用 ADMIN token POST /article 能建文章
- [ ] 用 GUEST token POST /article 返回 **403**
- [ ] 不带 token POST /article 返回 **401**
- [ ] GET /article/{id} 浏览量 view_count +1
- [ ] Swagger 上能看到这几组接口

---

### 🚩 Phase 2：Category + Tag（结构最简单的复制品）—— ⚠️ 只完成 Category，Tag 未做

**目标**：文章能归类、能打标签。

**新增文件**：category/、tag/ 各一套（entity/mapper/service/controller/dto）

**接口**：
| 接口 | 权限 |
|------|------|
| GET /category/list | 公开 |
| POST/PUT/DELETE /category | ADMIN |
| GET /tag/list | 公开 |
| POST/PUT/DELETE /tag | ADMIN |

**额外工作**：ArticleService.createArticle 里处理 **标签关联**
（先删 article_tag 中该文章的旧记录，再批量插入新的 tagId）。

**验收**：能给文章设分类和多标签，列表接口能按分类筛选。

---

### 🚩 Phase 3：Comment / 留言 —— ❌ 未做

**目标**：文章可评论，首页留言板有真数据。

**接口**：
| 接口 | 权限 |
|------|------|
| GET /comment/list?articleId= | 公开（只返回 status=1） |
| POST /comment | 公开（游客可留言，status 存 0 待审） |
| PUT /comment/{id}/status | ADMIN |
| DELETE /comment/{id} | ADMIN |

**验收**：游客能留言（待审）；管理员通过后前台可见。

---

### 🚩 Phase 4：用户管理接口（补 user 模块）—— ✅ 已完成（并额外做了「重置密码」「删除用户」）

**目标**：后台能管用户。

**新增**：user/controller/UserController.java、user/dto/UserVO.java

| 接口 | 权限 |
|------|------|
| GET /user/page | ADMIN |
| PUT /user/{id}/status（启用/禁用） | ADMIN |
| PUT /user/{id}/role（改角色） | ADMIN |

> ⚠️ **返回用户信息必须用 UserVO，绝不能带 password 字段。**
> LoginUser.isEnabled() 已经读了 status，所以禁用用户后他会登录失败——这条链路是通的。

**验收**：管理员能禁用某用户，该用户随后登录被拒。

---

### 🚩 Phase 5：统计接口 —— ❌ 未做

**目标**：后台概览页显示真实数字。

**新增**：stats/controller/StatsController.java、stats/dto/StatsVO.java

```java
@GetMapping("/stats/overview")
@PreAuthorize("hasRole('ADMIN')")
public Result<StatsVO> overview() {
    // articleMapper.selectCount(null) / userMapper.selectCount(null) / ...
    return Result.success(statsService.overview());
}
```

**返回**：文章数、用户数、分类数、标签数、评论数、（可选）今日新增。

**验收**：前端 admin.vue 的 4 个统计卡从写死的 0 变成真实数字。

---

### 🚩 Phase 6：Redis 缓存（**把"配了没用"变成"真用了"**）—— ⚠️ 只做了「认证信息缓存」，文章缓存未做

**目标**：文章列表走缓存，有真实缓存逻辑可讲。

**做法（推荐先用 Spring Cache 注解，简单直观）**：
1. config/RedisConfig.java：配置 RedisTemplate + JSON 序列化
2. 启动类加 @EnableCaching
3. Service 上加注解：

```java
@Cacheable(value = "article:page", key = "#query.page + ':' + #query.size + ':' + #query.categoryId")
public IPage<ArticleVO> pageArticle(ArticleQuery query) { ... }

@CacheEvict(value = "article:page", allEntries = true)   // 发文章后清缓存
public Long createArticle(ArticleRequest req) { ... }
```

4. **浏览量**用 Redis 计数（increment），定时或达到阈值再回写 MySQL，减少写库压力。

**面试要能讲的三件事**（务必准备）：
- **缓存穿透**：查不存在的数据 → 缓存空值 / 布隆过滤器
- **缓存击穿**：热点 key 过期瞬间 → 互斥锁 / 逻辑过期
- **缓存雪崩**：大量 key 同时过期 → 过期时间加随机值

**验收**：第二次请求列表明显变快；发新文章后缓存被清、列表立刻更新。

---

### 🚩 Phase 7：上传 + 部署 —— ❌ 未做

**上传**：POST /upload（ADMIN），存本地目录或 OSS，返回 url。
要点：文件类型白名单、大小限制、文件名用 UUID 防覆盖。

**部署**：
1. 写 Dockerfile（基于 eclipse-temurin:17-jre）
2. 写 docker-compose.yml（backend + mysql + redis 三个容器）
3. 云服务器上 docker compose up -d，Nginx 反代 + 域名

**验收**：用公网地址能打开博客、能发文章。

---

## 5. 阶段可选增强（有余力再做）

| 技术 | 用在哪 | 价值 |
|------|--------|------|
| **操作日志** | **Spring 事件 + `@TransactionalEventListener`** 写 operation_log 表（~~`@Log` 注解 + `@Aspect`~~ 已否决，不自研切面） | 加分，能讲事务与异步 |
| **WebSocket** | 首页"1 人正在看"实时在线人数、留言实时推送 | 有真实业务支撑 |
| **RabbitMQ** | 评论后异步发通知 | 进阶亮点 |
| **@Scheduled 定时任务** | 定时清理日志 / 自动发布草稿 | 小而清晰 |

---

## 6. 测试计划 —— ✅ 已落地（实际结果如下）

沿用你已有的 MockMvc 风格（AuthLoginTest 就是模板），每个模块补 1 个测试类。
**实际做出来的是 8 个类、76 个用例，全部通过**（当初只规划了 4 个类，实际覆盖面更宽）：

| 测试类 | 用例数 | 覆盖 | 状态 |
|--------|:---:|------|:---:|
| `AuthLoginTest` | 4 | 登录成功/失败、禁用账号、返回体结构 | ✅ |
| `UserRegisterTest` | 3 | 注册成功、用户名重复、参数校验 | ✅ |
| `JwtSecurityTest` | 4 | JWT 签发/解析、无 token 401、游客 403 | ✅ |
| `GlobalExceptionHandlerTest` | 3 | 异常到统一返回体的映射 | ✅ |
| `UserAdminTest` | 18 | 用户管理全部接口 + 自我保护 + 分页夹取 + 禁用/删除/降级后 token 即时失效 | ✅ |
| `ArticleAdminTest` | 26 | 后台文章增删改查、草稿隔离、状态流转、权限、逻辑删除（`JdbcTemplate` 直查物理行） | ✅ |
| `ArticlePublicTest` | 14 | 前台列表与详情、只返回已发布、浏览量、分页边界 | ✅ |
| `YiguixingtuApplicationTests` | 4 | 上下文加载与基础链路 | ✅ |

> 规划里提到的 `CategoryControllerTest` / `CommentControllerTest` 还没写：
> 前者是因为分类只有 1 个只读接口，只读接口的边界用例已被 `ArticlePublicTest` 的分页用例覆盖；
> 后者是因为评论模块本身还没做。

**写测试的金句**：@Transactional 让每个测试跑完自动回滚，**不污染数据库**——你已经这么用了，继续保持。
补充两条踩过坑之后定的规矩：
- **断言要落到数据库**：用 `JdbcTemplate` 直查原生 SQL 验证，不要只断言 HTTP 状态码。
  比如验证逻辑删除，必须确认**物理行还在且 `deleted = 1`**——`@TableLogic` 会过滤，用 Mapper 查不出来。
- **分页/总数类断言必须先用唯一标记圈定范围**（现有用 `ZZT + 纳秒时间戳`），
  否则库里已有的真实数据会让 `total` 断言飘忽不定。


---

## 7. 风险与注意事项

1. ⚠️ **.anyRequest().authenticated() 会挡住公开接口**——Phase 1 第一步就要改 SecurityConfig（见 3.2）。
2. ⚠️ **CORS 只放行了 http://localhost:3000**——如果前端换端口，或部署后换域名，要把新地址加进 setAllowedOriginPatterns，否则线上跨域失败。
3. ⚠️ **JWT secret 有默认值**——jwt.secret 里那个默认串是开发用的，**部署时务必用环境变量覆盖**，别把默认密钥带上线。
4. ⚠️ **返回 entity 泄露字段**——用户相关接口一律用 VO。
5. 💡 **User 实体没有 @TableField(fill=...)**——建议按 3.3 加自动填充，新建文章/分类时时间字段自动写。
6. 💡 **article_tag 关联表新增/编辑要"先删后插"**——别忘了删旧关联，否则标签会越改越多。
7. 💡 **逻辑删除的坑**：@TableLogic 后 selectCount 默认自动过滤已删数据，统计数字不会把删掉的算进去——这是想要的行为，但你要知道原理。

---

## 8. 建议执行顺序（每日清单）

**Day 1 —— 文章核心**
- [ ] 建表（5 张：category / tag / article / article_tag / comment）
- [ ] 改 SecurityConfig：加公开 GET 接口的 permitAll
- [ ] 加 MyMetaObjectHandler 自动填充
- [ ] Article entity / mapper / dto / service / controller 全套
- [ ] 跑通：列表分页 + 详情 + 新增/编辑/删除 + @PreAuthorize
- [ ] 写 ArticleControllerTest

**Day 2 —— 分类标签 + 评论**
- [ ] category、tag 两套 CRUD
- [ ] 文章保存时处理 article_tag 关联
- [ ] comment 模块（游客留言 + 管理员审核）
- [ ] 写对应测试

**Day 3 —— 用户管理 + 统计 + 联调**
- [ ] UserController（分页 / 改状态 / 改角色）+ UserVO
- [ ] /stats/overview 统计接口
- [ ] 前端：加 utils/request.ts（自动带 Authorization: Bearer、401 拦截）
- [ ] 前端首页文章瀑布流接 GET /article/page（**删掉写死的 mock**）
- [ ] 前端后台统计接 /stats/overview

**Day 4 —— Redis 缓存 + 上传**
- [ ] RedisConfig + @EnableCaching
- [ ] 文章列表 @Cacheable，发布 @CacheEvict
- [ ] 浏览量用 Redis 计数
- [ ] POST /upload 封面图上传

**Day 5 —— 部署**
- [ ] Dockerfile + docker-compose.yml
- [ ] 服务器部署 + Nginx 反代
- [ ] 环境变量覆盖 JWT secret

---

## 9. 完工后的技术栈（写简历用，届时 100% 真实）

> ⚠️ 更新：原稿里写了 **AOP**，但实际方案已经改为**不自研切面**——
> 操作日志用 Spring 事件 + `@TransactionalEventListener`，限流用 Nginx + Resilience4j，
> 都是主流现成方案（理由见 `TECH_ROADMAP.md` §20）。**写简历时不要写 AOP 操作日志。**

**后端**：Java 17 · Spring Boot 4 · MyBatis-Plus · MySQL · Redis · Spring Security + JWT · BCrypt · Spring Validation · springdoc(Swagger) · JUnit + MockMvc · Docker
**前端**：Vue 3 · Nuxt 4 · Element Plus

---

> **核心心法**：先把 **Article 一条链走通**（entity→mapper→service→controller→鉴权→测试），
> 后面的模块都是它的"复制品"，速度快很多。**别跳步，别一次改多个模块。**
