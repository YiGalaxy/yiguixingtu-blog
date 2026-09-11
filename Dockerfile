# =====================================================================
# 后端镜像（多阶段构建）
#
# 【为什么要多阶段】
#   单阶段也能构建，但那样最终镜像里会带上 Maven、全部依赖缓存、源码 ——
#   镜像轻松 500MB+。而运行时其实只需要一个 jar 和一个 JRE。
#   多阶段构建把"编译"和"运行"分成两个镜像：
#     构建阶段（builder）里有 Maven 和 JDK，干完活就丢掉
#     运行阶段只从 builder 里 COPY 那一个 jar 出来
#   最终镜像只有 JRE + jar，体积小、启动快、暴露面也小
#   （镜像里没有编译器、没有源码，被攻破时可利用的东西更少）。
#
# 【构建命令】
#   docker build -t yiguixingtu-backend .
#   或者直接用 docker-compose.prod.yaml（推荐，见那里的说明）
# =====================================================================


# ---------------------------------------------------------------------
# 阶段一：构建
#
# 用 maven + temurin 17 的官方镜像，它已经是"JDK + Maven"的组合，
# 不用自己装。固定 JDK 17 是为了和 pom.xml 里的 <java.version>17</java.version> 一致 ——
# 本地、CI、镜像三处编译用的 JDK 必须相同，否则会出现
# "本地能跑、镜像里编译不过"这种最难查的问题。
# ---------------------------------------------------------------------
FROM maven:3.9-eclipse-temurin-17 AS builder

WORKDIR /build

# 【默认走阿里云 Maven 镜像 —— 这不是"优化"，是能否构建成功的问题】
#   ⚠️ 实测（本机，国内网络）：直接连 Maven Central 时，`dependency:go-offline`
#   这一步跑了 25 分钟还没结束（在容器里，没有任何进度输出，看起来像卡死）。
#   而这个构建在【用户部署的阿里云 ECS 上也要跑一次】—— 也就是说，
#   如果不管它，第一次 `docker compose up -d --build` 可能要等很久甚至超时失败，
#   而失败信息只会是一句"构建失败"，看不出是网络慢。
#
#   做法：写一个最小的 settings.xml，把中央仓库指到阿里云公共代理。
#   想换别的镜像 / 想关掉它：构建时传 --build-arg MAVEN_MIRROR_URL=...
#   （传空值表示不加 mirror 节点，回到直连 Maven Central）。
#
#   【为什么不是"换个网络"】镜像构建发生在服务器上，
#   那是我们控制不了的环境；把仓库地址写进构建产物才是可复现的。
ARG MAVEN_MIRROR_URL=https://maven.aliyun.com/repository/public
RUN mkdir -p /root/.m2 && printf '%s\n' \
      '<settings xmlns="http://maven.apache.org/SETTINGS/1.0.0">' \
      '  <mirrors>' \
      '    <mirror>' \
      '      <id>build-mirror</id>' \
      '      <name>build-time mirror</name>' \
      "      <url>${MAVEN_MIRROR_URL}</url>" \
      '      <mirrorOf>central</mirrorOf>' \
      '    </mirror>' \
      '  </mirrors>' \
      '</settings>' > /root/.m2/settings.xml

# 【为什么先只拷 pom.xml、单独下载依赖，再拷源码】
#   Docker 构建是分层的，某一层的输入没变就直接用缓存。
#   如果一上来就 `COPY . .`，那么【改任何一行代码】都会让
#   "下载依赖"这一层缓存失效，每次都重新拉几百 MB 依赖。
#   拆成两步之后：只要 pom.xml 没动，改代码就不会触发重新下载依赖，
#   日常构建从几分钟降到几十秒。
COPY pom.xml .
RUN mvn -B -q dependency:go-offline

# 依赖就绪后再拷源码并打包
COPY src ./src

# -DskipTests 的理由：单元/集成测试用的是 Testcontainers，
# 它需要在构建过程中起 Docker 容器。而容器里再起容器（DinD）在大多数
# CI/构建环境里既慢又需要额外配置，不该塞进镜像构建这一步。
# 测试由 CI 负责跑（.github/workflows/ci.yml，76+ 个用例），
# 本地提交前也会跑 —— 所以这里跳过是【有替代保障】的，不是把测试绕过去。
RUN mvn -B clean package -DskipTests


