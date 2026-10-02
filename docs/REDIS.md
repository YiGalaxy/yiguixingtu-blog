# Redis 在两个项目里做了什么

> 这份文档回答一个问题：**Redis 在这两个仓库里具体承担了什么工作**。
> 结论先放前面：**只有后端用了 Redis** —— 13 份缓存 + 3 个版本号 + 4 类共享状态（token 黑名单、认证信息、浏览量增量、幂等占位），
> 前端一行 Redis 代码都没有 —— 但它读到的每一个热接口，背后都可能是后端从 Redis 直接返回的。
>
> 文档里的数字都标了来源：
> `【实测】`= 在**生产服务器**上读出来的（Redis 已连续运行约 2 天，`db0` 只有 9 个 key，占用 1.36 MB），
> `【代码】`= 从仓库代码里读出来的，`【库源码】`= 从 `spring-data-redis 4.1.1` 的源码里核对过的。

---

## 0. 一页速览

| # | 干什么 | 代表 key | 数据结构 | TTL | 用到的命令 |
|---|--------|----------|----------|-----|------------|
| 1 | **13 份公开接口结果的声明式缓存** | `article:page:v1:41:1:12:all:all:all:default:default` | String（JSON） | 正常 5 分钟+抖动 / 空结果 30 秒 / 统计 60 秒 | `GET` `SET PX` `DEL` `EXISTS` |
| 2 | **缓存失效用的版本号**（3 个计数器） | `article:page:version` = 41 | String（计数器） | **没有 TTL** | `INCR` `GET` |
| 3 | **登出 token 黑名单** | `auth:blacklist:{jti}` | String | = 该 token 的**剩余有效期** | `SET EX` `EXISTS` |
| 4 | **用户认证信息缓存** | `auth:user:{id}` | **Hash** | 30 分钟 | `HGETALL` `HMSET` `PEXPIRE` `DEL` |
| 5 | **浏览量计数器** | `article:view:{id}` | String（计数器） | **没有 TTL** | `INCR` `GET` `GETDEL` `KEYS` |
| 6 | **接口幂等占位** | `idem:article:create:{uuid}` | String | 处理中 60 秒 / 结果 24 小时 | `SET NX EX` `GET` `DEL` |

另外两件**不是业务而是框架**的事：

| # | 干什么 | 代表 key | 说明 |
|---|--------|----------|------|
| 7 | 缓存防击穿的**锁** | `article:detail~lock` | `spring-data-redis` 的 `lockingRedisCacheWriter` 自带：每次读缓存都会先 `EXISTS` 一下它。⚠️ 锁**没有过期时间**（框架默认），用完靠 `DEL` 释放 —— 见 3.4 与第 7 节 |
| 8 | 缓存条目的**格式版本前缀** | `article:page:` **`v1`** `:41:...` | `RedisConfig` 里写死的 `:v1:`，改结构时改成 `v2` 就等于一键作废所有老缓存 |

一句话概括三者的分工：**Redis 在后端同时扮演"缓存"（1、7、8）、"共享状态"（2、3、4、6）和"写放大缓冲"（5）三个角色**，
而这三类东西对"丢了怎么办"的容忍度完全不同 —— 这正是它最值得写清楚的地方（见第 5 节）。

---

## 1. 两个项目分别是什么，Redis 站在哪

### 1.1 后端：Redis 是唯一的共享状态

| 组件 | 作用 | Redis 的位置 |
|------|------|--------------|
| MySQL 8（`mysql:8.4`） | 唯一的事实来源：文章、分类、标签、评论、用户、设置…… | 不参与 |
| **Redis 7.4** | 缓存 + 跨请求/跨实例的共享状态 | **本文件的主角** |
| Spring Boot 4.1.1 应用 | 无状态，可以多实例部署 | 唯一使用 Redis 的进程 |

关键点：**应用本身不保存任何需要被多个实例共享的状态**。这句话听着抽象，落到代码上就是
`ArticleCacheVersion` 里那段注释说的事 —— 版本号如果放在 JVM 的内存里（`AtomicLong`），
单实例没问题，一部署两个实例就会"A 实例发文只让自己内存里的版本号 +1，B 实例还在用老 key 返回旧数据"。
**放 Redis 才能让所有实例看到同一个版本号**。项目里凡是"需要跨请求保持、且必须所有实例看到同一份"的东西，
都放在 Redis：版本号、黑名单、认证信息、浏览量增量、幂等占位。

### 1.2 前端：完全没有 Redis，而且不应该有

前端是 Nuxt 4 的 SSR 应用（`yiguixingtu-web`），它**一个 Redis 客户端都没有**：

| 检查项 | 结果 | 怎么验的 |
|--------|------|----------|
| 生产容器里有没有 `REDIS_*` 环境变量 | **没有** | `docker exec yiguixingtu-prod-frontend env \| grep -i redis` → 空 `【实测】` |
| 运行时依赖里有没有 redis 客户端 | **没有** | 容器内 `node_modules` 里没有 `ioredis` / `redis` / `@upstash` `【实测】` |
| `package-lock.json` 里的 `ioredis` / `@upstash/redis` | 是 `unstorage` / `nitropack` 的**可选**依赖，没有任何代码引用 | 全仓 `grep ioredis\|redis://` 只命中 `package-lock.json` 与一句注释 `【代码】` |
| compose 里前端服务的环境变量 | 只有 `NUXT_PUBLIC_API_BASE` / `NUXT_API_BASE_SERVER` / `NUXT_PUBLIC_SITE_URL` | `docker-compose.prod.yaml` 的 `frontend.environment` `【代码】` |

**为什么前端不需要 Redis**，三条理由：

1. **它没有自己的会话状态。** 登录态是后端签的 JWT，存在浏览器的 cookie 里；
   前端只是"把 cookie 里的 token 转发给后端"（`app/composables/useApi.ts` 会自动加 `Authorization` 头）。
   校验、撤销、失效全在后端 —— 前端没有"必须跨请求保存"的东西。
2. **它的缓存是另一套东西，跟 Redis 不在一层。** 前端关心的"缓存"是：
   SSR 把服务端取到的数据塞进 payload 让浏览器接管（`useAsyncData`）、
   HTTP 响应头（`server/routes/media/[...file].get.ts` 显式给媒体文件 `Cache-Control: no-cache`，
   Nginx 那边才给 `/media/` 打 7 天）、以及浏览器端的 `localStorage` / cookie（音量、播放模式、视频开关）。
   这些都在**客户端或 Nitro 进程内**，引入 Redis 只会多一个要运维的组件、多一处会失败的地方。
3. **它跟 Redis 的因果关系只有两条，而两条都是"前端发起、后端落 Redis"**（见 1.3）。

### 1.3 前端与 Redis 的真实因果关系（只有两处）

| 前端行为 | 前端做什么 | 后端在 Redis 里做什么 | 前端代码 |
|----------|------------|----------------------|----------|
| 点「退出登录」 | `POST /auth/logout`，带上当前 token | 把 token 的 `jti` 写进**黑名单**（`auth:blacklist:{jti}`），之后带它的请求一律 401 | `app/composables/useAuth.ts` 的 `logout()` |
| 在后台点「发布」新建文章 | 生成一个 UUID 放在 `Idempotency-Key` 请求头 | 用 `SET NX` 在 Redis 里**占位**，重复请求不再执行第二次 | `app/composables/useIdempotencyKey.ts` + `app/components/admin/ArticleEditDialog.vue` |

这两处都有同一个特点：**前端只负责"发出一件事"，真正的状态落在后端 Redis 上**。
所以前端删掉本地 token 并不等于退出（后端那个 token 还能用 24 小时，除非黑名单生效）——
`useAuth.ts` 里那段注释记的就是这个曾经的 bug。

### 1.4 前端页面看到的数据，哪些其实是 Redis 给的

