package com.yigalaxy.yiguixingtu.common.web;

import lombok.extern.slf4j.Slf4j;
import org.lionsoul.ip2region.xdb.Searcher;
import org.lionsoul.ip2region.xdb.Version;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;

/**
 * =====================================================================
 * IP → 归属地（省份 / 城市）—— 全项目唯一一处解析规则
 *
 * 【和 ClientIp 的分工】两者都在 common/web 下，都跟"IP"有关，但不是一类东西：
 *   · {@link ClientIp} 是**无状态工具**：从请求头里把 IP 取出来，一次调用、零成本
 *   · 本类是**持有数据的组件**：它把一份 11MB 的离线地址库读进内存，
 *     用"这个 IP 落在哪个网段"反查归属地。启动时构建一次，之后只读
 *
 * 【为什么用离线库，不调在线 API】
 *   ① **IP 不出服务器**。在线 API（ip-api、高德等）意味着每一个访客的 IP
 *      都要发给第三方 —— 那是把"我们已经在留 90 天的个人信息"再扩大一圈，
 *      而换来的只是"列表里多显示一个城市"
 *   ② 没有速率限制、没有对方挂掉的可能，也不消耗任何配额
 *   ③ 查询是纯内存查表，微秒级 —— 它挂在**每次访问上报**的路径上，
 *      任何一次网络往返都不可接受
 *   代价是数据有版本（见下面 DATA_SOURCE 那段），需要偶尔更新 xdb 文件。
 *
 * 【数据来源与更新方式】
 *   ip2region（Apache-2.0），文件 {@code src/main/resources/ip2region_v4.xdb}。
 *   更新 = 从上游重新下载覆盖同名文件、重新构建：
 *   {@code https://raw.githubusercontent.com/lionsoul2014/ip2region/master/data/ip2region_v4.xdb}
 *   ⚠️ 它是**随 jar 打包**的，改完必须重新构建并部署，改服务器上的文件没有用。
 *
 * 【⚠️ 这个类绝不会让应用起不来，也绝不会抛异常】
 *   归属地是"统计页上多一列"这种程度的锦上添花。它挂掉时正确的表现是
 *   **少显示一列**，而不是：
 *     · 应用启动失败（xdb 文件缺失 / 版本不对 —— 那会让整站不可用）
 *     · 每有一条访问就抛一次异常（写入路径在 VisitController 的 try/catch 里，
 *       异常会被记成"记录访问失败"，把统计整条丢掉）
 *   ⇒ 所以：加载失败时只打一行 WARN 并把自己置为"不可用"，之后 resolve 恒返回 null；
 *     单次查询出问题同样吞掉。**"没有归属地"是一个合法的结果**，
 *     UI 上显示成「—」，与"这个 IP 确实没有归属地"看起来完全一样 ——
 *     这是可接受的，因为两者的处理方式本来就该一样。
 * =====================================================================
 */
@Slf4j
@Component
public class IpRegionResolver {

    /** 离线地址库在 classpath 上的位置（放在 resources 根下，随 jar 一起打包） */
    private static final String XDB_RESOURCE = "/ip2region_v4.xdb";

    /** 与 visit_log.region 列宽一致（varchar(64)），超长要截断否则 INSERT 会失败 */
    private static final int REGION_MAX_LENGTH = 64;

    /** ip2region 对"保留地址/内网地址"返回的占位值（127.0.0.1、192.168.x.x 都是它） */
    private static final String RESERVED = "Reserved";

    /**
     * 内网地址的显示名。
     * ⚠️ 访客列表里出现「内网」是一个**有意义的信号**：说明这个 IP 根本不是公网地址，
     *   多半是本机调试，或者反向代理没把 X-Forwarded-For 传对（见 ClientIp 的类注释）——
     *   那种情况下**所有访客会变成同一个 IP**，UV 会明显偏低。
     *   所以这里不返回 null 把它藏起来，而是明确写出来。
     */
    private static final String INTERNAL_LABEL = "内网";

