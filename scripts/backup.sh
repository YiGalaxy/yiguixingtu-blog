#!/usr/bin/env bash
# =====================================================================
# 亿轨星途 · 每日备份（MySQL 全库 + 上传文件）
#
# 【为什么需要它（而不是手敲命令）】
#   这台站点的全部内容只有两处：MySQL 里的数据（文章 / 用户 / 评论 / 站点设置）
#   和具名卷 `yiguixingtu-prod_uploads_data` 里的上传文件（封面 / 音频 / 附件）。
#   两者都不在宿主机上"看得见"的位置：
#     · 数据库在 docker 具名卷里 —— 卷坏了 / 被误删 / 某次迁移写坏，没有备份就是**全站归零**
#       （阿里云快照能救一部分，但那不是"能按需回滚到昨天"）；
#     · 上传文件在另一个卷里 —— 只备份数据库的话，恢复出来是"文章都在、图片全裂"。
#   手敲一次命令没有意义，备份的价值全在"**它昨天真的跑过**"。
#
# 【这个脚本做什么（按顺序）】
#   ① 取一把锁（`flock`）：上一次还在跑就不重复起（备份慢时定时任务会叠起来）
#   ② 磁盘空间护栏：剩余空间不足就**直接失败退出** ——
#      宁可少一天备份，也不要把磁盘写满（写满的后果是全站 500，比少一份备份严重得多）
#   ③ `mysqldump` 全库 → gzip（`--single-transaction`：InnoDB 下不锁表、站点不停机）
#   ④ 上传目录打包成 tar.gz（在**运行中的 backend 容器**里打包，见下面的长注释）
#   ⑤ 校验：`gzip -t`、SQL 里有没有 `CREATE TABLE`、表数量与线上对不对得上
#   ⑥ 写一份 `.sha256`（将来能验证"这份备份没被人动过"）
#   ⑦ 清理 N 天前的旧备份
#
# 【为什么每步都写日志文件】
#   cron 不会给你看输出（服务器上没有 MTA，邮件直接丢），
#   所以"[昨天 3:30 那一次成功了吗]"这个问题的唯一答案就在 `/var/log/yiguixingtu-backup.log` 里。
#
# 【怎么用】
#   /srv/yiguixingtu/scripts/backup.sh                     # 日常备份（cron 调的就是它）
#   /srv/yiguixingtu/scripts/backup.sh --verify-restore    # 额外"真的恢复一遍"再删掉临时库
#   BACKUP_DIR=/tmp/b KEEP_DAYS=1 /srv/yiguixingtu/scripts/backup.sh   # 演练（不碰生产目录）
#
# 【关键词】
#   `set -euo pipefail`：任何一步失败立刻退出。`-o pipefail` 尤其重要 ——
#     没有它，`mysqldump | gzip` 里 mysqldump 的失败会被 gzip 的成功掩盖，
#     结果是**安静地产出一个只有半个库的备份**，这是备份脚本最危险的失败方式；
#   `flock`：内核级文件锁，进程一死锁自动释放（不会像 pid 文件那样留下假锁）；
#   `--single-transaction`：在一个一致性快照里导出；
#   `MYSQL_PWD`：密码走环境变量而不是命令行参数（命令行参数在 `ps` 里谁都看得见）。
# =====================================================================

set -euo pipefail

# ---------------------------------------------------------------- 配置
# 【全部可用环境变量覆盖】演练时把 BACKUP_DIR 指到 /tmp 就行，不用碰生产目录
COMPOSE_DIR="${COMPOSE_DIR:-/srv/yiguixingtu}"
BACKUP_DIR="${BACKUP_DIR:-/srv/backup}"
KEEP_DAYS="${KEEP_DAYS:-14}"
MIN_FREE_MB="${MIN_FREE_MB:-500}"
DB_CONTAINER="${DB_CONTAINER:-yiguixingtu-prod-mysql}"
APP_CONTAINER="${APP_CONTAINER:-yiguixingtu-prod-backend}"
DB_NAME="${DB_NAME:-yiguixingtu}"
UPLOAD_DIR_IN_CONTAINER="${UPLOAD_DIR_IN_CONTAINER:-/app/uploads}"
LOCK_FILE="${LOCK_FILE:-/var/lock/yiguixingtu-backup.lock}"

STAMP="$(date +%Y%m%d-%H%M%S)"
DB_FILE="$BACKUP_DIR/db-$STAMP.sql.gz"
FILES_FILE="$BACKUP_DIR/uploads-$STAMP.tar.gz"

VERIFY_RESTORE=0
[ "${1:-}" = "--verify-restore" ] && VERIFY_RESTORE=1

log() {
    printf '%s [backup] %s\n' "$(date '+%F %T')" "$*"
}

fail() {
    log "❌ 失败：$*"
    # 也往 syslog 丢一份：日志文件被误删时至少还有线索（`journalctl -t yiguixingtu-backup`）
    command -v logger >/dev/null 2>&1 && logger -t yiguixingtu-backup "backup failed: $*" || true
    exit 1
}

