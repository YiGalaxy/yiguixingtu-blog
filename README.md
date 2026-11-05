# 亿轨星途博客

个人博客与作品展示平台，采用单仓库管理前端和后端。

## 目录

- `frontend/`：Nuxt 网站
- `backend/`：Spring Boot API
- `docs/`：产品、工程和发布文档

## 本地开发

环境要求：Node.js 22+、Java 17+、Maven 3.9+。

生产环境请设置 `NUXT_PUBLIC_API_BASE`、`NUXT_SITE_URL`、`BLOG_ADMIN_PASSWORD` 和数据库连接变量。`NUXT_SITE_URL` 用于 RSS、站点地图与 robots.txt 的绝对链接。API 上传目录通过 `BLOG_UPLOAD_DIR` 配置，生产部署需将其挂载到持久化存储并纳入备份。

```powershell
cd frontend
npm install
npm run dev
```

```powershell
cd backend
docker compose up -d mysql
mvn spring-boot:run
```

前端默认从 `http://localhost:8082` 请求 API，可用 `NUXT_PUBLIC_API_BASE` 覆盖。
首次启动时 API 使用 Flyway 创建内容表。Swagger UI 地址为 `http://localhost:8082/swagger-ui.html`。
首次设置管理员时，将 `BLOG_ADMIN_PASSWORD` 配置为强密码后启动 API；初始管理员名默认为 `admin`，可通过 `BLOG_ADMIN_USERNAME` 修改。

## 替代旧站

新仓库已按独立实现重建主要阅读、发布和站点展示业务；没有复制旧项目工作树。公开页面包含首页、文章搜索与详情、归档、项目、关于、收藏、友链和音乐，后台覆盖文章、评论、分类标签、展示内容、设置与媒体管理。RSS、站点地图和 robots.txt 由 Nuxt 服务端生成。

接管旧站前仍需完成内容与媒体数据迁移、旧地址逐项核验、生产域名和 HTTPS 部署、数据库及上传目录备份恢复演练。新站使用独立数据库，不会自动导入旧站数据；旧站在完成迁移验收前应继续运行。

## 版本约定

前后端是同一产品仓库中的两个独立业务应用，各自维护 SemVer 版本号和变更记录。提交、版本号、发布标签规则见 [版本与提交规范](docs/versioning.md)。

## 当前实施范围

替代旧站的实施范围涵盖文章、管理员账号、评论、设置、项目、收藏、友链、音乐、上传和访问统计。文章检索集中到 `/articles`，首页搜索兼容旧筛选链接并跳转；首页公告只保留一个编辑入口；后台按业务分组。账号注册策略等依赖存量数据的事项单独评估。