这张表是"两个项目"真正的连接点：后端 13 份缓存**每一份都对应着前端的一个页面或一块外壳**，
其中 `article:detail` 就是文章详情页自己，`setting:detail` 是每一页都要读的外壳数据。

| 前端页面 / 路由 | 后端接口 | 对应缓存名 | 生产实测的 key |
|-----------------|----------|------------|----------------|
| 首页（三个数字：7 篇文章 / 50 次浏览 / 2 个分类） | `GET /article/stats` | `article:stats` | `article:stats:v1:41` `【实测】` |
| 首页 / 文章列表页 | `GET /article/page` | `article:page` | `article:page:v1:41:1:12:all:all:all:default:default`（8288 字节）`【实测】` |
| 首页筛选条 / 分类 | `GET /category/list` | `category:list` | `category:list:v1:41` `【实测】` |
| 首页 / 文章页的标签云 | `GET /tag/list` | `tag:list` | `tag:list:v1:41` `【实测】` |
| 归档页 | `GET /article/archive` | `article:archive` | 读时才有，TTL 5 分钟 |
| `/feed.xml`（Nuxt 运行时拼的 RSS） | `GET /article/rss` | `article:rss` | 同上 |
| 文章详情页 | `GET /article/{id}` | `article:detail` | `article:detail:v1:62:4`（TTL 322 秒）`【实测】` |
| 友情链接 / 项目 / 收藏 / 关于 / 音乐 | `GET /link/list`、`/project/list`、`/favorite/list`、`/about`、`/music/list` | 五个同名缓存 | `music:list:v1:12`、`setting:detail:v1:12` 等 `【实测】` |
| **每一页的外壳**（页眉站点名、页脚版权与备案号、公告、评论开关） | `GET /setting` | `setting:detail` | `setting:detail:v1:12` `【实测】` |

> 也就是说：前端首页上那三个数字 `7 / 50 / 2`，就是从
> `article:stats:v1:41` 这个 key 里读出来的（`【实测】` 里那串值正是
> `{"@class":"...ArticleStatsVO","articleCount":7,"viewCount":50,"categoryCount":2}`）。
> 前端改过一版"刷新后数字变 0"的 bug，根因在 SSR 的 hydration，不在 Redis —— 但它说明
> **前端对这份数据的依赖是硬的**：拿不到就真的没有数字可显示。

---

## 2. 后端：Redis 里的每一类 key

### 2.1 完整清单（生产 `db0` 的真实快照）

`【实测】` 抓取时刻：Redis 已运行约 2 天，`dbsize = 9`，`expires = 6`，`used_memory = 1.36 MB`，`evicted_keys = 0`。

| key | 类型 | TTL | 存什么 | 谁写 | 谁读 | 丢了会怎样 |
|-----|------|-----|--------|------|------|------------|
| `article:page:version` = **41** | String | **无**（`-1`） | 文章列表缓存的版本号 | 文章的新建/编辑/上下架/删除 | 每次拼列表缓存 key | 版本号从 0 重来 → 老缓存再也拼不出来 → 全部缓存重建（**不错，只是多查几次库**） |
| `article:detail:version` = **62** | String | **无** | 文章详情缓存的版本号 | 文章写操作 **+ 浏览量定时落库** | 每次拼详情缓存 key | 同上 |
| `content:list:version` = **12** | String | **无** | 友链/项目/收藏/关于/音乐/站点设置六个模块共用 | 这六个模块的写操作 | 同上 | 同上 |
| `article:page:v1:{版本}:{查询条件}` | String(JSON) | 300 秒 + 0~59 秒抖动 | `IPage<ArticleVO>` | `@Cacheable` 未命中时 | `@Cacheable` | 重新查库 |
| `article:detail:v1:{版本}:{id}` | String(JSON) | 同上 | `ArticleVO`（**不含**浏览量增量） | 同上（`sync = true` 的加锁路径） | 同上 | 重新查库 |
| `article:stats:v1:{版本}` | String(JSON) | 60 秒 + 0~9 秒 | `ArticleStatsVO`（首页三个数字） | 同上 | 同上 | 重新查库 |
| `article:archive:v1:{版本}`、`article:rss:v1:{版本}`、`tag:list:v1:{版本}`、`category:list:v1:{版本}` | String(JSON) | 300 秒 + 抖动 | 归档 / RSS 20 篇正文 / 标签云 / 分类 | 同上 | 同上 | 重新查库 |
| `link:list:v1:{版本}`、`project:list:v1:{版本}`、`favorite:list:v1:{版本}`、`about:detail:v1:{版本}`、`music:list:v1:{版本}`、`setting:detail:v1:{版本}` | String(JSON) | 300 秒 + 抖动 | 五个列表 + 两个单条对象 | 同上 | 同上 | 重新查库 |
| `article:view:{id}` | String(计数器) | **无** | **还没落库**的浏览量增量 | 每次打开文章详情 `INCR` | 详情接口合并展示；定时任务每 5 分钟 `GETDEL` 取走 | **这 5 分钟的浏览白记了**（静默丢失，事后查不出来） |
| `auth:user:{id}` | **Hash** | 30 分钟 | `id/username/nickname/role/status`（**没有密码**） | 认证缓存未命中时查库回填 | 每个带 token 请求的过滤器 | 回落到查库（**不会错，只是慢一点**） |
| `auth:blacklist:{jti}` | String="1" | = token 剩余有效期 | 已登出的 token 编号 | `POST /auth/logout` | 每个带 token 请求的过滤器 `EXISTS` | 该 token 提前"复活"到自然过期（最长 24 小时） |
| `idem:article:create:{uuid}` | String | 处理中 60 秒 / 结果 24 小时 | `__processing__` 或上次的响应体 | 新建文章接口 | 同上 | 极端情况下同一篇被建两次（用户自己删一篇） |
| `{缓存名}~lock`（如 `article:detail~lock`） | String（空值） | **没有过期时间** ⚠️ | 防击穿锁 | `lockingRedisCacheWriter` 抢锁 | 同上，读前 `EXISTS` | 正常路径下用完就 `DEL`；**但持锁进程被杀会永久留下**，见 3.4 与第 7 节 |

**"没有 TTL 的 key"一共四类**：三个版本号计数器 + `article:view:*`。
它们**必须**没有 TTL（版本号过期会让缓存白建、浏览量增量过期会真丢数据），
而这一点直接决定了 Redis 的内存淘汰策略只能是 `volatile-lru`（见 4.3）。

### 2.2 一次真实的文章详情请求：Redis 上到底跑了什么

下面这段是**生产环境 `MONITOR` 抓到的原始命令**（先删掉时间戳与连接信息），
请求的是 `/article/4`（那时它没有缓存）：`【实测】`

**第 1 次请求（缓存未命中，要查库）**

```
GET     article:detail:version              ← 拼缓存 key，得先取详情版本号（= 62）
EXISTS  article:detail~lock                 ← 读缓存前先看有没有别人在加载（防击穿）
GET     article:detail:v1:62:4              ← 未命中
EXISTS  article:detail~lock                 ← 准备加锁前再确认一次
SET     article:detail~lock "" NX           ← 抢到锁（只有一个人去查库）；注意没有 PX —— 这把锁不会自己过期
GET     article:detail:v1:62:4              ← 持锁后再查一次（还没有）
SET     article:detail:v1:62:4 <JSON> PX 332000   ← 查库结果写进缓存，TTL 332 秒
DEL     article:detail~lock                 ← 释放锁
INCR    article:view:4                      ← 浏览量 +1（在缓存之外！）
GET     article:view:4                      ← 取增量，和缓存里的快照相加后返回给前端
```

**第 2、3 次请求（命中缓存）**

```
GET     article:detail:version
EXISTS  article:detail~lock                 ← 命中路径没有锁，只有这一次检查
GET     article:detail:v1:62:4              ← 命中，直接返回
INCR    article:view:4                      ← 但浏览量照样 +1
GET     article:view:4
```

