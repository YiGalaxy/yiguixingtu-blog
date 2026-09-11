package com.yigalaxy.yiguixingtu;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * =====================================================================
 * 部署配置契约：内存预算与 JVM 参数的位置
 *
 * 【为什么需要这个测试 —— 它守的是"漏了不会报错"的那一类问题】
 *   这个项目里绝大多数错误都会以异常、日志、或者测试失败的形式自己冒出来。
 *   但"部署配置"这一族恰好相反，它们的失败方式是【静默】的：
 *     · 给容器忘了写 mem_limit → 容器没有限额，JVM 按【宿主机】内存算堆，
 *       2G 的机器上最后被内核 OOM killer 杀掉的是 MySQL ——
 *       症状是"数据库莫名重启"，应用日志里一个字都没有（要看 dmesg）
 *     · 在 Dockerfile 的 ENTRYPOINT 里又写了一份 JVM 参数 →
 *       JAVA_TOOL_OPTIONS 被命令行上的同名参数静默覆盖，
 *       症状是"我在 compose 里调了堆，怎么没生效"
 *     · compose 里出现重复的 key（比如插一段配置时手滑把上面某行又抄了一遍）→
 *       Docker 的解析器会直接拒绝整个文件，而那时你正在服务器上部署
 *   ⇒ 这几件事都有"上线当天才发现"的特征，所以用测试把它们钉在这里，
 *     让它们在【本地跑测试】的时候就失败，并且失败信息直接写明怎么改。
 *
 * 【它为什么是"读文件"而不是"启一个 Spring 上下文"】
 *   被测对象不是 Java 代码，而是 docker-compose.prod.yaml 与 Dockerfile 这两个
 *   文本文件。把它解析出来断言，比"启动应用看行为"直接得多，也快得多 ——
 *   所以这个类【不继承 AbstractIntegrationTest】，它不需要数据库和 Redis。
 *   （同类做法见 ProfileProdConfigTest 里"读配置文件原文"的那条用例。）
 *
 * 【⚠️ 这里的数字是"2 核 2G 这台服务器"的决定，不是通用真理】
 *   换机器时这些断言会失败，那正是它的作用：逼着人重新算一遍预算，
 *   改完 compose 再回来改这里的常量，而不是让一份过期预算继续躺在文档里。
 * =====================================================================
 */
class DeploymentMemoryBudgetTest {

    /** 宿主机内存（MB）—— 目标服务器是"2 核 2G"，见 README「内存预算」那一节 */
    private static final long HOST_MEMORY_MB = 2048;

    /**
     * 必须留给宿主机的余量（MB）。
     * 操作系统、Docker 守护进程、Nginx、sshd 都要内存，而它们不在 compose 里
     * （不在 compose 里的东西最难被想起来，所以留白要用断言守住）。
     */
    private static final long HOST_RESERVE_MB = 300;

    /** 堆占容器上限的比例上限（%）。留出的部分给元空间 / 线程栈 / 直接内存 / JIT 代码缓存 / GC 结构 */
    private static final double MAX_HEAP_PERCENT = 70.0;

    /** 部署形态里的四个服务。写成清单是为了"少了一个/多了一个"都能被看出来 */
    private static final List<String> EXPECTED_SERVICES =
            List.of("mysql", "redis", "backend", "frontend");

    /** 项目根目录（Surefire 的工作目录就是它，所以这两个文件用相对路径就能找到） */
    private static final Path PROJECT_ROOT = Path.of(System.getProperty("user.dir"));

    private static final Path COMPOSE_FILE = PROJECT_ROOT.resolve("docker-compose.prod.yaml");

    private static final Path DOCKERFILE = PROJECT_ROOT.resolve("Dockerfile");

    /** 解析后的 compose 文档（整个文件只读一次，几条用例共用） */
    private static Map<String, Object> compose;

