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
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * =====================================================================
 * 管理端点（/actuator/prometheus）的暴露面契约
 *
 * 【为什么需要这个测试 —— 它守的是"应用层故意放行、只能靠部署挡住"的端点】
 *   /actuator/prometheus 在 SecurityConfig 里是【匿名放行】的，这不是疏忽：
 *   抓指标的是 Prometheus，它没有也不该有我们的 JWT（理由见那边的注释）。
 *   所以它的安全完全依赖部署层的两道护栏：
 *     ① 容器端口只绑 127.0.0.1（公网连不到 8082）
 *     ② Nginx 把 /api/actuator/ 返回 404
 *   而 ② 曾经【写错了，且错得很危险】：
 *     原来的说法是"Nginx 只反代 /api/、没有 /actuator 的 location，所以打不到"。
 *     这个推论是反的 —— `location /api/ { proxy_pass http://127.0.0.1:8082/; }`
 *     结尾那个 `/` 会把 `/api/` 前缀替换成 `/`，于是
 *     /api/actuator/prometheus 正好映射到后端那个放行的 /actuator/prometheus。
 *     请求走的一直是 /api/ 那一条，它根本不需要任何 /actuator 的 location。
 *   这类错误的坏处不只是"少了一道防护"，而是【注释和核对清单都在说它安全】——
 *   下一个人会安心地把这层防护删掉，而且上线核对时也不会真的去试一次。
 *   ⇒ 所以把它变成断言：文档里少一段、或者被人改回去，本地测试就会红。
 *
 * 【它为什么读文档而不是启应用】
 *   仓库里【没有】nginx.conf 这个文件 —— 这份 Nginx 配置只存在于 README 的部署章节里
 *   （那是部署时唯一的事实来源，也是人真正会去复制粘贴的东西）。
 *   应用层的另一半已经由 MetricsEndpointTest 断言过了（/actuator/prometheus 匿名返回 200、
 *   /actuator/env 与 /actuator/beans 是 404），这里不重复。
 *   纯读文件，不继承 AbstractIntegrationTest，不需要数据库和 Redis。
 *
 * 【⚠️ 它证明不了什么，要说清楚（免得给人虚假的安全感）】
 *   这些用例只能证明"文档里写着这段配置"。它【不能】证明你的服务器上真的配了 ——
 *   那只能靠上线核对清单第 9 条实际 curl 一次（本测试的第 ④ 条用例正是盯着
 *   "核对清单有没有明确写出该看到 404"，因为这一条原来写的是"正常情况打不到"，
 *   于是没有人会真的去试）。
 *
 * 【代码构造】
 *   · 先取 README 里那份 server 块（靠 "server {" + "listen 443 ssl;" 这对特征定位，
 *     避开正文里提到 `listen 443 ssl;` 的那句话）
 *   · 再在块内按 location 名取出单个 location，逐个断言
 *   · compose 用 SnakeYAML 解析，检查每个服务的 ports
 *   关键字：{@code proxy_pass} 结尾的斜杠（前缀替换语义）、
 *   {@code limit_req}（限流，与本测试无关但会在同一个 location 里出现）、
 *   {@code expose}（只声明端口不发布，与 {@code ports} 不同，后者才会发布到宿主机）。
 * =====================================================================
 */
class ActuatorExposureContractTest {

    /** 项目根目录（Surefire 的工作目录就是它，所以这两个文件用相对路径就能找到） */
    private static final Path PROJECT_ROOT = Path.of(System.getProperty("user.dir"));

    private static final Path README = PROJECT_ROOT.resolve("README.md");

    private static final Path COMPOSE_FILE = PROJECT_ROOT.resolve("docker-compose.prod.yaml");

    /** README 里那份 Nginx server 块（占位符保持原样，只做结构断言） */
    private static String serverBlock;

    /** 解析后的 compose 文档 */
    private static Map<String, Object> compose;