请求结束后 `【实测】`：`article:view:4 = 3`、详情缓存 `ttl = 322` 秒、`article:detail:version = 62`。

这段 trace 一次说明了四件事，每件都是刻意的设计：

1. **`TTL 332 秒`** = 300 秒基数 + 32 秒随机抖动 —— 防**缓存雪崩**的抖动真实生效了（第 3.3 节）。
2. **未命中时只有一个人去查库**：抢到 `article:detail~lock`（`SET NX`）的那个查库，其余在 `EXISTS` 上等着。
   这是防**缓存击穿**；测试里有一条用例用 12 个线程并发读同一篇未缓存文章，断言 `selectById` **只被调用 1 次**。
3. **`INCR` 在缓存之外**：三次请求命中缓存两次，浏览量却实实在在加了 3。
   如果 `INCR` 被挪进缓存里，命中缓存的请求就不会再计数 —— 浏览量会被"冻住"。测试里有反面用例守着这条边界。
4. **每次读缓存都多一次 `EXISTS <缓存名>~lock`**，这是 `lockingRedisCacheWriter` 的固定代价（第 3.4 节给了全量账本）。

### 2.3 缓存命中一次要花多少钱（全量账本）

`INFO commandstats` 是 Redis 自己记的"每条命令被调用了多少次"，
把它和代码里的调用点对上，就能算出每种操作的真实成本：`【实测】`（约 2 天，累计 285706 条命令）

| 命令 | 次数 | 每次耗时 | 对应代码里的什么 |
|------|------|----------|------------------|
| `GET` | 147791 | 2.74 µs | 读缓存条目 + 每次拼 key 读版本号 + 读浏览量增量 |
| `EXISTS` | 74170 | 1.00 µs | 两处：① 读缓存前检查 `<缓存名>~lock`（`MONITOR` 已证实）② 每个带 token 的请求查黑名单 `auth:blacklist:{jti}` |
| `auth` | 17703 | 5.60 µs | 各类客户端的认证（容器健康检查占了绝大多数） |
| `ping` | 17662 | 0.49 µs | 容器健康检查（每 10 秒一次） |
| `info` | 11792 | 22.15 µs | 后端 `/actuator/health` 的 Redis 健康指标（每 15 秒一次） |
| `SET` | 10404 | 6.04 µs | 写缓存值 + 抢缓存锁 + 写黑名单 |
| `DEL` | 5202 | 3.63 µs | 释放缓存锁 + 幂等失败释放 + 清认证缓存 |
| `KEYS` | 593 | 5.42 µs | **只来自一个地方**：浏览量定时落库扫 `article:view:*`（每 5 分钟一次，2 天 ≈ 576 次） |
| `HGETALL` | 281 | 5.59 µs | 读认证缓存（命中未命中都要读一次） |
| `INCR` | 130 | 4.36 µs | 浏览量 +1、推进版本号（两者无法从命令计数里分开，见下方说明） |
| `GETDEL` | 35 | 5.34 µs | 有 35 篇文章在某个 5 分钟窗口里被浏览过，增量被取走落库 |
| `HMSET` / `PEXPIRE` | 8 / 8 | — | 认证缓存只被**写入**过 8 次（其余请求都命中）—— 两个数字相等，正是"写完就设过期"这一对操作 |

> `SET : DEL ≈ 2 : 1`（10404 : 5202）是可以解释的：每次写缓存 = 抢锁 `SET ~lock` + 写值 `SET key`（两次 SET）
> + 用完 `DEL ~lock`（一次 DEL）。数字正好对上，这是"这套配置真的按预期在跑"的一个旁证。
>
> 关于 `INCR` 的分账：三个版本号的当前值合计 115（41 + 62 + 12），和 `INCR` 的 130 看起来能对上
> （剩下的约 15 次就是浏览量计数）。**但这只能算"大致吻合"，不是精确拆分**：
> 版本号的值是**计数器建立以来**的累计，而 `commandstats` 是 **Redis 上次启动以来**的累计 ——
> Redis 开了 AOF，重启不会清零计数器的值，但会把命令计数清零，两个窗口不一定相同。

---

## 3. 逐项说明：为什么这么做，代价是什么

### 3.1 十三份声明式缓存：`@Cacheable` 一句话接上 Redis

**在哪**：`src/main/java/com/yigalaxy/yiguixingtu/config/RedisConfig.java` 负责把 Spring 的缓存抽象接到 Redis 上；
业务代码里只有注解，例如 `ArticleServiceImpl.pagePublished`：

```java
@Cacheable(cacheNames = RedisConfig.CACHE_ARTICLE_PAGE,
        key = "@articleCacheVersion.current() + ':' + #query.toCacheKey()")
public IPage<ArticleVO> pagePublished(ArticleQuery query) { ... }
```

**为什么用注解而不是手写 `redisTemplate.opsForValue().get/set`**：
缓存键、序列化、TTL、失效这几件事在十几处读写里各写一遍，就必然出现"有一处忘了失效"。
交给 `@Cacheable` 之后，**缓存边界在方法签名上看得见**，而不是散在方法体里。

**key 的结构**（`【实测】` 的完整 key 就是按这个规则拼出来的）：

```
article:page : v1 : 41 : 1:12:all:all:all:default:default
└─ 缓存名 ──┘  │    │    └────── 查询条件的归一化字符串 ──────┘
            格式版本 版本号
```

- **格式版本 `v1`**：`ArticleVO` 加字段或改名时，把它改成 `v2` 就等于一键作废所有老缓存 ——
  比手动上 Redis 敲 `FLUSHDB` 安全得多，而且说得清为什么。
- **版本号**：见 3.2。
- **查询条件归一化**：`toCacheKey()` 会把 `size` 归一化成**实际生效值**（超上限截到 50、不传按默认 10），
  否则 `?size=51` / `52` / `99` 各拼一个 key，每次都未命中 —— 缓存等于白做，还顺手把 Redis 塞满垃圾 key。

**序列化为什么是 JSON，不是 JDK 序列化**：
JDK 序列化存的是二进制，用 `redis-cli` 看是一串乱码、没法排查，还要求被缓存的类都实现 `Serializable`、类结构一变旧数据就读不出来。
JSON 人眼可读（`【实测】` 里那串 `{"@class":"...ArticleStatsVO","articleCount":7,...}` 一眼就能看出是什么），跨语言跨版本都宽容。
代价是体积大一些、且 JSON 里必须带 `@class` 类型标记（否则读出来是 `LinkedHashMap`，用的时候才 `ClassCastException`）。
还有一个容易踩的点：`ArticleVO` 里有 `LocalDateTime`，Jackson **默认不知道怎么序列化它**，
必须在 `GenericJackson2JsonRedisSerializer` 上注册 `JavaTimeModule`，否则写缓存时直接抛 `InvalidDefinitionException`。

### 3.2 三个版本号：为什么不用"删缓存"来失效

**问题**：缓存了列表之后，发文/改文/删文要让缓存失效。最直观的写法是 `@CacheEvict(allEntries = true)`。

**这条路走不通，而且是踩过之后才知道的**：`spring-data-redis` 的 `RedisCache.clear()`
（`allEntries` 背后的实现）**不是同步完成的** —— 方法返回时还没删完，删除在后台继续跑。
项目里用一个"只碰缓存、不碰数据库"的探针实测过（README「文章列表缓存」一节有完整表格）：

| 操作 | 立刻查 Redis | 500 毫秒后再查 |
|------|--------------|----------------|
| `put` 一个 key | 已写入（同步） | — |
| `clear()` | **还在！** | 已删除 |
| `evict(单个 key)` | 已删除（同步） | — |