    @BeforeAll
    static void loadCompose() {
        // ⚠️ setAllowDuplicateKeys(false) 是这里最容易漏掉、也最有价值的一项：
        //    SnakeYAML 默认【允许】重复 key（后面那个覆盖前面那个，静默），
        //    而 Docker 自己的解析器会因此拒绝整个文件。
        //    打开这个开关之后，"手滑抄重了一行"会在本地就炸出来。
        //    —— 这条不是假想：给 compose 插入内存配置时就真的重复了一个 LOG_DIR。
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        compose = new Yaml(options).load(readFile(COMPOSE_FILE));
        assertNotNull(compose, "docker-compose.prod.yaml 解析出来是空的");
    }

    // =================================================================
    //  一、内存上限本身
    // =================================================================

    @Test
    @DisplayName("生产编排：四个服务都必须有 mem_limit（没有限额的容器会按【宿主机】内存算堆）")
    void everyServiceMustDeclareMemoryLimit() {
        Map<String, Object> services = services();

        for (String name : EXPECTED_SERVICES) {
            assertTrue(services.containsKey(name),
                    "compose 里找不到服务 " + name + "，部署形态可能被改过了；"
                            + "确认之后请同时更新本测试的 EXPECTED_SERVICES 与 README 的内存预算表");
        }

        // 逐个服务检查，而且是对【实际存在的所有服务】检查 ——
        // 将来加了第五个服务，这条断言会自动把它也管上（不必记得改测试）
        for (String name : services.keySet()) {
            Map<String, Object> service = service(name);
            assertTrue(service.containsKey("mem_limit"),
                    "服务 " + name + " 没有配 mem_limit。"
                            + "不设上限时容器没有 cgroup 限额，JVM 的 MaxRAMPercentage 会按【宿主机】内存算堆，"
                            + "2G 的机器上会让内核 OOM killer 来收场（被杀掉的通常是 MySQL）");
        }
    }

    @Test
    @DisplayName("生产编排：容器上限之和必须给宿主机留出余量（不许顶满物理内存）")
    void totalMemoryLimitMustLeaveRoomForHost() {
        long total = 0;
        StringBuilder detail = new StringBuilder();

        for (String name : services().keySet()) {
            long mb = memoryMb(service(name).get("mem_limit"));
            total += mb;
            detail.append(name).append('=').append(mb).append("MB ");
        }

        long budget = HOST_MEMORY_MB - HOST_RESERVE_MB;
        assertTrue(total <= budget,
                "容器内存上限之和是 " + total + "MB（" + detail.toString().trim() + "），"
                        + "超过了 " + budget + "MB 的预算（宿主机 " + HOST_MEMORY_MB + "MB 减去留给系统的 "
                        + HOST_RESERVE_MB + "MB）。"
                        + "把上限之和顶到物理内存，等于把\"谁先被 OOM\"交给内核随机决定 —— "
                        + "要么按比例调小各服务的 mem_limit，要么先确认宿主机真的换了更大的规格");
    }

    // =================================================================
    //  二、JVM 参数写在哪、写了什么
    // =================================================================

    @Test
    @DisplayName("Dockerfile：ENTRYPOINT 里不许出现 JVM 参数（会静默覆盖 compose 里的 JAVA_TOOL_OPTIONS）")
    void dockerfileEntrypointMustNotCarryJvmFlags() {
        String entrypoint = readFile(DOCKERFILE).lines()
                .map(String::trim)
                // 只看 ENTRYPOINT 那一行（注释里提到 -XX 是允许的，那正是解释"为什么挪走"的那段）
                .filter(line -> line.startsWith("ENTRYPOINT"))
                .collect(Collectors.joining(" "));

        assertFalse(entrypoint.isEmpty(), "Dockerfile 里找不到 ENTRYPOINT 行");
        assertFalse(entrypoint.contains("-XX"),
                "Dockerfile 的 ENTRYPOINT 里出现了 -XX 参数：" + entrypoint
                        + " —— JAVA_TOOL_OPTIONS 是 JVM 在解析命令行【之前】读入的，"
                        + "命令行上的同名参数会赢，于是 compose 里写的值被静默忽略，"
                        + "症状只是\"调了堆怎么没生效\"。所有堆参数应当只写在 compose 的 JAVA_TOOL_OPTIONS 里");
        assertFalse(entrypoint.contains("-Xm"),
                "Dockerfile 的 ENTRYPOINT 里出现了 -Xmx / -Xms：" + entrypoint
                        + " —— 固定值不会随容器上限变化，理由同上");
    }