# ---------------------------------------------------------------------
# 阶段二：运行
#
# 只装 JRE 不装 JDK：跑一个 jar 不需要编译器。
# ---------------------------------------------------------------------
FROM eclipse-temurin:17-jre

# 【装 curl 是为了给容器健康检查用】
#   compose 里用 curl 打 /actuator/health 判断"应用真的能干活了"，
#   而不是只看端口有没有开。--no-install-recommends 避免拖进一堆无关包，
#   装完立刻删掉 apt 缓存，不然这一层会白白大几十 MB。
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

# 【为什么用非 root 用户运行】
#   Docker 容器默认以 root 运行，一旦应用被攻破，攻击者拿到的是容器内的 root；
#   配合内核漏洞或挂载配置不当，就可能进一步影响宿主机。
#   创建一个没有任何特权的普通用户再切过去，是"纵深防御"里成本最低的一条。
#   -r 表示系统用户（不建家目录、不设密码），够用了。
RUN groupadd -r app && useradd -r -g app app

WORKDIR /app

# 从构建阶段把 jar 拿出来，并改名为固定的 app.jar
# （不写死版本号，pom 里改了版本这里也不用跟着改）
COPY --from=builder /build/target/*.jar app.jar

# 【上传目录 + 日志目录：都必须在这里先建出来，而且属主必须是 app】
#   封面图存在服务器磁盘上，目录由 app.upload.local-dir 指定，
#   compose 里挂的是 /app/uploads；
#   日志目录由 LOG_DIR 指定（见 logback-spring.xml），compose 里挂的是 /app/logs。
#
#   ⚠️ 为什么非要在镜像里 mkdir 一次（明明代码里 Files.createDirectories 会自己建）
#     因为 compose 挂的是【具名卷】：卷第一次被使用时，Docker 会把镜像里
#     那个挂载点上【已有的内容连同属主/权限】一起复制进新卷。
#     如果镜像里没有这个目录，新卷会被建成 root:root ——
#     而我们的进程是以非 root 的 app 用户跑的：
#       · 上传目录没建 → 第一次上传就 "Permission denied"（用户点了上传才报错）
#       · 日志目录没建 → logback 连日志文件都建不出来，而这件事【不会有任何提示】，
#         表现只是"docker logs 里有、但日志文件里什么都没有"
#     先 mkdir 再 chown，新卷一出生就是 app 能写的。
#   （chown 顺手把整个 /app 一起改了，jar 的属主也一起修正）
RUN mkdir -p /app/uploads /app/logs && chown -R app:app /app

# 切换到非 root 用户 —— 这行之后的所有指令都以 app 身份执行
USER app

EXPOSE 8082

# =====================================================================
# 【关于 JVM 参数：这里【故意】一个都不写，它们全在 compose 里】
#
#   这里曾经写的是 `-XX:MaxRAMPercentage=75.0`。挪走的原因不是它错了，
#   而是它和 docker-compose.prod.yaml 里的 mem_limit 是【同一个决定的两半】：
#     · "堆按容器内存上限的百分比算" —— 百分比写在这里
#     · "容器内存上限是多少"       —— mem_limit 写在 compose 里
#   两半分居两个文件，改了一处很容易忘了另一处（把 mem_limit 从 768m 调到
#   1.5g 的人，未必会想到还有一份堆参数在 Dockerfile 里等着他）。
#
#   ⚠️ 更要紧的是【两处都写会静默失效】：JAVA_TOOL_OPTIONS 是 JVM 在解析
#   命令行【之前】读入的，命令行上的同名 -XX 参数会覆盖它 ——
#   也就是说 Dockerfile 里这个 75.0 会赢、compose 里写的值被悄悄忽略，
#   而表现只是"我在 compose 里把堆调小了，怎么没生效"。
#   ⇒ 所以口径定成：**镜像保持中立，所有随部署环境而变的旋钮集中在一处**
#     （compose 的 JAVA_TOOL_OPTIONS，紧挨着 mem_limit）。
#     这个镜像不带任何堆参数，JVM 默认 MaxRAMPercentage=25 ——
#     即便有人直接 `docker run` 它，拿到的也是一个保守的小堆，不会失控。
# =====================================================================
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