业务上的表现就是"发布文章后刷新前台，有时看得到有时看不到"，取决于那次刷新有没有赶在后台删除完成之前。
**这种偶发不一致最难排查，写测试也会变成随机红。**

**换成版本号之后**：不再删旧缓存，而是给缓存 key 加一层版本号，写操作只做一次 `INCR`。
版本号一变，之前的 key 就**再也拼不出来**了 —— 等于一瞬间全部作废，但一条数据都没删。

三个计数器，为什么是三个而不是一个：

| 计数器 | 谁推进它 | 为什么不能合成一个 |
|--------|----------|--------------------|
| `article:page:version` | 文章写操作 | 列表和详情**共用的那份**：一次文章写操作两个都 +1 |
| `article:detail:version` | 文章写操作 **+ 浏览量定时落库** | 落库会改变详情里的浏览量快照，但**不改变列表内容**；只推动必要的那个，列表缓存就不会被每 5 分钟一次的无谓失效拖累 |
| `content:list:version` | 友链/项目/收藏/关于/音乐/设置的写操作 | 这些内容和文章**毫无关系**；共用文章版本号的话，"加一条友链"会把文章列表/详情/归档/RSS/分类/标签**六份缓存**一起作废 |

> **`article:detail:version` 为什么必须单独存在**（这是全量测试抓出来的一个真 bug）：
> 详情接口返回的浏览量 = 「缓存里的库快照 + Redis 里的增量」。落库会同时做两件事 ——
> 库里的快照变大、Redis 里的增量清零。如果详情缓存里那份快照还是旧的，用户会看到
> 「旧快照 0 + 新增量 1 = 1」，而落库前明明显示 3 —— **数字当着用户的面往回跳**。
> `【实测】` 里 `detail:version = 62` 比 `page:version = 41` 大了 21。
> 只要这两个计数器是同时建立、期间没有被人为清掉过（两个 key 都从 0 开始 `INCR`），
> 这个差值就等于"浏览量落库推进详情版本号的次数"。

**版本号方案的三个附带好处**：
① 写入侧代价从 O(缓存条数) 变成 O(1)（不管缓存里有 10 条还是 10 万条，失效都只是一次 `INCR`）；
② 完全避开 `KEYS` / `SCAN` 批量匹配（`KEYS` 会阻塞 Redis 单线程）；
③ 失效不再是"注解什么时候生效"这种看不见的事，而是一次显式自增，日志和断点都能看见。

**代价说清楚**：老版本的 key 不会被主动清理，靠 TTL 自然过期 ——
短时间内 Redis 里会同时存在新老两个版本的少量 key。`【实测】` 里 `db0` 只有 9 个 key，
说明这个"堆积"实际很小；但如果哪天 TTL 被调得很长、或者版本号在短时间内被推很多次，就必须重新算这笔账。

### 3.3 防穿透 / 防雪崩 / 防击穿：三个"经典问题"各自怎么落的

| 问题 | 是什么 | 项目里的做法 |
|------|--------|--------------|
| **穿透** | 有人拿一堆**必然查不到**的条件（比如随机 keyword）反复刷接口，缓存永远不命中，请求全落到数据库 | **把"没有结果"也缓存起来**，但只给 **30 秒** TTL：30 秒足够挡掉重复的恶意查询，又不会让"刚发布的文章看不到"持续太久。TTL 由 `resolveTtl` 按内容决定 —— 空结果 30 秒 + 抖动，正常 5 分钟 + 抖动 `【代码】` |
| **雪崩** | 一批缓存在**同一时刻**被写入（比如服务重启后第一批请求），于是会在同一秒集体失效，请求瞬间全压到数据库 | 每条缓存的 TTL 加 **0~59 秒随机抖动**；统计缓存另给 60 秒 + 0~9 秒（基数才 60 秒，抖 60 秒会让"一分钟"这个承诺太虚）`【实测】`：详情缓存的 `PX` 正好是 332000 毫秒 = 300 + 32 |
| **击穿** | 热点 key 恰好过期，同时涌进来 N 个请求，它们全部发现缓存没了、全部去查库 | `@Cacheable(sync = true)` + `lockingRedisCacheWriter`：未命中时先抢一把 Redis 里的锁，只有抢到的去查库，其余等锁后读缓存 `【实测】`：`SET article:detail~lock "" NX` → 查库 → `SET 缓存值` → `DEL 锁`，就是上面那段 trace |

**为什么是"空结果也给 TTL"而不是"空结果不写缓存"**：
不写缓存 = 攻击流量每次都打到数据库；写了但不过期 = 刚发布的文章会在一段时间内依然"看不到"（比穿透更难排查）。
30 秒是这两者之间取的平衡点。

**实现上有一个必须知道的细节**：这套 TTL 逻辑用的是 `RedisCacheWriter.TtlFunction`（每次写入时现算），
而不是 `entryTtl(Duration)`（整个缓存一个固定时长）。只有前者才能做到"按内容给不同时长"。
另外 `NullValue` 包装（Spring 用来表示 null 的东西）也必须走 else 分支拿到 TTL，
否则会变成**永不过期的缓存**。

### 3.4 防击穿的锁：它到底在 Redis 上做了什么

`RedisConfig` 里用的是 `RedisCacheWriter.lockingRedisCacheWriter(connectionFactory)`。
从 `spring-data-redis 4.1.1` 的源码可以确认它的每个动作 `【库源码】`：

| 时机 | Redis 上发生什么 |
|------|------------------|
| **每次读缓存**（命中或未命中都一样） | `EXISTS <缓存名>~lock` —— 看看有没有人正在加载，有就每 **50 毫秒**轮询等它 |
| 未命中且走加锁加载路径 | `SET <缓存名>~lock "" NX`（抢锁，**注意没有 `PX`**）→ 查库 → `SET <key> <value> PX <ttl>` → `DEL <缓存名>~lock`（释放） |
| 写缓存（普通路径） | `EXISTS <缓存名>~lock` + `SET <key> <value> PX <ttl>`（**不加锁**，只等别人把锁放开） |

这里要注意三点，前两点是"看起来不起眼但量很大"，第三点是**一个真实存在的隐患**：

1. **`EXISTS <缓存名>~lock` 是每次读缓存都要付的**。`【实测】` 里 `EXISTS` 有 74170 次，
   其中相当一部分就是它（另一部分是黑名单检查）。命中率越高、这个额外开销占比越大 —— 但它只有 1 微秒量级，
   而且换来的是"热点 key 过期时只有一个请求查库"。**如果哪天缓存命中率变得很低，这笔锁开销就该重新评估**（`RedisConfig` 里写明了这个前提）。
2. **抢不到锁的请求是在 `EXISTS` 上轮询等着的**（`Thread.sleep(50ms)`），不是重复查库。
3. ⚠️ **锁 key 没有过期时间**。这一点是从框架源码和实测两处一起确认的：
   `RedisCacheWriter.lockingRedisCacheWriter(connectionFactory)` 用的是 `CacheLockingConfigurer.enable()`，
   它的 Javadoc 原话是"default sleep time of 50 milliseconds and **persistent lock keys**" `【库源码】`；
   而生产 `MONITOR` 抓到的命令正是 `SET article:detail~lock "" NX` —— **没有 `PX` 参数** `【实测】`。
   同时 `doLock` / `checkAndPotentiallyWaitUntilUnlocked` 都是**没有等待上限**的循环。

   **后果**：如果应用恰好在"抢到锁 → 查库 → 写缓存"这段中间被杀（部署重启、OOM 137、进程 kill），
   `article:detail~lock` 会**永久留下**，此后每一次文章详情请求都会卡在 `EXISTS` 轮询上等一把永远不会释放的锁 ——
   而且它会占住 Tomcat 线程（上限 50），累积下去会把整个站点拖死。
   **恢复办法**：`redis-cli DEL article:detail~lock`（现在生产里没有这个 key，说明还没踩到）。

   **一行就能修**：`RedisCacheWriter.lockingRedisCacheWriter(connectionFactory, Duration.ofMillis(50), TtlFunction.just(Duration.ofMinutes(1)), BatchStrategies.keys())`
   给锁一个 TTL，最坏情况就变成"卡一分钟"。本项目当前没配 —— 见第 7 节。

   📌 **顺带发现一处注释与事实不符**（值得单独修）：
   `article/cache/PublishedArticleCache.java` 的类注释写的是
   "那正是 LockingRedisCacheWriter 内部在做的事（它就是 SETNX + **超时释放**）……它已经处理好了**锁超时**、异常时释放、重试这些细节"。
   前半句的机制描述是对的，但"超时释放 / 锁超时"这两处**不符合 4.1.1 的实际行为** ——
   `spring-data-redis 4.x` 把锁的配置换成了 `CacheLockingConfigurer`，默认就是 persistent（无 TTL）。
   （类名 `LockingRedisCacheWriter` 也已经是 `DefaultRedisCacheWriter`，项目里另一处测试注释已经纠正过这个类名，
   但"超时释放"这个说法留了下来。）这类注释最危险的地方在于：它让人以为"锁不会漏"，于是不会去检查。