    @Test
    @DisplayName("backend：堆占容器上限的比例必须留够堆外内存")
    void backendHeapMustLeaveRoomForNonHeap() {
        long limitMb = memoryMb(service("backend").get("mem_limit"));
        String javaToolOptions = javaToolOptions();

        double percent = heapPercent(javaToolOptions);
        assertTrue(percent <= MAX_HEAP_PERCENT,
                "堆占容器上限的比例是 " + percent + "%（上限 " + limitMb + "M → 堆约 "
                        + Math.round(limitMb * percent / 100) + "M），超过了 " + MAX_HEAP_PERCENT + "%。"
                        + "剩下的部分要装元空间、线程栈、直接内存（NIO）、JIT 代码缓存和 GC 自身的结构 —— "
                        + "它们都算在同一个 mem_limit 里，堆占太多会让容器在【堆还很空】的时候就被 cgroup 杀掉");

        assertTrue(javaToolOptions.contains("-XX:MaxMetaspaceSize="),
                "元空间必须有上限：它默认【无上限】，而它吃的是堆外内存 —— "
                        + "泄漏时的表现是容器被 cgroup 杀掉、Java 层面连 OutOfMemoryError 都不打，是最难查的一类 OOM");
    }

    @Test
    @DisplayName("backend：三个硬上限之和必须小于 mem_limit（否则是 cgroup 杀进程，Java 层连 OOM 都不报）")
    void backendHardLimitsMustFitInsideMemoryLimit() {
        long limitMb = memoryMb(service("backend").get("mem_limit"));
        String javaToolOptions = javaToolOptions();

        long heapMb = Math.round(limitMb * heapPercent(javaToolOptions) / 100);
        long metaspaceMb = requiredMemoryMb(javaToolOptions, "-XX:MaxMetaspaceSize=");
        long directMb = requiredMemoryMb(javaToolOptions, "-XX:MaxDirectMemorySize=");

        long sum = heapMb + metaspaceMb + directMb;
        assertTrue(sum < limitMb,
                "三个硬上限之和 " + sum + "M（堆 " + heapMb + "M + 元空间 " + metaspaceMb
                        + "M + 直接内存 " + directMb + "M）不小于 mem_limit " + limitMb + "M。"
                        + "⚠️ cgroup 的限额是【整进程】的，JVM 并不知道这几个上限加起来会不会越界 —— "
                        + "只要有可能越界，越界时就是内核直接杀掉容器（ExitCode 137），"
                        + "Java 层面连一句 OutOfMemoryError 都不会打，日志里无从查起。"
                        + "守着这条不变式，超限才会以\"某一类内存的 OOM + 一份堆转储\"的形式明确报出来。"
                        + "调整方向是把比例调小，而不是把 mem_limit 调大（后者会挤到别的服务）");
    }

    @Test
    @DisplayName("backend：堆转储与 GC 日志必须写进【挂了卷】的目录（否则随容器一起消失）")
    void heapDumpAndGcLogMustLiveOnMountedVolume() {
        String javaToolOptions = javaToolOptions();

        assertTrue(javaToolOptions.contains("-XX:+HeapDumpOnOutOfMemoryError"),
                "没有开堆转储的话，OOM 之后没有任何现场可以看，只能猜");
        assertTrue(javaToolOptions.contains("-XX:+ExitOnOutOfMemoryError"),
                "走到 OOM 的 JVM 已经不可信（可能连日志都写不出来），应当退出后由 restart 换一个干净的新进程");

        // 从参数里取出转储目录，再去 volumes 里确认那个目录真的挂了卷 ——
        // 这两件事分开看各自都对，连起来才是"重启后现场还在"
        String dumpPath = extractValue(javaToolOptions, "-XX:HeapDumpPath=");
        assertNotNull(dumpPath, "JAVA_TOOL_OPTIONS 里缺 -XX:HeapDumpPath=");
        assertVolumeMounted("backend", dumpPath);

        String gcLogPath = extractValue(javaToolOptions, "-Xlog:gc*:file=");
        assertNotNull(gcLogPath, "JAVA_TOOL_OPTIONS 里缺 GC 日志（-Xlog:gc*:file=...）："
                + "在内存吃紧的机器上，\"是不是一直在 GC\"是第一个要问的问题，而这些日志事后补不出来");
        assertVolumeMounted("backend", gcLogPath);
    }