# 【为什么要这两个小函数】密码只存在于**容器自己的环境变量**里（compose 注入的），
#   所以所有的 SQL 都必须在容器内执行、由容器内的 `$MYSQL_ROOT_PASSWORD` 提供密码。
#   这里用一个 `-e SQL=...` / `-e DBNAME=...` 把参数递进去，避免在 `sh -c '...'` 里
#   拼引号 —— 那种写法（`-e "SELECT ... \"'$VAR'\""`）是这类脚本最容易出错的地方，
#   而它出错的表现是"SQL 语法错误"或更糟：**对错误的库执行了语句**。
on_db_mysql() {
    docker exec -i -e SQL="$1" "$DB_CONTAINER" sh -c \
        'export MYSQL_PWD="$MYSQL_ROOT_PASSWORD"; exec mysql -uroot -N -B -e "$SQL"'
}

# ---------------------------------------------------------------- ① 锁
exec 9>"$LOCK_FILE" || fail "打不开锁文件 $LOCK_FILE"
if ! flock -n 9; then
    log "上一次备份还在跑（锁被占着），这次跳过 —— 这不是错误"
    exit 0
fi

log "=========== 开始（stamp=$STAMP，保留 ${KEEP_DAYS} 天）==========="

# ---------------------------------------------------------------- 前置检查
[ -f "$COMPOSE_DIR/docker-compose.prod.yaml" ] || fail "找不到编排文件：$COMPOSE_DIR/docker-compose.prod.yaml"
[ -f "$COMPOSE_DIR/.env" ] || fail "找不到 .env：$COMPOSE_DIR/.env"
mkdir -p "$BACKUP_DIR"

# 容器必须在跑：否则 mysqldump 会连不上，或者读到一个正在关闭的实例
docker inspect -f '{{.State.Running}}' "$DB_CONTAINER" 2>/dev/null | grep -q true \
    || fail "数据库容器 $DB_CONTAINER 没在运行"
docker inspect -f '{{.State.Running}}' "$APP_CONTAINER" 2>/dev/null | grep -q true \
    || fail "应用容器 $APP_CONTAINER 没在运行（上传文件要从它里面打包）"

# ---------------------------------------------------------------- ② 磁盘护栏
# 【为什么看"剩余空间"而不是"备份目录大小"】目录只会越长越大，真正会出事的是磁盘写满，
#   所以在写第一个字节之前先问一次。
FREE_MB="$(df -Pm "$BACKUP_DIR" | awk 'NR==2 {print $4}')"
[ -n "$FREE_MB" ] || fail "拿不到磁盘剩余空间"
[ "$FREE_MB" -ge "$MIN_FREE_MB" ] \
    || fail "磁盘剩余 ${FREE_MB}MB < 阈值 ${MIN_FREE_MB}MB，本次不执行（先清理旧备份）"
log "磁盘剩余 ${FREE_MB}MB（阈值 ${MIN_FREE_MB}MB）"

# ---------------------------------------------------------------- ③ 备份数据库
# 【为什么写 .tmp 再 mv】中途断电/被杀会留下一个**半截文件**，而它名字是正式的、gzip 头也是对的，
#   看起来和好备份一模一样。改名是原子操作 ⇒ "目录里存在的正式备份 = 当时确实写完过"。
log "导出数据库 $DB_NAME ..."
docker exec -i -e DBNAME="$DB_NAME" "$DB_CONTAINER" sh -c \
    'export MYSQL_PWD="$MYSQL_ROOT_PASSWORD"; exec mysqldump -uroot \
        --single-transaction --routines --triggers --events \
        --default-character-set=utf8mb4 --databases "$DBNAME"' \
    | gzip -9 > "$DB_FILE.tmp" \
    || fail "mysqldump 失败（常见原因：库名写错、磁盘满、容器刚被重启）"

# 【这两条校验各自对应一种"看起来正常、其实没用"的备份】
#   · gzip -t 失败     → 文件被截断（磁盘满 / 进程被杀）
#   · 没有 CREATE TABLE → mysqldump 把错误信息写进了文件
gzip -t "$DB_FILE.tmp" || fail "备份文件解不开（多半只写了一半）"
zcat "$DB_FILE.tmp" | grep -q 'CREATE TABLE' || fail "备份里没有 CREATE TABLE，内容可疑"

# 表数量对账：备份里的建表语句条数 = 库里实际的表数（少一张就说明导出中途断过）
LIVE_TABLES="$(on_db_mysql "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='$DB_NAME'" | tr -d '\r')"
DUMP_TABLES="$(zcat "$DB_FILE.tmp" | grep -c '^CREATE TABLE' || true)"
[ -n "$LIVE_TABLES" ] || fail "读不到线上表数量，无法对账"
[ "$LIVE_TABLES" = "$DUMP_TABLES" ] \
    || fail "表数量对不上：线上 $LIVE_TABLES 张，备份里 $DUMP_TABLES 张"
