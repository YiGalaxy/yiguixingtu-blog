package com.yigalaxy.yiguixingtu;

import com.yigalaxy.yiguixingtu.common.web.IpRegionResolver;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * =====================================================================
 * IP 归属地解析的用例
 *
 * 【为什么这个类不继承 AbstractIntegrationTest】
 *   被测对象是一个纯查表组件：给它一个 IP 字符串，它回一个地名。
 *   既不需要数据库、也不需要 Redis —— 为它启一整个 Spring 上下文
 *   （还要起 Testcontainers 的 MySQL 与 Redis）纯属浪费，
 *   而「这个组件本身对不对」和「它在容器里能不能被注入」是两件事，
 *   后者由 VisitLogTest 里那条「上报之后 region 落库了」的用例覆盖。
 *   （同类做法见 DeploymentMemoryBudgetTest：只测自己那点东西。）
 *
 * 【这一组最要紧的三件事】
 *  ① **xdb 真的在 classpath 上**。它是 11MB 的二进制，不在代码里、
 *     也不是依赖自动带来的 —— 被误删、被 .gitignore 掉、被构建配置排除掉，
 *     表现都只是「归属地那一列永远是空的」，不会有任何报错。
 *     下面第一条用例就是钉这件事的。
 *  ② **解析失败绝不能抛异常**。它挂在每次访问上报的路径上（每翻一页就调一次），
 *     IPv6 访客、被伪造的 X-Forwarded-For、代理塞进来的畸形值都是正常输入。
 *  ③ **内网地址要说出来，不能藏起来**。它显示成「内网」时是一个信号：
 *     要么是本机调试，要么是反向代理没把真实 IP 传进来（那会让所有访客变成同一个 IP）。
 * =====================================================================
 */
class IpRegionResolverTest {

    /**
     * 整个类共用一个实例。
     *
     * 【为什么要共用】构造一次要把 11MB 的 xdb 读进内存（几十毫秒）。
     * 每条用例都 new 一个的话，这个类的耗时会有相当一部分花在重复加载上。
     */
    private static IpRegionResolver resolver;

    @BeforeAll
    static void loadResolver() {
        resolver = new IpRegionResolver();
    }

    // =================================================================
    //  一、地址库本身（★ 这条守着「11MB 的 xdb 还在不在」）
    // =================================================================

    @Test
    @DisplayName("① ★ 地址库能加载，且公网 IP 解析得出地名（xdb 真的在 classpath 上）")
    void shouldResolvePublicIp() {
        String region = resolver.resolve("114.114.114.114");

        // 【为什么这条断言这么「松」】它要守的是「xdb 加载成功且能查出东西」，
        //   而不是「南京这个城市的名字没变」。写成 assertEquals("江苏省 南京市")
        //   的话，将来从上游更新一次数据（地名口径调整、网段归属变化）
        //   就会让这条用例变红，而那时**代码一个字都没错** ——
        //   这种「测试自己看错了」的失败最浪费时间。
        //   真实地名的正确性由数据源负责，不是这个类的职责。
        assertNotNull(region, "★ 公网 IP 解析不出归属地，多半是 ip2region_v4.xdb 没被打进 jar");
        assertFalse(region.contains("|"), "原始结果是 5 段用 | 分隔的，拼成显示文本后不该还有分隔符：" + region);
        assertFalse(region.contains("Reserved"), "公网 IP 不该被当成保留地址：" + region);
        assertFalse(region.isEmpty(), "解析出空串与「解析不出」应当区分开（后者必须是 null），前者不该出现");
    }

    @Test
    @DisplayName("② 国内 IP 不带「中国」前缀，境外的才带国家")
    void shouldPrefixCountryOnlyForOverseas() {
        String domestic = resolver.resolve("114.114.114.114");
        assertFalse(domestic.startsWith("中国"),
                "国内访客占绝大多数，每行都写一遍「中国」只会把真正有信息量的省市挤掉：" + domestic);

        // 8.8.8.8 是 Google 的公共 DNS，归属地必然不在中国
        String overseas = resolver.resolve("8.8.8.8");
        assertNotNull(overseas, "8.8.8.8 应当解析得出归属地（它在库里是最常见的几条之一）");
        assertFalse(overseas.startsWith("中国"), "8.8.8.8 不可能是中国的地址：" + overseas);
    }

    // =================================================================
    //  二、解析不出来的输入（每一条都必须安静地返回 null）
    // =================================================================

    @Test
    @DisplayName("③ ★ 内网与保留地址显示成「内网」（它是一个有意义的信号，不能藏起来）")
    void shouldLabelReservedAddresses() {
        // 这几类都会走到 ip2region 的 Reserved 分支：回环、私有网段、文档专用网段
        assertEquals("内网", resolver.resolve("127.0.0.1"));
        assertEquals("内网", resolver.resolve("192.168.1.5"));
        assertEquals("内网", resolver.resolve("10.0.0.1"));
        assertEquals("内网", resolver.resolve("203.0.113.7"),
                "TEST-NET-3 是文档专用网段，库把它归在保留地址里");
    }

    @ParameterizedTest
    @DisplayName("④ ★ 解析不出来的输入一律返回 null，绝不抛异常（它挂在每次访问上报的路径上）")
    @NullSource
    @ValueSource(strings = {
            "",                          // 空串（ClientIp 取不到值时给的是 null，但空串也可能出现）
            "   ",                       // 全是空格
            "not-an-ip",                 // 畸形值（被伪造的 X-Forwarded-For）
            "999.999.999.999",           // 长得像 IP 但越界
            "2001:4860:4860::8888",      // IPv6：库里只有 v4 数据，解析不了是预期行为
            "::1",
            "114.114.114.114, 10.0.0.1", // 没切干净的 X-Forwarded-For 整串
    })
    void shouldReturnNullForUnresolvableInput(String ip) {
        // 不写 assertDoesNotThrow：这个方法的约定是「永不抛异常」，
        // 而它被违反了的话这条用例会因为异常直接失败 —— 那正是我们想看到的
        assertNull(resolver.resolve(ip), "解析不出的输入应当返回 null：" + ip);
    }
}