    // =================================================================
    //  三、数据服务自己的内存参数
    // =================================================================

    @Test
    @DisplayName("redis：淘汰策略必须是 volatile-lru，且 maxmemory 必须小于容器上限")
    void redisMustProtectNonTtlKeysFromEviction() {
        Map<String, Object> redis = service("redis");
        List<String> command = stringList(redis.get("command"));

        String policy = commandValue(command, "--maxmemory-policy");
        assertNotNull(policy, "redis 没有配 --maxmemory-policy");
        assertEquals("volatile-lru", policy,
                "淘汰策略必须是 volatile-lru。本项目在 Redis 里存了两类【没有 TTL】的 key："
                        + "article:view:*（浏览量的待落库增量，丢了就是真丢数据）与两个缓存版本号计数器。"
                        + "allkeys-lru 会把它们一起淘汰，而且丢得静默无声");

        Long maxMemoryMb = parseMemoryMb(commandValue(command, "--maxmemory"));
        assertNotNull(maxMemoryMb, "redis 没有配 --maxmemory（默认是不限内存，2G 的机器上必须给它天花板）");

        long limitMb = memoryMb(redis.get("mem_limit"));
        assertTrue(maxMemoryMb <= limitMb,
                "redis 的 maxmemory=" + maxMemoryMb + "MB 大于容器上限 " + limitMb + "MB —— "
                        + "那等于没有上限：Redis 会在自己还没到 maxmemory 的时候就被 cgroup 杀掉");
    }

    @Test
    @DisplayName("mysql：必须关掉 performance_schema 并给缓冲池设上限（默认值在 2G 机器上太贵）")
    void mysqlMustCapItsOwnMemory() {
        Map<String, Object> mysql = service("mysql");
        List<String> command = stringList(mysql.get("command"));

        assertTrue(command.contains("--performance-schema=OFF"),
                "MySQL 的 performance_schema 默认是【开】的，自己就占 100~200MB —— "
                        + "对一台 2G 的机器来说这是最划算的一刀。本项目依赖的性能数据是慢查询日志，不依赖它");

        String bufferPool = commandValue(command, "--innodb-buffer-pool-size");
        assertNotNull(bufferPool, "InnoDB 缓冲池必须写一个显式的绝对值"
                + "（默认 128M 在这个规格下是合适的，但写出来才能一眼看出它和 mem_limit 的关系）");

        Long bufferPoolMb = parseMemoryMb(bufferPool);
        assertNotNull(bufferPoolMb, "解析不了 innodb-buffer-pool-size 的值：" + bufferPool);

        // ⚠️ 这条断言是实测踩出来的：MySQL 会把缓冲池【向上取整】到
        //    innodb_buffer_pool_chunk_size 的整数倍，而那个 chunk 默认就是 128M。
        //    实测 --innodb-buffer-pool-size=192M 时，@@innodb_buffer_pool_size 读到的是
        //    268435456（256M）—— 白占 64M 物理内存，而且不报错、不警告，
        //    只有在服务器上读一次变量才会发现。
        //    所以取值必须是 128 的整数倍。（真的改了 chunk size 的话，连同本条与 README 一起改。）
        assertEquals(0, bufferPoolMb % 128,
                "InnoDB 缓冲池写的是 " + bufferPoolMb + "M，不是 128M 的整数倍。"
                        + "MySQL 会把它向上取整到 innodb_buffer_pool_chunk_size（默认 128M）的整数倍："
                        + "实测写 192M 会实际生效 256M，白白多占 64M —— 而它不会报错也不会警告");

        long limitMb = memoryMb(mysql.get("mem_limit"));
        assertTrue(bufferPoolMb < limitMb,
                "InnoDB 缓冲池 " + bufferPoolMb + "MB 不小于容器上限 " + limitMb + "MB。"
                        + "⚠️ 缓冲池是【启动时就按这个大小申请】的，不是按需增长，所以它必须明显小于 mem_limit，"
                        + "否则 MySQL 会在初始化数据目录的那一步就被 OOM 杀掉（ExitCode 137）");
    }