    @BeforeAll
    static void loadDocuments() {
        String readme = readFile(README).replace("\r\n", "\n");

        // 用 "server {" + "listen 443 ssl;" 这对特征定位代码块：
        // 正文里也出现过 `listen 443 ssl;`（在讲解"没有证书 Nginx 起不来"那一段），
        // 只按那一句找会定位到散文里去。
        int start = readme.indexOf("server {\n    listen 443 ssl;");
        assertTrue(start > 0,
                "README 里找不到 Nginx 的 server 块（应当是 ```nginx 围起来的、以 server { 开头的那段）。"
                        + "如果部署文档被重写了，请把这个测试一起更新 —— 它守的是那份配置里的一条规定");
        int end = readme.indexOf("```", start);
        assertTrue(end > start, "找不到 Nginx 代码块的结束围栏");
        serverBlock = readme.substring(start, end);

        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        compose = new Yaml(options).load(readFile(COMPOSE_FILE));
        assertNotNull(compose, "docker-compose.prod.yaml 解析出来是空的");
    }

    // =================================================================
    //  一、/api/ 前缀的语义（后面那条防护之所以必要，就是因为这个语义）
    // =================================================================

    @Test
    @DisplayName("Nginx：location /api/ 必须把 /api 前缀【剥掉】（这正是 /api/actuator/ 会命中后端的根因）")
    void apiPrefixMustBeStrippedByProxyPass() {
        String apiLocation = locationBlock("/api/");

        // proxy_pass 结尾的斜杠不是风格问题，它决定了两件事：
        //   ① 后端路由里【没有】/api 前缀（见各 Controller 的 @RequestMapping），
        //      所以必须剥掉 —— 不剥的话每个接口都会 404；
        //   ② 剥掉之后 /api/actuator/prometheus 正好拼成后端的 /actuator/prometheus，
        //      也就是那个匿名放行的管理端点 —— 这是下面那条防护存在的【原因】。
        //      谁要是把这一行的斜杠去掉、或者改成别的映射方式，
        //      请连同 location /api/actuator/ 那一段一起重新想一遍。
        assertTrue(apiLocation.contains("proxy_pass http://127.0.0.1:8082/;"),
                "location /api/ 里应当是 `proxy_pass http://127.0.0.1:8082/;`（结尾带斜杠 = 替换掉 /api 前缀）。"
                        + "实际内容：\n" + apiLocation);
    }

    // =================================================================
    //  二、部署层的两道护栏
    // =================================================================

    @Test
    @DisplayName("Nginx：必须有把 /api/actuator/ 返回 404 的 location（应用层放行了它，这里不挡就是公网可读）")
    void nginxMustBlockActuatorUnderApiPrefix() {
        String actuatorLocation = locationBlockOrNull("/api/actuator/");

        assertNotNull(actuatorLocation,
                "README 的 Nginx 配置里没有 `location /api/actuator/` 这一段。"
                        + "⚠️ 不要以为\"Nginx 里没有 /actuator 的 location 所以访问不到\"——"
                        + "location /api/ 的 proxy_pass 结尾带斜杠，会把 /api/actuator/prometheus "
                        + "拼成后端的 /actuator/prometheus，而那正是匿名放行的管理端点（能读到接口路径、"
                        + "调用量、连接池占用、JVM 内存）。这一段是必须的，不是多一层保险");

        assertTrue(actuatorLocation.contains("return 404;"),
                "location /api/actuator/ 里应当直接 return 404（而不是反代过去让后端拒绝，"
                        + "那样既多一次转发，又给这个端点留下被误放行的机会）。实际内容：\n" + actuatorLocation);

        assertFalse(actuatorLocation.contains("proxy_pass"),
                "这一段刻意【不】反向代理到后端：它的目的就是让这些路径在这里就结束。"
                        + "实际内容：\n" + actuatorLocation);
    }

