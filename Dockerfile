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

# 把文件属主改成 app，否则 jar 还是 root 所有
RUN chown -R app:app /app

# 切换到非 root 用户 —— 这行之后的所有指令都以 app 身份执行
USER app

EXPOSE 8082

# 【关于 JVM 参数】
#   -XX:MaxRAMPercentage=75 让 JVM 按"容器内存上限的 75%"来定堆大小。
#   为什么不写 -Xmx512m 这种固定值：容器内存限制一变（比如从 1G 提到 2G），
#   固定值不会跟着变，等于白加内存；用百分比才能自适应。
#   留 25% 给堆外（元空间、线程栈、直接内存）和系统本身。
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "/app/app.jar"]
