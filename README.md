# yiguixingtu — 个人博客系统

> 基于 Spring Boot 4 + MyBatis-Plus + JWT 的个人博客后端项目（Spring Boot 4.1.1 / Java 17）

## 项目简介

**yiguixingtu** 是一个个人博客系统的后端服务，当前已完成 **用户登录认证模块**，采用 **JWT 无状态认证**，配合 **MySQL** 存储用户数据、**Redis** 做缓存与扩展。接口文档由 **springdoc** 自动生成，支持导入 **Apifox** 进行接口管理与测试。

博客核心功能（文章、分类、评论等）在规划中，将在登录/权限模块稳定后逐步实现。

## 技术栈

| 类别 | 技术 |
|------|------|
| 框架 | Spring Boot 4.1.1 |
| 语言 | Java 17 |
| 构建 | Maven |
| ORM | MyBatis-Plus 3.5.17 |
| 数据库 | MySQL 8 |
| 缓存 | Redis 7 |
| 安全 | Spring Security 7 + JWT（jjwt 0.12.6） |
| 接口文档 | springdoc-openapi 3.0.0（OpenAPI/Swagger） |
| 工具库 | Lombok、validation |

## 功能特性

### ✅ 已实现

- 用户登录（签发 JWT）
- 退出登录
- 获取当前登录用户
- JWT 无状态鉴权（请求头 `Authorization: Bearer <token>`）
- 角色支持（`ADMIN` 管理员 / `GUEST` 游客）
- 密码 BCrypt 加密存储
- 全局异常处理（统一返回 `{code, message, data}`）
- 自动生成 OpenAPI 接口文档
- 集成测试（JWT / 安全鉴权 / 登录 / 异常）

### 🚧 规划中

- 用户注册
- 文章发布 / 管理 / 编辑 / 删除
- 分类、标签
- 评论、留言
- 归档、搜索
- 后台管理面板

## 目录结构

```
com.yigalaxy.yiguixingtu
├── common
│   ├── Result                        # 统一返回包装
│   ├── ResultCode                    # 错误码枚举
│   └── exception
│       ├── BusinessException         # 自定义业务异常
│       └── GlobalExceptionHandler    # 全局异常处理器
├── config
│   ├── MybatisPlusConfig             # MyBatis-Plus 分页
│   └── SecurityConfig                # Spring Security + JWT 配置
├── auth
│   ├── controller/AuthController     # 登录/登出/当前用户接口
│   ├── service/UserDetailsServiceImpl
│   ├── util/JwtUtil                  # JWT 签发与解析
│   ├── filter/JwtAuthenticationFilter
│   ├── dto (LoginRequest, LoginVO)
│   ├── JwtProperties                 # JWT 配置
│   └── LoginUser                     # 认证用户包装
└── user
    ├── entity/User
    └── mapper/UserMapper
```

## 快速开始

### 1. 环境要求

- JDK 17+
- Maven 3.6+
- Docker（用于 MySQL / Redis）

### 2. 启动依赖（MySQL + Redis）

```bash
docker compose up -d
```

启动后：

- MySQL：`localhost:3307`（库 `yiguixingtu`，用户 `root/root`）
- Redis：`localhost:6380`

首次使用需创建 `user` 表：

```sql
CREATE TABLE IF NOT EXISTS `user` (
  `id`          bigint       NOT NULL AUTO_INCREMENT,
  `username`    varchar(64)  NOT NULL,
  `password`    varchar(100) NOT NULL,
  `nickname`    varchar(64)  DEFAULT NULL,
  `role`        varchar(20)  NOT NULL DEFAULT 'GUEST',
  `status`      tinyint      NOT NULL DEFAULT 1,
  `create_time` datetime     DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted`     tinyint      NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_username` (`username`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户表';
```

> 提示：`user` 是 MySQL 保留字，建表/查询时需注意用反引号包住表名。

### 3. 配置

#### 基础配置

| 项 | 值 |
|----|-----|
| 服务端口 | `8081` |
| MySQL | `localhost:3307`，库 `yiguixingtu` |
| Redis | `localhost:6380` |

#### 🔑 JWT 密钥配置（重要）

JWT 相关配置位于 `src/main/resources/application.properties`：

```properties
jwt.secret=${JWT_SECRET:开发用默认密钥}
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

### 4. 运行

```bash
mvn spring-boot:run
```

应用默认监听 `http://localhost:8081`。

### 5. 接口文档

- Swagger UI：`http://localhost:8081/swagger-ui.html`
- OpenAPI JSON：`http://localhost:8081/v3/api-docs`

> 可导入 **Apifox**（从 URL 导入 `http://localhost:8081/v3/api-docs`）进行接口管理与测试。

## 认证与鉴权（JWT 流程）

```
登录 → 校验用户名/密码（BCrypt） → 生成并返回 JWT
之后每次请求 → 请求头带 Authorization: Bearer <token>
        → JwtAuthenticationFilter 验签 → 写入 SecurityContext
        → 受保护接口校验通过后放行
```

- **密码**：BCrypt 单向加密存储，不保存明文
- **JWT**：无状态，服务端不存 token，凭签名自证身份
- **角色**：`ADMIN` / `GUEST`，可通过 `@PreAuthorize("hasRole('ADMIN')")` 做接口级权限

## 接口列表

| 方法 | 路径 | 说明 | 是否需要登录 |
|------|------|------|:---:|
| POST | `/auth/login` | 登录（返回 token） | 否 |
| POST | `/auth/logout` | 退出登录 | 是 |
| GET  | `/auth/me` | 获取当前用户 | 是 |
| POST | `/auth/register` | 用户注册（规划中） | 否 |

## 测试

```bash
mvn test
```

覆盖：JWT 签发/解析、安全链路鉴权、登录接口、全局异常处理。

## 许可证

[MIT](LICENSE)（按需修改）
