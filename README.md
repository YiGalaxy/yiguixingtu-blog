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
设置管理员时，将 `BLOG_ADMIN_PASSWORD` 配置为强密码后启动 API；管理员名默认为 `admin`，可通过 `BLOG_ADMIN_USERNAME` 修改。后台仅提供该管理员登录，不提供用户注册或用户管理。

## 替代旧站

新仓库已按独立实现重建主要阅读、发布和站点展示业务；没有复制旧项目工作树。公开页面包含首页、文章搜索与详情、项目、关于、收藏和友链，后台覆盖文章、评论、分类标签、展示内容、设置与媒体管理。RSS、站点地图和 robots.txt 由 Nuxt 服务端生成。

2026-11-05 已接管 [生产站点](https://www.yigalaxy.xin)，使用独立生产数据库 `yiguixingtu_blog_v3`。已迁移旧站内容与媒体、核验旧地址、完成 HTTPS 部署及数据库和上传目录备份恢复校验。旧库和旧容器保留用于回滚，旧应用已停止运行。详见 [生产部署验收](docs/生产部署验收.md)。

## 版本约定

前后端统一仓库管理，业务跨前后端统一记录版本，各应用维护 SemVer 和变更记录。提交、版本号、发布标签规则见 [版本与提交规范](docs/versioning.md)。

## 当前实施范围

当前已建立文章阅读与发布、管理员登录、评论审核、站点设置、项目、收藏、友链和媒体管理。音乐、归档与普通用户管理已移除。正文支持图片，文章支持附件。首页保留旧版筛选与增量加载交互，`/articles` 提供分页检索；前进、后退和刷新均从地址恢复筛选条件。后台默认进入文章管理。概览包含文章汇总及近 7/30/90 天访问趋势、PV/UV、访客构成、页面与文章排行；IP 明细仅管理员可查看，暂未接入归属地数据库。

本地预览和旧站对照结果见 [旧站对照验收](docs/旧站对照验收.md)，业务交付记录见 [业务版本记录](docs/业务版本记录.md)。

本地预览网站为 `http://localhost:3001`，API 为 `http://localhost:8083/api/v1`，使用独立数据库 `yiguixingtu_preview`。生产部署采用本地构建镜像后上传，迁移原生产数据；本地预览库不用于覆盖生产。线上管理员 `admin` 沿用原站密码，后台地址为 `https://www.yigalaxy.xin/admin`。