**为什么不自己写 `SETNX` + 双重检查**：抢锁、释放、抢不到时轮询等待、异常路径下 `finally` 释放这几件事，
这个写入器都替我们做了；自己写一遍只会多出几个能写错的地方，而错了的表现是"偶发多查一次库"，几乎不可能被发现。
⚠️ 但它**没有**替你处理"持锁进程死掉"这一种情况（锁没有 TTL，见上面第 3 点）——
**框架省掉的是"写对"的成本，不是"配好"的责任**：锁的 TTL 该不该给、给多久，是使用方要自己决定的。

### 3.5 登出：让"退出登录"真的生效（`auth:blacklist:{jti}`）

**问题**：JWT 是**无状态**的 —— 服务端签出去就不管了，token 在过期前一直有效（本项目 24 小时）。
好处是不用查库、天然支持多实例；代价是**撤销不掉**。于是"退出登录"就成了一个假动作：
前端把本地 token 删了，但那个 token 依然能用到自然过期 —— 要是它已经被人截获，或者浏览器历史里还留着，就还能用 24 小时。

**做法**：登出时把 token 的唯一编号（JWT 标准字段 `jti`）写进 Redis，之后带它的请求一律 401。
`AuthController.logout` 从请求头里再取一次原始 token（`SecurityContext` 里只有"用户是谁"，拿不到 `jti`），
然后按**这个 token 的剩余有效期**设置 TTL。

两个刻意的选择：

- **按 `jti` 拉黑，而不是"按用户失效"**。另一种做法是给每个用户记一个版本号、登出就 +1 ——
  那会把该用户的**所有设备**一起踢下线。个人博客的用户可能手机和电脑都登着，
  在手机上点退出却把电脑也踢了，是明显的越权行为。**要踢掉某人的全部 token 是另一种需求（管理员禁用账号），那归 `UserAuthCache` 管。**
- **TTL = 剩余有效期**，所以黑名单**不会无限增长，也不需要任何定时清理任务**。
  已经过期的 token 直接不记（它自己就无效了）；没有 `jti` 的老 token 记不了，只能让它自然过期，但会打一条日志说明。

**代价**：每个带 token 的请求多一次 `EXISTS`（`hasKey`）。这是"能撤销 token"必须付的代价，
无状态 JWT 本身做不到；要省这一次查询就只能改用"有状态 session"，那是另一个方向的取舍。

### 3.6 认证信息缓存（`auth:user:{id}`）：让"禁用/降级立刻生效"

**问题**：`JwtAuthenticationFilter` 每个请求都要确认"用户还在、没被禁用"。
token 里存的是**签发那一刻**的角色 —— 光看 token 看不出用户是否已被禁用、删除、改角色。
不校验的后果是"被禁用的人拿着旧 token 依然畅通无阻，直到 24 小时后自然过期"。

**做法**（`UserAuthCache`）：把认证需要的全部信息放进一个 **Hash**，命中时不需要查库就能构造出登录用户。

| 字段 | 说明 |
|------|------|
| `id` / `username` / `nickname` / `role` / `status` | 过滤器命中缓存时**够用的全部信息** |
| ~~`password`~~ | **不缓存**，敏感数据不进缓存 |

- **结构用 Hash 而不是序列化整个 User 对象**：这些字段本来就是"一张表"，Hash 天然对应；
  `HGETALL` 一次拿全，`HMSET` 一次写全（`【实测】`：`HMSET` 8 次 / `PEXPIRE` 8 次 —— 正好是"写完就设过期"的一对）。
- **一致性策略是"写时清除"，不是定时刷新**：禁用/启用、删除、改角色/昵称这四个写操作会主动 `DEL` 这个 key，
  所以读到的**一定是最新状态**。TTL 30 分钟只是兜底 —— 万一哪次清除漏了，最多脏 30 分钟。
- **读失败当作"未命中"**（回落查库），**写失败只打日志**：缓存出问题绝不能影响主流程。

> 这里有一个**手工改数据库时必须知道的坑**（README 里也写了）：
> 直接 `UPDATE user SET role='ADMIN'` 会绕过那套清理逻辑，缓存里还是旧角色，最长 30 分钟才自然过期。
> 改完要补一句 `docker exec -it yiguixingtu-redis redis-cli DEL auth:user:<id>`。

### 3.7 浏览量计数器（`article:view:{id}`）：把"读接口里的写操作"挪出请求链路

**问题**：原来每次打开文章详情都执行一次 `UPDATE article SET view_count = view_count + 1 WHERE id = ?`。
两个具体后果：
① **写放大** —— 详情是全站最典型的"读多写极少"接口，却是唯一会写库的读接口；
② **行锁竞争** —— 同一篇文章被并发打开时，这些 UPDATE 在同一行上排队等锁，**访问量越大越慢**。

**现在**：详情页只做一次 Redis 的 `INCR`（内存操作、无行锁），由一个定时任务每 5 分钟把累计的增量批量写回数据库。

```
打开详情：  INCR article:view:{id}        ← 微秒级，无行锁
返回数字：  库里的快照 + Redis 里的增量     ← 必须合并，否则用户会看到"刷新了但数字不动"
每 5 分钟： KEYS article:view:*  →  GETDEL 每个 key  →  一条 UPDATE 批量累加  →  推进详情版本号
```

三个关键点，每一个都有明确的理由：

1. **`GETDEL`（取值并删除）而不是"先 GET 再 DEL"**：分两步中间有窗口 ——
   刚好在这两步之间来了新访问，那个 +1 会被后面的 DEL 一起删掉，**计数凭空少一次，而且事后无法察觉**。
   一条原子命令就没有窗口。
2. **失败处理是"丢了就丢了"**：流程是"先从 Redis 取走增量 → 再写库"，写库失败那批增量就永久丢了。
   接受的代价是：浏览量是展示型数据，少几次不会造成业务错误；
   反过来做（先写库成功再删 Redis）会让同一批数字**被重复累加**，而"数字偏大"比"数字偏小"更容易被人当成作弊。
   （更严格的方案是用消息队列做可靠投递，那是另一个量级的复杂度，对浏览量的精度不值得。）
3. **落库之后必须推进 `article:detail:version`**，理由见 3.2 的那个"数字往回跳"的 bug。
   列表缓存**不用**跟着失效 —— 列表里的 `view_count` 是原样展示库里的值，落库后只是暂时偏小，等 TTL 到期自然刷新，不会出错。