log "数据库备份完成：$DUMP_TABLES 张表"

# ---------------------------------------------------------------- ④ 备份上传文件
# 【为什么在 backend 容器里打包，而不是 `docker run -v 卷:/data alpine ...`】
#   后者要额外拉一个镜像（这段原本写的就是 alpine），而**服务器拉镜像本来就时好时坏**
#   （实测镜像加速器经常失败）。一个"平时能跑、偶尔因为拉不到镜像而失败"的备份脚本，
#   等于没有备份。backend 容器里已经有 tar 与 gzip，直接用它，零额外依赖。
log "打包上传目录 $UPLOAD_DIR_IN_CONTAINER ..."
docker exec -i "$APP_CONTAINER" tar -czf - -C "$UPLOAD_DIR_IN_CONTAINER" . > "$FILES_FILE.tmp" \
    || fail "打包上传目录失败"
gzip -t "$FILES_FILE.tmp" || fail "上传文件包解不开"
# 只数文件（目录项以 / 结尾，排除掉）
UPLOAD_COUNT="$(tar -tzf "$FILES_FILE.tmp" | grep -cv '/$' || true)"
log "上传文件备份完成：$UPLOAD_COUNT 个文件"

# ---------------------------------------------------------------- ⑤⑥ 定稿 + 校验和
mv "$DB_FILE.tmp" "$DB_FILE"
mv "$FILES_FILE.tmp" "$FILES_FILE"
sha256sum "$DB_FILE" "$FILES_FILE" > "$BACKUP_DIR/backup-$STAMP.sha256"
log "定稿：$(basename "$DB_FILE")（$(du -h "$DB_FILE" | cut -f1)）、$(basename "$FILES_FILE")（$(du -h "$FILES_FILE" | cut -f1)）"

# ---------------------------------------------------------------- 可选：真恢复一遍
# 【为什么这条要单独做成开关、且不进 cron】它比日常备份重得多（真的导一遍库），
#   但它是"备份到底能不能用"的唯一硬证据 —— 定期手动跑一次，比每天多导一次划算。
if [ "$VERIFY_RESTORE" = "1" ]; then
    VERIFY_DB="${DB_NAME}_verify_$STAMP"
    log "⚠️ --verify-restore：把备份导进临时库 $VERIFY_DB 并比对行数"
    on_db_mysql "DROP DATABASE IF EXISTS \`$VERIFY_DB\`" >/dev/null
    on_db_mysql "CREATE DATABASE \`$VERIFY_DB\`" >/dev/null || fail "建临时库失败"

    # ⚠️ dump 里带 `USE \`yiguixingtu\`;`（--databases 的产物），直接喂给 mysql 会**覆盖线上库**。
    #    所以这里必须把 USE 目标改写成临时库 —— 这一步写错就等于"恢复演练把生产库清了"。
    #    用 `[^;]+` 而不是反引号做匹配：反引号在双引号里是命令替换，写起来全是转义容易出错。
    if ! zcat "$DB_FILE" | sed -E "s/^USE .*;/USE $VERIFY_DB;/" \
            | docker exec -i "$DB_CONTAINER" sh -c \
                'export MYSQL_PWD="$MYSQL_ROOT_PASSWORD"; exec mysql -uroot'; then
        on_db_mysql "DROP DATABASE IF EXISTS \`$VERIFY_DB\`" >/dev/null || true
        fail "恢复演练失败（导入临时库出错）"
    fi

    LIVE_ARTICLES="$(on_db_mysql "SELECT COUNT(*) FROM \`$DB_NAME\`.article" | tr -d '\r')"
    VERIFY_ARTICLES="$(on_db_mysql "SELECT COUNT(*) FROM \`$VERIFY_DB\`.article" | tr -d '\r')"
    on_db_mysql "DROP DATABASE \`$VERIFY_DB\`" >/dev/null
    [ "$LIVE_ARTICLES" = "$VERIFY_ARTICLES" ] \
        || fail "恢复演练行数对不上：线上 article=$LIVE_ARTICLES，恢复出来=$VERIFY_ARTICLES"
    log "✅ 恢复演练通过：article 行数 $LIVE_ARTICLES = $VERIFY_ARTICLES，临时库已删除"
fi

# ---------------------------------------------------------------- ⑦ 清理
DELETED="$(find "$BACKUP_DIR" -maxdepth 1 -type f \
    \( -name 'db-*.sql.gz' -o -name 'uploads-*.tar.gz' -o -name 'backup-*.sha256' \) \
    -mtime +"$KEEP_DAYS" -print -delete | wc -l)"
log "清理 ${KEEP_DAYS} 天前的旧备份：删除 $DELETED 个文件"
log "备份目录当前占用：$(du -sh "$BACKUP_DIR" | cut -f1)"
log "=========== 完成 ==========="