    // =================================================================
    //  四、私有工具
    // =================================================================

    @SuppressWarnings("unchecked")
    private static Map<String, Object> services() {
        Object value = compose.get("services");
        assertNotNull(value, "compose 里没有 services 段");
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> service(String name) {
        Object value = services().get(name);
        assertNotNull(value, "compose 里没有服务 " + name);
        return (Map<String, Object>) value;
    }

    private static String javaToolOptions() {
        Object value = service("backend").get("environment");
        assertNotNull(value, "backend 没有 environment 段");
        @SuppressWarnings("unchecked")
        Map<String, Object> env = (Map<String, Object>) value;
        Object options = env.get("JAVA_TOOL_OPTIONS");
        assertNotNull(options, "backend 的 environment 里没有 JAVA_TOOL_OPTIONS —— "
                + "堆参数就靠它传给 JVM（Dockerfile 里刻意不写，见那里的注释）");
        return String.valueOf(options);
    }

    /** 从 JAVA_TOOL_OPTIONS 里取出"堆占容器上限的比例" */
    private static double heapPercent(String javaToolOptions) {
        Matcher matcher = Pattern.compile("-XX:MaxRAMPercentage=([0-9.]+)").matcher(javaToolOptions);
        assertTrue(matcher.find(),
                "backend 的 JAVA_TOOL_OPTIONS 里必须写 MaxRAMPercentage（按容器上限的百分比算堆，"
                        + "这样 mem_limit 一改堆就跟着变），实际=" + javaToolOptions);

        double percent = Double.parseDouble(matcher.group(1));
        assertTrue(percent > 0, "MaxRAMPercentage 必须是正数，实际=" + percent);
        return percent;
    }

    /**
     * 取出 {@code -XX:MaxMetaspaceSize=192m} 这类硬上限的值（MB）。
     * 缺了就直接失败：缺一项，"硬上限之和"这条不变式就无从判断了。
     */
    private static long requiredMemoryMb(String javaToolOptions, String key) {
        String raw = extractValue(javaToolOptions, key);
        assertNotNull(raw, "JAVA_TOOL_OPTIONS 里缺 " + key + " —— 它是不变式里的一项，"
                + "缺了就没人知道这几项加起来会不会越过 mem_limit");
        Long mb = parseMemoryMb(raw);
        assertNotNull(mb, "解析不了 " + key + " 的值：" + raw);
        return mb;
    }

    /** 从 `-XX:HeapDumpPath=/app/logs` 或 `-Xlog:gc*:file=/app/logs/gc.log:...` 里取出路径 */
    private static String extractValue(String options, String prefix) {
        int start = options.indexOf(prefix);
        if (start < 0) {
            return null;
        }
        String rest = options.substring(start + prefix.length());
        // 参数之间用空格分隔；GC 日志的路径后面还跟着装饰器（:time,uptime,...），所以再切一刀 ':'
        int end = rest.indexOf(' ');
        if (end >= 0) {
            rest = rest.substring(0, end);
        }
        int colon = rest.indexOf(':');
        return colon >= 0 ? rest.substring(0, colon) : rest;
    }

    /** 断言某个容器内的路径确实挂在具名卷上（"写了路径"和"重启后还在"是两件事） */
    private static void assertVolumeMounted(String serviceName, String containerPath) {
        List<String> mounts = stringList(service(serviceName).get("volumes"));
        boolean mounted = mounts.stream().anyMatch(mount -> {
            int colon = mount.lastIndexOf(':');
            if (colon < 0) {
                return false;
            }
            String mountPath = mount.substring(colon + 1);
            // 挂载点本身，或者挂载点【里面】的子路径都算数：
            // GC 日志是 /app/logs/gc.log，而卷挂在 /app/logs 上
            return containerPath.equals(mountPath) || containerPath.startsWith(mountPath + "/");
        });
        assertTrue(mounted,
                serviceName + " 的 " + containerPath + " 没有挂卷（volumes=" + mounts + "）。"
                        + "容器重建（改配置、升级、up -d --build 都会重建）会把写在容器可写层里的文件一起带走，"
                        + "而那正是最需要看这份日志/转储的时候");
    }

    /**
     * 取命令列表里某个参数的取值。
     *
     * 【为什么两种形式都要支持】同一个"key=value"在两个服务里写法不同：
     *   · MySQL 的 mysqld 支持 {@code --key=value} —— 所以它是【一个】元素
     *   · Redis 的 redis-server 支持 {@code --key value} —— 所以它是【两个】元素
     *   只认一种的话，另一种会静默地"找不到"，而失败信息会指向参数没配（其实是测试看漏了）。
     */
    private static String commandValue(List<String> command, String key) {
        int index = command.indexOf(key);
        if (index >= 0 && index + 1 < command.size()) {
            return command.get(index + 1);
        }
        // --key=value 形式
        String prefix = key + "=";
        return command.stream()
                .filter(element -> element.startsWith(prefix))
                .map(element -> element.substring(prefix.length()))
                .findFirst()
                .orElse(null);
    }

    /** `mem_limit` 的值：既接受 `512m` 这种可读写法，也接受 compose 展开后的纯字节数 */
    private static long memoryMb(Object rawValue) {
        Long mb = parseMemoryMb(String.valueOf(rawValue));
        assertNotNull(mb, "解析不了内存值：" + rawValue);
        return mb;
    }

    /**
     * 把各种内存写法解析成 MB。
     *
     * 【为什么要认这么多写法】这三个单位体系长得像但不是同一个：
     *   · compose 的 mem_limit：{@code 512m} / {@code 1g}（也接受纯字节数）
     *   · Redis 的 maxmemory：{@code 96mb} / {@code 1gb}（多一个 b）
     *   · MySQL 的 innodb-buffer-pool-size：{@code 192M}（大小写不敏感）
     *   只认一种后缀的话，其余两种会返回 null，于是断言报"参数没配" ——
     *   而实际上配了，只是测试读不懂。这类"测试自己看漏了"的失败最浪费时间。
     */
    private static Long parseMemoryMb(String raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.trim().toLowerCase(java.util.Locale.ROOT);
        try {
            // 先统一掉 Redis 那种 "mb"/"gb"/"kb" 的写法（去掉结尾的 b）
            if (value.length() > 1 && value.endsWith("b") && !Character.isDigit(value.charAt(value.length() - 2))) {
                value = value.substring(0, value.length() - 1);
            }
            if (value.endsWith("g")) {
                return Math.round(Double.parseDouble(value.substring(0, value.length() - 1)) * 1024);
            }
            if (value.endsWith("m")) {
                return Math.round(Double.parseDouble(value.substring(0, value.length() - 1)));
            }
            if (value.endsWith("k")) {
                return Math.round(Double.parseDouble(value.substring(0, value.length() - 1)) / 1024);
            }
            if (!value.isEmpty() && value.chars().allMatch(Character::isDigit)) {
                // 纯数字：compose 把它当【字节】。`docker compose config` 输出的就是这个形式
                return Long.parseLong(value) / (1024 * 1024);
            }
        } catch (NumberFormatException e) {
            return null;
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static List<String> stringList(Object value) {
        if (value == null) {
            return List.of();
        }
        return ((List<Object>) value).stream().map(String::valueOf).collect(Collectors.toList());
    }

    private static String readFile(Path path) {
        try {
            if (!Files.exists(path)) {
                fail("找不到文件 " + path.toAbsolutePath() + "（本测试假定运行时的工作目录是项目根目录）");
            }
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("读取 " + path.toAbsolutePath() + " 失败", e);
        }
    }
}