**`KEYS` 的代价是知道的**：`KEYS` 会阻塞 Redis（要扫整个 keyspace）。
本项目的计数器 key 数量是"文章数"量级（几百条），而且 5 分钟才跑一次 —— `【实测】` 2 天里 `KEYS` 只出现 593 次。
**如果将来文章到十万级，应该换成 `SCAN` 游标分批扫**（代码注释里写明了这一点：现在用 KEYS 是权衡后的选择，不是不知道它的代价）。

**顺带一个"监控不要拖累生产"的决定**（`BusinessMetrics`）：
"待落库的文章数"这个指标**不是**在 Prometheus 来抓的时候去 Redis 数 key 的个数（那就要跑 `KEYS`），
而是**由定时任务在同步时把值记进内存**（`AtomicLong`），采集时只是读一个内存里的数字。
代价是它反映的是"上次同步时"的情况，滞后一个同步周期 —— 对"有没有在堆积"这个判断完全够用。
（`AtomicLong` 必须写成字段：Gauge 只持有弱引用，局部变量被 GC 掉之后指标会莫名变成 NaN。）

### 3.8 接口幂等（`idem:{scope}:{key}`）：点两下不要多出一篇文章

**问题**：在后台写完文章点「发布」，网络卡了两秒没反应，你又点了一下 ——
浏览器发出**两个一模一样**的创建请求，后端老老实实执行两次，数据库里出现两篇一模一样的文章。

**做法**（业界最主流的一种，Stripe / Square 的支付接口都是这么做的）：

```
前端：每次"提交动作"生成一个 UUID，放在请求头 Idempotency-Key 里
后端：SET idem:article:create:{uuid} __processing__ NX EX 60
        ├─ 占上了            → 新请求，正常执行
        ├─ 没占上、已存结果   → 直接返回上次的结果，不再执行第二次
        └─ 没占上、还没结果   → "另一个同样的请求正在处理中"
执行成功：SET 同一个 key = 这次的结果 EX 24 小时
执行失败：DEL 这个 key（让用户能重试）
```

四个细节，每个都是踩过或想清楚了才这么写：

| 细节 | 为什么 |
|------|--------|
| 键是**每次"提交动作"**一个，不是每次页面加载一个 | 用户点两次「发布」共用同一个键 → 第二次被正确拦掉；改了内容重新发布 → 新键，正常创建。（**前端** `useIdempotencyKey` 的语义就是这条；改成"每次点保存都生成新键"的话，点两下就是两个不同的键，后端会当成两次创建 —— 那就白做了） |
| `SET NX EX` 必须是**一条命令** | 分成"先查再写"两步的话，两个并发请求可能同时查到"不存在"然后都写进去，等于没拦 |
| 结果保留 **24 小时**，占位只保留 **60 秒** | 结果要覆盖"点了一次、隔一会儿又点一次"的时间窗（几分钟太短、永久又会无限增长）；占位对应的是"正在处理"这个几十毫秒的状态，给 24 小时的话一旦进程中途被杀，这个键会在 24 小时内一直返回"正在处理"，**用户重试永远失败且看起来毫无原因** |
| 失败必须 `DEL` 释放 | 不释放的话，这个键在 60 秒内会一直返回"正在处理" —— 用户看到一个没有任何原因、也无法自行解决的失败。而"失败就该能重试"是最基本的预期 |

**为什么 Redis 出错时选择"放行"**：幂等是一层**保护**，不是业务规则本身。
放行的代价是极端情况下可能重复创建一篇文章（用户自己删掉即可）；拒绝的代价是**用户彻底发不了文章**。
对个人博客来说前者明显更合理。
⚠️ 但代码注释里也写明了：如果是"扣款、下单"这类场景，**就该反过来选择拒绝** ——
那时"重复执行"的代价远大于"暂时不能用"。**这个选择取决于业务，没有通用答案。**

**唯一"不允许放行"的地方**是这个键的语义边界：`scope` 必须带业务名（如 `article:create`），
这样不同接口可以用同一个幂等键而不互相干扰。

---

## 4. 部署与运行时：Redis 在这台 2G 机器上是怎么配的

### 4.1 生产配置（`docker-compose.prod.yaml` 的 `redis` 服务）

| 项 | 值 | 为什么 |
|----|-----|--------|
| 镜像 | `redis:7.4`（**钉住小版本**） | Redis 里存的**全是可重建的东西**，所以"版本被悄悄换掉"的后果顶多是缓存冷启动，不像 MySQL 那样可能起不来。钉它的理由是**可复现**：部署时跑的是哪个版本应当是确定的 |
| 密码 | `--requirepass ${REDIS_PASSWORD}`，**内网也设** | 默认无密码时，只要容器网络可达就等于把缓存和业务数据公开了；能注入密码的成本几乎为零 |
| 持久化 | `--appendonly yes` + 具名卷 `redis_data:/data` | 容器重启后缓存不至于全丢。虽然丢了也能自愈，但频繁冷启动会压数据库。（`【实测】`：`aof_enabled=1`，`aof_last_bgrewrite_status=ok`） |
| 内存上限 | `mem_limit: 96m`，同时 `--maxmemory 64mb` | Redis 默认**不限内存**，这机器上必须给天花板，否则"缓存"这种东西会在内存紧张时反过来压垮系统。⚠️ `maxmemory` **必须小于** `mem_limit`，大于等于等于没有上限（Redis 会在自己还没到上限时就被 cgroup 杀掉）—— 测试里有用例盯着这个关系 |
| 淘汰策略 | `--maxmemory-policy volatile-lru` | 见 4.3，**这一条是特意选的** |
| 端口 | **一个都不发布** | 只有 compose 内网可达；`【实测】` `docker port` 输出为空 |
| 健康检查 | `redis-cli -a $$REDIS_PASSWORD ping \| grep PONG` | 设了密码之后 `ping` **必须带 `-a`**，否则收到 `NOAUTH`。踩过一次：只在 `command` 里用密码、没往容器注入 `REDIS_PASSWORD` 环境变量，健康检查里变量是空的 → **"redis 明明在跑却 unhealthy"** → backend 因依赖它而根本不启动 |

### 4.2 生产实测的运行状态

`【实测】`（约 2 天连续运行）

| 指标 | 值 |
|------|-----|
| `redis_version` / `uptime_in_days` | 7.4.11 / 2 |
| `used_memory` / `used_memory_peak` / `maxmemory` | **1.36 MB** / 1.55 MB / 64 MB |
| `maxmemory_policy` | `volatile-lru` |
| `dbsize` / `expires` | 9 / 6 |
| `evicted_keys` | **0**（从来没有因为内存不足淘汰过任何 key） |
| `expired_keys` | 5213（TTL 正常在工作） |
| `keyspace_hits` / `keyspace_misses` | 142829 / 79403 |
| 客户端连接 | **2 条** Lettuce 长连接（来自 `172.18.0.4`，即 backend 容器），库版本 `spring-data-redis 4.1.1` |
| 容器网络 | `yiguixingtu-prod_default`，四个容器（mysql / backend / frontend / redis）同网 |

> 一个诚实的小注：`mem_fragmentation_ratio = 3.48` 看着偏高，但基数只有 1.36 MB
> （RSS 约 4.7 MB）—— 在这么小的数据量下这个比值主要反映分配器的最小粒度，不是问题。

### 4.3 为什么淘汰策略必须是 `volatile-lru`

**这一条是整个 Redis 里最不能改错的配置**，因为项目在 Redis 里存了**四类"丢了就真丢数据"的 key，而它们都没有 TTL**：

| 没有 TTL 的 key | 被淘汰的后果 |
|-----------------|--------------|
| `article:view:*` | 这 5 分钟里的浏览**白记了**，而且是**静默丢失**，事后查不出来 |
| `article:page:version` / `article:detail:version` / `content:list:version` | 版本号从 0 重来，不会出错，但会让全部缓存白白重建 |

- `allkeys-lru` 会把上面这些**一起淘汰**；
- `volatile-lru` **只淘汰带 TTL 的**，而那正好是 Spring 缓存的那批条目（5 分钟 / 60 秒 / 30 秒 + 抖动）——
  它们本来就是"随时可以重新查库重建"的东西。