    @Test
    @DisplayName("编排：数据库端口不发布、应用端口只绑回环（公网直连不到 8082/3000/3306/6379）")
    void dataAndAppPortsMustNotBeExposedToPublic() {
        // 这是上面那个端点的第一道护栏，同时也是"防拖库"最基本的一条。
        // 【为什么连 mysql / redis 也要断言】它们不是"没配 ports 所以碰巧安全"，
        //   而是刻意不配：容器之间走 compose 内网的服务名互相访问，
        //   不需要任何宿主机端口。一旦有人为了"方便用客户端连库"加上 ports，
        //   3306 就会暴露到公网 —— 那是最常见的被拖库原因之一。
        for (String dataService : new String[]{"mysql", "redis"}) {
            Object ports = service(dataService).get("ports");
            assertTrue(ports == null || stringList(ports).isEmpty(),
                    "服务 " + dataService + " 不该发布任何宿主机端口（实测配了 ports=" + ports + "）。"
                            + "容器之间用服务名互访即可；把 3306 / 6379 暴露到公网是最常见的被拖库原因");
        }

        // 应用端口要给宿主机上的 Nginx 反代，所以必须发布 —— 但只能绑回环。
        // 不写 "127.0.0.1:" 前缀时 Docker 会绑到 0.0.0.0，也就是公网可以绕过 Nginx
        // 直接打后端：限流、访问日志、HTTPS 全部失效，/actuator/prometheus 也一起暴露。
        for (String appService : new String[]{"backend", "frontend"}) {
            List<String> ports = stringList(service(appService).get("ports"));
            assertFalse(ports.isEmpty(), "服务 " + appService + " 应当发布端口给宿主机的 Nginx 用");
            for (String port : ports) {
                assertTrue(port.startsWith("127.0.0.1:"),
                        "服务 " + appService + " 的端口映射 " + port + " 没有以 127.0.0.1: 开头。"
                                + "不加这个前缀时 Docker 会绑到 0.0.0.0，公网就能绕过 Nginx 直接访问 "
                                + appService + " —— 限流、访问日志、HTTPS 全部失效");
            }
        }
    }

    // =================================================================
    //  三、人在上线时要照着做的那一步
    // =================================================================

    @Test
    @DisplayName("核对清单：必须明确写出这一项该看到 404（原来写的是\"正常情况打不到\"，于是没人会去试）")
    void checklistMustStateTheExpectedStatusCode() {
        String readme = readFile(README).replace("\r\n", "\n");

        // 找到核对清单里讲这个端点的那一行（表格的一行）
        String row = readme.lines()
                .filter(line -> line.startsWith("|") && line.contains("actuator/prometheus"))
                .findFirst()
                .orElse(null);
        assertNotNull(row, "上线核对清单里找不到 /actuator/prometheus 这一项 —— "
                + "它是这个端点的最后一道人工防线，不能删");

        // 【为什么断言的是"写了 404"而不是"写了应该打不到"】
        //   人工核对的价值在于"能明确判断做没做对"。原来这一项写的是
        //   "应当拿不到指标（正常情况打不到）"—— 这种说法既没法验证，
        //   又暗示"不用管它"，结果就是没有人真的去 curl 一次。
        //   写成"应当返回 404"之后，核对的人有一个可执行、可判定的动作。
        assertTrue(row.contains("404"),
                "这一项必须写出【可判定的预期结果】（应当返回 404），而不是\"按道理打不到\"。"
                        + "实际内容：" + row);
    }

    // =================================================================
    //  四、私有工具
    // =================================================================

    /** 从 README 的 server 块里取出某个 location（找不到就断言失败） */
    private static String locationBlock(String name) {
        String block = locationBlockOrNull(name);
        assertNotNull(block, "Nginx 配置里找不到 `location " + name + " {`。实际 server 块：\n" + serverBlock);
        return block;
    }

    /** 从 README 的 server 块里取出某个 location；没有则返回 null（用于"必须存在"之外的判断） */
    private static String locationBlockOrNull(String name) {
        String marker = "location " + name + " {";
        int start = serverBlock.indexOf(marker);
        if (start < 0) {
            return null;
        }
        // location 块的收尾是缩进 4 空格的 "}"，块内的语句都更深一层
        int end = serverBlock.indexOf("\n    }", start);
        assertTrue(end > start, "location " + name + " 的收尾大括号没找到，README 里的配置可能被改坏了");
        return serverBlock.substring(start, end);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> service(String name) {
        Object services = compose.get("services");
        assertNotNull(services, "compose 里没有 services 段");
        Object value = ((Map<String, Object>) services).get(name);
        assertNotNull(value, "compose 里没有服务 " + name);
        return (Map<String, Object>) value;
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