    /** 无意义的段：ip2region 用 "0" 表示"这一级没有数据" */
    private static final String EMPTY_SEGMENT = "0";

    /** 解析结果里的分隔符（ip2region 的字段格式：国家|省份|城市|ISP|ISO代码） */
    private static final String FIELD_SEPARATOR = "\\|";

    /** 中国：归属地是中文时不需要在每一条前面都写"中国" */
    private static final String CHINA = "中国";

    private final Searcher v4Searcher;

    /**
     * 构造时把 xdb 读进内存。
     *
     * 【为什么是"全内存"模式而不是"只缓存索引"（省 10MB 内存）】
     *   本类挂在每次访问上报的路径上，而 vIndex 模式仍然要读一次磁盘
     *   （平均 100 微秒、且是随机读）。11MB 对 backend 容器（上限 640M、
     *   堆约 384M）来说是 3%，换掉这条路径上的所有磁盘 IO 很划算。
     *
     * 【为什么用 getResourceAsStream 而不是给一个文件路径】
     *   xdb 是打包进 jar 的资源，在部署环境里**磁盘上根本没有独立文件**
     *   （除了 jar 自己）。给它一个路径的写法在本地能跑、进了容器就找不到。
     *   从 classpath 流式读进来，两种环境下是同一条代码路径。
     */
    public IpRegionResolver() {
        this.v4Searcher = load();
    }

    /**
     * 解析一个 IP 的归属地。
     *
     * @param ip IPv4 地址；允许 null / 空串 / 任何畸形值（上游 {@link ClientIp} 允许返回 null）
     * @return 给人看的归属地，如 {@code "江苏省 南京市"}、{@code "United States California"}、
     *         {@code "内网"}；**解析不出时返回 null**（不是空串 —— "没有"和"空值"要分得开）
     */
    public String resolve(String ip) {
        if (v4Searcher == null || ip == null || ip.isBlank()) {
            return null;
        }
        try {
            return toDisplay(v4Searcher.search(ip.trim()));
        } catch (Exception e) {
            // 【为什么连日志都不打】IPv6 访客、畸形 X-Forwarded-For 都会走到这里，
            // 而那是**正常输入**（不是故障）。每次访问打一行 WARN 会把日志淹掉，
            // 而且它没有任何可操作的信息 —— 结果都一样：这一条没有归属地。
            return null;
        }
    }

    /**
     * 把 ip2region 的原始结果拼成给人看的一行。
     *
     * 【原始格式】{@code 国家|省份|城市|ISP|ISO代码}（5 段，固定顺序），例如
     * <pre>
     *   中国|江苏省|南京市|0|CN          → 江苏省 南京市
     *   中国|北京市|北京市|电信|CN        → 北京市          （省市同名，去重）
     *   United States|California|0|Google LLC|US  → United States California
     *   Reserved|Reserved|Reserved|0|0   → 内网
     * </pre>
     *
     * 【为什么不要 ISP 那段】"电信/阿里/Google LLC"回答的是"这个 IP 属于谁"，
     *   不是"在哪"。访客列表里那一列叫「归属地」，混进运营商会让人以为
     *   "深圳市 电信"是一个地名。真要看 ISP 时再加一列，别塞进同一个字段。
     *
     * 【为什么中国不显示】列表里绝大多数行都是中国，每行都写一遍"中国"
     *   只会把真正有信息量的省市挤掉。境外的才带上国家 —— 那才是需要一眼看到的。
     */
    private String toDisplay(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        // -1：保留末尾空段（"a|b|" 要切成 3 段而不是 2 段），否则索引会错位
        String[] parts = raw.split(FIELD_SEPARATOR, -1);

        // ⚠️ 【顺序要紧】必须先判 Reserved、再做归一化。
        //   归一化会把 "Reserved" 抹成空串（它与 "0" 一样表示"这一级没有数据"），
        //   反过来写的话下面那个判断永远看不到 "Reserved" ——
        //   127.0.0.1 会一路走到"三个字段全空 → 返回 null"，
        //   于是内网地址显示成「—」而不是「内网」，把那个信号吞掉了。
        if (isReserved(partAt(parts, 0)) || isReserved(partAt(parts, 1)) || isReserved(partAt(parts, 2))) {
            // 保留地址：ip2region 对 127.0.0.1 / 192.168.x.x / 10.x.x.x 一律回这个。
            // 它在后台访客列表里是一个有意义的信号（多半是本机调试，
            // 或者代理没把真实 IP 传进来），所以不返回 null，而是明确写出来
            return INTERNAL_LABEL;
        }

        String country = normalize(partAt(parts, 0));
        String province = normalize(partAt(parts, 1));
        String city = normalize(partAt(parts, 2));

        StringBuilder text = new StringBuilder();
        // 境外才带国家。注意判断的是"国家这一段有内容且不是中国"，
        // 而不是"省市为空" —— 境外的省市可能也是 0（小国家只有国家级数据）
        if (!country.isEmpty() && !CHINA.equals(country)) {
            text.append(country);
        }
        appendSegment(text, province);
        // 省市同名（直辖市：北京市/北京市、上海市/上海市）时只留一个
        if (!city.equals(province)) {
            appendSegment(text, city);
        }

        if (text.isEmpty()) {
            return null;
        }
        return truncate(text.toString());
    }