⇒ **"淘汰可重建的、保住不可重建的"**，这就是选它的全部理由。
`DeploymentMemoryBudgetTest` 里有一条用例专门断言 compose 里写的是 `volatile-lru`，改成 `allkeys-lru` 会直接变红。

### 4.4 本地开发与测试环境

| 环境 | Redis | 说明 |
|------|-------|------|
| 本地开发 | `docker-compose.yaml` 里只有 mysql + redis 两个容器，Redis 映射到 **`localhost:6380`**（不是 6379，避免和本机已装的 Redis 打架），浮动 tag `redis:7` | 本地要的是方便，不是可复现 |
| 集成测试 | **Testcontainers 自动拉起 `redis:7.4` 容器**，跑完自动销毁 | `AbstractIntegrationTest` 用 `GenericContainer`（官方没有专门的 redis 模块，而 Redis 只需要暴露一个端口）；宿主端口是**随机的**，所以连接信息必须用 `@DynamicPropertySource` 在容器起来之后注入 |
| CI | **不需要配置任何 Redis 服务** | 测试自己拉起容器，`clone` 下来即可验证 |

⚠️ 测试里有一条特别容易忘的规矩：**`@Transactional` 回滚不了 Redis**。
涉及缓存的用例要在 `@AfterEach` 里手动清 key —— 项目里为这件事踩过坑（"清完缓存立刻用另一条连接 `KEYS` 去查可能还看不到"，
因为缓存的写入是异步发出的；解决办法是**轮询等待**，并把这个坑写在注释里，见 `ArticleCacheTest` 的 `awaitCacheKeys`）。

---

## 5. Redis 挂了会怎样（逐项降级表）

这张表是这份文档最该记住的部分：**同一个 Redis 上放着四种容忍度完全不同的东西**，
所以"Redis 挂了怎么办"**没有统一答案，只能逐项决定**。

| 用途 | Redis 不可用时的行为 | 取向与理由 |
|------|---------------------|------------|
| 13 个缓存名的读（走 `@Cacheable`） | ⚠️ **不是"回落到查库"，而是把异常原样抛出去** —— 项目**没有配 `CacheErrorHandler`**，Spring 默认的 `SimpleCacheErrorHandler` 四个方法都是 `throw exception`（已对 `spring-context 7.0.9` 的源码逐行核对），所以表现是**读接口 500** | 这是目前最该补的一处：实现 `CachingConfigurer#errorHandler` —— get 失败记 warn 后回落查库、put/evict 失败只记 warn。**在此之前，"缓存出问题绝不影响主流程"这条原则只覆盖了下面那些手写组件，没有覆盖 `@Cacheable` 这条路径**（这份文档初版把这一行写成了"回落到查库"，是错的，已改） |
| 缓存（写） | 同上，`@Cacheable` 的 put 失败也会往外抛 | 同上 |
| 认证信息缓存 | 读失败当作"未命中" → 回落查库；写失败只打日志 | 主流程照常，只是每个请求都查库 |
| token 黑名单（读） | **宁可放行**（按"未拉黑"处理） | 真正的兜底是 token 的过期时间，攻击面被限制在"Redis 挂了的那段时间"；反过来"拒绝所有请求"等于让 Redis 抖动升级成全站不可用 |
| token 黑名单（写） | 只打日志、**登出接口照样返回成功** | 后果是"这次登出没生效"，但比"点了退出却报错、用户以为没退成功"要好，而且日志里留下了明确记录 |
| 浏览量计数 | 只打日志，**文章照常展示** | 浏览量是次要数据，不该拿它换"用户看不了文章" |
| 浏览量落库 | 跳过本次（日志 ERROR），下次再来 | 增量还在 Redis 里没被取走，不会丢 |
| 幂等占位 | **放行**（按"未处理过"处理） | 见 3.8：极端情况下可能重复建一篇，用户删掉即可；拒绝会让用户彻底发不了文章 |
| **缓存版本号推进** | **抛 `IllegalStateException` → 发文/改文失败** | **这是唯一一处"不让步"**：版本号推不上去就意味着"老缓存还在、前台会一直显示旧内容"，宁可让这次写操作失败、让用户看见错误，也不要留下一个静默的脏缓存。（取向与别处一致：**宁可失败，也不要错得没声音**） |
| 容器健康检查 | Redis unhealthy → backend 因 `depends_on: service_healthy` **不启动** | 宁可起不来，也不带着"缓存全失效 + 黑名单失效"的状态对外服务 |

> ⚠️ 上表是"Redis **不可用**"的降级；还有两件事要分开看：
> ① "Redis 可用、但里面留了一个坏 key" —— 它不在这张表里、却是目前最需要盯着的一条：**防击穿锁没有过期时间**，
> 一旦应用在持锁查库的中间被杀，那把锁会永久留下，把文章详情接口卡死。详见 3.4 与第 7 节第 1 条。
> ② 上表第 1 行是这份文档**初版写错、后来改掉**的一行：`@Cacheable` 那条路径**不会自动降级**，
> 因为项目没有实现 `CacheErrorHandler`（框架默认实现是把异常原样抛出）。这条与"锁没有 TTL"一起，
> 属于第 7 节里排在"最该修"前列的两项。

---

## 6. 怎么自己复核这份文档

### 6.1 直接问生产 Redis（只读，不改任何 key）

```bash
# 进容器（密码在 /srv/yiguixingtu/.env）
. /srv/yiguixingtu/.env
R() { docker exec yiguixingtu-prod-redis redis-cli -a "$REDIS_PASSWORD" --no-auth-warning "$@"; }

R dbsize                                   # 一共几个 key（实测 9）
R --scan --count 1000                      # 列出所有 key（**不要用 KEYS**，它在生产上是阻塞命令）
R get article:page:version                 # 41
R get article:detail:version               # 62 —— 比上面大，因为它还被浏览量落库推着
R get content:list:version                 # 12
R ttl article:detail:v1:62:4               # 详情缓存的剩余寿命（应该在 0~360 秒之间）
R info memory | grep -E "used_memory_human|maxmemory"
R info stats  | grep -E "expired_keys|evicted_keys|keyspace_hits"
R info commandstats                        # **能看到每条 Redis 命令被调用了多少次**（本文件 2.3 节的账本就是从这来的）
R config get maxmemory-policy              # volatile-lru
```

想看"一次请求到底发了哪些命令"，用 `MONITOR`（记得加 `-t`，否则 `redis-cli` 的输出被块缓冲，杀掉进程时抓不到东西）：

```bash
timeout -s INT 10 docker exec -t yiguixingtu-prod-redis \
  redis-cli -a "$REDIS_PASSWORD" --no-auth-warning monitor
# 另一个终端：curl -s http://127.0.0.1:8082/article/4 > /dev/null
```

### 6.2 仓库里哪些测试盯着这些行为

Redis 相关行为**全部有测试**，而且**测试自带 Redis 容器**（Testcontainers），不需要事先起任何服务：

