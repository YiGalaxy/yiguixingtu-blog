# 本地构建与生产发布

服务器只接收已编译的 Docker 镜像，禁止在服务器执行 npm、Maven 或 docker build。应用版本为 3.1.0，本次发布标识为 `v3.1.0-20261105`。

## 本地构建

在仓库根目录使用 PowerShell，以下每一步成功后再执行下一步：

```powershell
npm.cmd --prefix frontend ci
npm.cmd --prefix frontend run build
npm.cmd --prefix frontend test
mvn.cmd -f backend/pom.xml -DskipTests package
python deploy/stage-frontend.py
$releaseRevision = git rev-parse HEAD
docker build -f deploy/Dockerfile.frontend --build-arg REVISION=$releaseRevision -t yiguixingtu-blog-web:v3.1.0-20261105 .
docker build -f deploy/Dockerfile.backend --build-arg REVISION=$releaseRevision -t yiguixingtu-blog-api:v3.1.0-20261105 .
python deploy/package-images.py
```

`stage-frontend.py` 将 Nuxt 产物中的 Windows 目录链接实体化，避免 Linux 容器丢失多版本依赖。`output/release/` 包含镜像、校验清单和发布配置，均被 Git 忽略。后端集成测试需使用独立测试数据库，禁止使用预览或生产库运行测试。

## 上传与演练

通过已有 `ssh blog` 连接，在 `/srv/yiguixingtu-blog/releases/v3.1.0-20261105` 创建权限 700 的发布目录，上传成品并执行：

```sh
sha256sum -c SHA256SUMS
gzip -dc images.tar.gz | docker load
python3 production.py prepare /srv/yiguixingtu-blog/releases/v3.1.0-20261105
```

`prepare` 完整备份旧库、上传目录、Nginx 和运行配置，并在隔离库中真实恢复验证；随后创建独立库 `yiguixingtu_blog_v3` 和随机专用数据库密码。服务器内存有限，准备阶段不启动新容器，切换时先停旧后端再启动新服务。新端口为 3002、8084，只绑定本机。旧库和 MySQL/Redis 容器保留。生产配置权限为 600，备份目录为 700，禁止提交 Git。

迁移保留文章 ID、全文、发布时间、草稿、删除标记、阅读数及原管理员密码摘要。评论只迁移未删除记录，普通用户和音乐不迁移。媒体转入 `/srv/yiguixingtu-blog/uploads` 持久化目录，旧媒体地址保留兼容读取。访问日志迁移近 90 天仍保留的公开路由，原始日志仍在备份和旧库中保存。

## 正式切换

在本地验收生产镜像的页面、接口和分页，备份恢复检查通过后执行：

```sh
python3 production.py cutover /srv/yiguixingtu-blog/releases/v3.1.0-20261105
```

脚本先停止可能因中断而遗留的新容器，再停止旧后端以冻结写入并释放内存，制作 `backup-cutover` 并恢复验证。随后单独启动已构建的新后端镜像、等待数据库结构就绪、迁移最后快照并校验全文，最后启动新前端，经 `nginx -t` 切换 HTTPS 流量。期间 API 会短暂不可用。公网检查失败会先停新容器，再恢复原 Nginx 并启动旧容器。成功后停止旧前端，旧容器和旧库继续保留，`cutover-complete` 防止重复切换或导入覆盖生产新内容。

生产管理员沿用旧站的 `admin` 账号和原密码；本地预览密码不适用于线上。

## 回滚与后续备份

切换前备份路径为发布目录下的 `backup-initial` 和 `backup-cutover`；`database.sql.gz`、`uploads.tar.gz` 以及 `sha256.json` 可用于恢复。将备份下载到本地受保护目录，禁止提交 Git。

立即回滚可先停止新 API、备份新库和新上传目录，再启动 `yiguixingtu-prod-backend`、`yiguixingtu-prod-frontend`，将 `backup-cutover/nginx.conf` 恢复至 `/etc/nginx/sites-available/yiguixingtu.conf`，执行 `nginx -t` 和 `systemctl reload nginx`。旧库不包含切换后的新编辑，回滚前应保存并核对这些记录。新旧数据库禁止相互覆盖。

日常备份必须同时涵盖 `yiguixingtu_blog_v3`、`/srv/yiguixingtu-blog/uploads` 和受保护的生产配置。