    /** 取第 index 段（只做去空白与越界保护，不做归一化 —— 见上面那段注释）；越界返回空串 */
    private String partAt(String[] parts, int index) {
        if (index >= parts.length || parts[index] == null) {
            return "";
        }
        return parts[index].trim();
    }

    /** 是不是保留地址的占位值（大小写不敏感：库里写的是 Reserved） */
    private boolean isReserved(String value) {
        return RESERVED.equalsIgnoreCase(value);
    }

    /**
     * 把 "0" / 空白统一成空串 —— 它们都表示"这一级没有数据"。
     * ⚠️ 这里**不处理 "Reserved"**：那个值要在归一化之前就被识别出来（见 toDisplay）。
     */
    private String normalize(String value) {
        if (value == null || value.isEmpty() || EMPTY_SEGMENT.equals(value)) {
            return "";
        }
        return value;
    }

    private void appendSegment(StringBuilder text, String segment) {
        if (segment.isEmpty()) {
            return;
        }
        if (!text.isEmpty()) {
            text.append(' ');
        }
        text.append(segment);
    }

    /**
     * 截断到列宽以内。
     * 【为什么是截断而不是报错】理由与 ClientIp.truncate 完全一致：
     *   它是给"插入 visit_log.region"用的，不截断会让超长值把整条 INSERT 打回去 ——
     *   而"归属地太长"不值得丢掉一条访问记录（更不该让访客的页面出问题）。
     */
    private String truncate(String region) {
        return region.length() > REGION_MAX_LENGTH ? region.substring(0, REGION_MAX_LENGTH) : region;
    }

    /** 启动时加载地址库；失败时返回 null（见类注释：绝不因此让应用起不来） */
    private Searcher load() {
        try (InputStream in = IpRegionResolver.class.getResourceAsStream(XDB_RESOURCE)) {
            if (in == null) {
                // 最常见的原因：xdb 没被提交进仓库 / 构建时被 .dockerignore 排除掉了
                throw new IOException("classpath 上找不到 " + XDB_RESOURCE
                        + "（它应当随 jar 一起打包，见本类注释里的「数据来源与更新方式」）");
            }
            // loadContentFromInputStream 会把整个文件读成一个堆上的字节数组，
            // 之后的查询完全不走磁盘 —— 这也是它能安全地并发调用的原因
            return Searcher.newWithBuffer(Version.IPv4, Searcher.loadContentFromInputStream(in));
        } catch (Exception e) {
            log.warn("IP 归属地库加载失败，访问统计将不显示归属地（其它功能不受影响）: {}", e.getMessage());
            return null;
        }
    }

}