| 测试类 | 盯住什么 |
|--------|----------|
| `ArticleCacheTest` | 列表缓存：第二次走缓存、四个写操作都让缓存失效、TTL 落在 300~360 秒、**空结果也缓存（防穿透）**、key 归一化 |
| `ArticleDetailCacheTest` | 详情缓存：走缓存、**浏览量不被冻住**、写操作后立刻更新、下架即 404、自愈重建、**12 线程并发只查库 1 次（防击穿）**、TTL 有效 |
| `ArticleViewCountTest` | 浏览量：`INCR` 累加不丢、定时批量落库、落库后增量清零、**落库后继续访问从新基线往上加**（就是抓到"数字往回跳"的那组用例） |
| `ArticleIdempotencyTest` | 幂等：带 `Idempotency-Key` 重复请求只建一篇、不带键时按普通请求处理、失败后可重试 |
| `LogoutTokenTest` | 登出：黑名单生效、TTL = 剩余有效期、没 `jti` 的老 token 的行为、并发/异常路径 |
| `TagTest` / `FriendLinkTest` / `FavoriteTest` / `CategoryAdminTest` / `AboutTest` / `ProjectTest` / `MusicTest` / `SiteSettingTest` | 每个内容模块的**缓存命中 + 写操作推进版本号 + key 带版本号且有 TTL** 三条 |
| `MetricsEndpointTest` | 落库指标与"待落库数量"的来源（内存里的数字，不是采集时跑 `KEYS`） |
| `DeploymentMemoryBudgetTest` | 部署契约：redis 必须是 `volatile-lru`、`maxmemory` 必须小于 `mem_limit`、数据服务镜像必须钉住小版本 |
| `ProfileProdConfigTest` | prod 配置从环境变量取 Redis 密码（不给默认值 —— 有默认值就会"带着公开凭据悄悄跑起来"） |

共 **463 个后端集成测试**跑在这套真容器上（行覆盖 91.1%），CI 上**不需要配置任何 MySQL / Redis 服务**。

---

## 7. 已知的坑与"没能验证的事"

按用户的习惯，这一节把话说清楚，不粉饰：

1. **防击穿的锁没有过期时间，残留的锁会把文章详情接口卡死**（这条是写这份文档时才发现的，值得第一个说）。
   `RedisConfig` 用的是 `RedisCacheWriter.lockingRedisCacheWriter(connectionFactory)`，
   它启用的正是框架默认的 `CacheLockingConfigurer.enable()` —— Javadoc 原话是
   "default sleep time of 50 milliseconds and **persistent lock keys**" `【库源码】`，
   而生产 `MONITOR` 抓到的命令也是 `SET article:detail~lock "" NX`（**没有 `PX`**）`【实测】`。
   同时 `doLock` / `checkAndPotentiallyWaitUntilUnlocked` 的循环**没有等待上限**。
   ⇒ 如果应用恰好在"抢到锁 → 查库 → 写缓存"这段中间被杀（部署重启、OOM 137、`docker kill`），
   `article:detail~lock` 会永久留下，此后每一次文章详情请求都会永久卡在 `EXISTS` 轮询上，
   而且每个卡住的请求都占着一个 Tomcat 线程（上限 50）—— 攒够就把整个站点拖死。
   现在生产 `db0` 里**没有**这个 key，说明还没踩到；但它目前没有任何防护，
   而这恰好是"部署时会重启后端"这个最平常的动作能触发的。
   **修法**（任选其一）：给锁配一个 TTL ——
   `RedisCacheWriter.lockingRedisCacheWriter(connectionFactory, Duration.ofMillis(50), TtlFunction.just(Duration.ofMinutes(1)), BatchStrategies.keys())`；
   或者在部署脚本启动前加一句 `DEL article:detail~lock`。
   **这次没有改**：本轮任务只是写文档，改生产代码（哪怕一行）应当单独走一次提交，并且要有能复现的验证与一条测试盯着。
   同一处还附带着一个**注释错误**：`PublishedArticleCache` 的类注释说这把锁是"超时释放"、
   框架"已经处理好了锁超时" —— 这与 4.1.1 的实际行为不符（见 3.4 末尾），应该在同一提交里一起改掉。
2. **缓存命中率指标没有做。** 项目路线图里这条一直挂着（属于 M2 的遗留），
   所以监控上看不到"缓存到底有没有用"。`【实测】` 里 `keyspace_hits / (hits + misses) = 64.3%`，
   但**这个数字不能当缓存命中率看** —— 分母里混进了"每次拼 key 都要读一次版本号"这类必然 miss 的读，
   以及 `article:view:*` 这种"查之前当然不存在"的读。
3. **`EXISTS` 的 74170 次没法进一步拆分。** 它同时来自两处（缓存锁检查 + 黑名单检查），
   而 `commandstats` 只按命令统计、不按 key 区分。本文里两处的归属是"`MONITOR` 抓到的锁检查 + 代码里的 `hasKey`"，
   **不是**从计数里量出来的比例。
4. **compose 里那句注释少算了一类 key。** `docker-compose.prod.yaml` 的 `volatile-lru` 注释写的是
   "两类没有 TTL 的 key（`article:view:*` 与两个版本号计数器）"，
   而**代码实际有四个**：`article:page:version`、`article:detail:version`、`content:list:version`、`article:view:*`。
   注释是在 `article:detail:version` 出现之前写的。结论不变（`volatile-lru` 依然是对的，
   因为要保住的正是这些没有 TTL 的 key），但注释该更新。
   （顺带一提：`~lock` 也没有 TTL，但它属于"不该被淘汰"的那一类 —— 被淘汰反而是好事。）
5. **`KEYS` 是生产上唯一的阻塞式命令。** 当前规模（几百个计数器 key、5 分钟一次）可以接受，
   文章到十万级时必须换 `SCAN`；代码注释里写了这一条，但**没有任何东西能自动提醒**。
6. **前端那一侧没有任何自动化验证。** "点退出之后旧 token 真的不能用了"这条链路跨越两个仓库：
   后端有 `LogoutTokenTest`，但前端 `logout()` 是否**真的调了** `POST /auth/logout`，
   目前只靠前端单测（断言请求头）而不是端到端验证。
7. **`@Cacheable` 这条路径没有降级（优先级与第 1 条同级）。** 项目里手写的四个组件
   （`UserAuthCache` / `TokenBlacklist` / `IdempotencyService` / `ArticleViewCounter`）都做了 try-catch，
   但 13 个 `@Cacheable` 缓存名**没有实现 `CacheErrorHandler`** —— 框架默认的 `SimpleCacheErrorHandler`
   会把异常原样抛出，所以 Redis 一抖动，走缓存的读接口**可能是 500，而不是"降级为查库"**。
   这与整套设计反复强调的"缓存出问题绝不能影响主流程"直接冲突。
   **修法**：实现 `CachingConfigurer#errorHandler`（get 失败记 warn 后按"未命中"返回、put/evict 失败只记 warn），
   并补一条"停掉 Redis，详情接口仍应返回 200（走查库）"的用例 —— 这条用例比任何文档都有说服力。
8. **这份文档里的生产数字是某一时刻的快照**（`db0` 9 个 key、版本号 41/62/12、`uptime 2 天`）——
   它们会随时间变化，作用是把"这套机制真的在跑"这件事变得可核对，而不是当成固定值。

---

## 附：术语与命令速查

| 命令 / 概念 | 在这个项目里对应 |
|-------------|------------------|
| `INCR` | 浏览量 +1；推进三个缓存版本号 |
| `GETDEL` | 定时任务把浏览量增量"取走并清零"（原子，避免丢计数） |
| `SET NX EX` | 幂等占位（`setIfAbsent`）；缓存防击穿抢锁 |
| `HGETALL` / `HMSET` | 读/写认证信息缓存（Hash） |
| `EXISTS` | ① 缓存锁检查 `<缓存名>~lock` ② token 黑名单检查 |
| `KEYS` | 只有一处：浏览量定时落库扫 `article:view:*` |
| `PEXPIRE` | 认证信息缓存的 30 分钟 TTL |
| `TTL = -1` | **没有过期时间** —— 项目里只有三个版本号与 `article:view:*` 该是这样 |
| `~lock` 后缀 | `spring-data-redis` 的 `lockingRedisCacheWriter` 的锁 key（**默认没有 TTL**，靠 `DEL` 释放） |
| `v1` 前缀段 | `RedisConfig.computePrefixWith` 里写死的**缓存格式版本** |
| `@Cacheable(sync = true)` | 防击穿；只有文章详情缓存用了它 |
