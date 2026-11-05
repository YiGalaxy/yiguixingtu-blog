# 亿轨星途博客

个人博客与作品展示平台，采用单仓库管理前端和后端。

## 目录

- `frontend/`：Nuxt 网站
- `backend/`：Spring Boot API
- `docs/`：产品、工程和发布文档

## 本地开发

环境要求：Node.js 22+、Java 17+、Maven 3.9+。

```powershell
cd frontend
npm install
npm run dev
```

```powershell
cd backend
mvn spring-boot:run
```

前端默认从 `http://localhost:8082` 请求 API，可用 `NUXT_PUBLIC_API_BASE` 覆盖。

## 版本约定

前后端是同一产品仓库中的两个独立业务应用，各自维护 SemVer 版本号和变更记录。提交、版本号、发布标签规则见 [版本与提交规范](docs/versioning.md)。

## 当前实施范围

第一阶段聚焦博客阅读和站长维护流程：文章检索集中到 `/articles`，首页搜索兼容旧筛选链接并跳转；首页公告只保留一个编辑入口；后台优先进入文章管理并按业务分组。普通用户注册策略等依赖存量数据的事项单独评估。
