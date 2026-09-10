package com.yigalaxy.yiguixingtu.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * =====================================================================
 * MyBatis-Plus 配置类
 *
 * 【这个类负责什么】
 *   把 MyBatis-Plus 的插件注册进 Spring 容器。目前只注册了一个：
 *   {@link PaginationInnerInterceptor}（分页插件）——
 *   它让 `mapper.selectPage(page, wrapper)` 这种写法能自动变成
 *   `... LIMIT ?, ?` 并且额外查一次总数，不用手写两条 SQL。
 *
 * 【用到的东西，以及它们各自是什么】
 *   · {@code @Configuration} —— Spring 的配置类标记。被它标住的类会被 Spring
 *     扫描到，里面带 {@code @Bean} 的方法返回值会被放进容器（这里是"交给 Spring 管"）。
 *   · {@code @MapperScan} —— MyBatis 的注解，告诉它去哪些包下面找 Mapper 接口，
 *     并为每个接口生成动态代理实现。写在配置类上就不用每个 Mapper 都加 {@code @Mapper}。
 *   · {@link MybatisPlusInterceptor} —— MyBatis-Plus 的插件总入口（本质是 MyBatis 的
 *     拦截器链）。所有功能插件都通过 {@code addInnerInterceptor(...)} 挂到它上面，
 *     顺序有意义：谁先加谁先执行。
 *   · {@link PaginationInnerInterceptor} —— 真正实现分页的那个内部拦截器。
 *
 * 【代码构造为什么是这样】
 *   总拦截器（MybatisPlusInterceptor）只负责"拿一串内部拦截器"，
 *   真正的行为都在内部拦截器上，所以要两步：
 *       ① new 一个 PaginationInnerInterceptor，把参数配好
 *       ② 把它 add 进 MybatisPlusInterceptor
 *   先配参数再 add，是因为 add 之后就进了拦截器链，
 *   在链上改对象属性虽然也生效，但读代码的人会以为"没配上"。
 * =====================================================================
 */
@Configuration
@MapperScan("com.yigalaxy.yiguixingtu.**.mapper")
public class MybatisPlusConfig {

    /**
     * 单页最多返回多少条 —— 分页的【全局兜底上限】。
     *
     * 【先澄清一件事：现在每个接口都已经自己夹过了】
     *   写这个常量的时候我曾经以为用户分页漏了夹取，查完发现并没有：
     *     · 文章分页：ArticleServiceImpl 里 {@code Math.min(size, 50)}
     *     · 用户分页：UserServiceImpl 里 {@code if (pageSize > 100) pageSize = 100}
     *   两边都做了。所以下面这个上限【不是在补一个 bug】。
     *
     * 【那它解决什么问题】
     *   解决的是"**将来**漏掉"。现在这两处夹取是**靠每个 Service 自己记得写**：
     *   谁下次新写一个分页接口、或者重构时把那几行删了，size 就会原样传到
     *   SQL 的 LIMIT 上，一个 {@code ?size=999999} 就能把整张表捞进内存、
     *   再序列化成 JSON 发出去 —— 不需要任何额外权限。
     *   把上限放进插件，等于在**所有分页查询的必经之路上**兜一道，
     *   和 Service 里各自夹取形成"纵深防御"：各自夹的是业务规则，插件兜的是安全底线。
     *
     * 【它和 Service 里那两个数字的关系（三道，各管各的）】
     *   | 层 | 值 | 性质 |
     *   |---|---|---|
     *   | 文章 Service | 50 | 业务规则：首页信息流一页最多 50 条 |
     *   | 用户 Service | 100 | 业务规则：后台用户列表一页最多 100 条 |
     *   | 本插件 | 100 | 安全底线：任何分页，size 都不可能超过它 |
     *   三者不冲突：谁更小谁生效。
     *
     * 【为什么是 100】
     *   · 前端后台列表一页 10 条，正常使用根本碰不到这个数
     *   · 100 条 VO 的响应体在几十 KB 量级，不构成负担
     *   · 定得比 100 更小（比如 20）会让"批量查看/导出"这类正常需求做不到，
     *     以后还得再加白名单，反而更乱
     */
    public static final long MAX_PAGE_SIZE = 100L;

    /**
     * 注册 MyBatis-Plus 插件链（目前只有一个分页插件）。
     *
     * @return 装配好的插件总入口，交给 Spring 管理；
     *         MyBatis 启动时会自动发现它并织入执行链
     */
    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {

        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();

        // DbType.MYSQL 是显式声明数据库方言。
        // 分页插件需要知道方言才能拼出正确的 LIMIT 语法
        // （MySQL 是 LIMIT offset, size，Oracle 是 ROWNUM，SQL Server 是 OFFSET/FETCH）。
        // 也可以让它自动探测，但显式写出来更稳、更快，也不会因为连了代理而被猜错。
        PaginationInnerInterceptor pagination = new PaginationInnerInterceptor(DbType.MYSQL);

        // 【关键一行】把全局上限交给分页插件。
        //
        // ⚠️ 一个容易误解的点（我在这里判断错过一次，写下来免得再踩）：
        //   setMaxLimit 改写的是【最终拼出来的 SQL 里的 LIMIT】，
        //   **不会**把 Page 对象上的 size 字段改掉。
        //   所以：
        //     · 想验证它生效，要看"实际返回了多少条"或直接看 SQL，
        //       看 page.getSize() 是看不出来的（那里还是调用方传进去的值）
        //     · UserAdminTest 里 `$.data.size` 能看到夹取后的 100，
        //       那个 100 来自 UserServiceImpl 自己的 if 判断，与这一行无关
        //   验证方式见 PaginationLimitTest：造 150 条数据、用 size=999999 去查，
        //   只回来 100 条就说明 LIMIT 被改写了（把这行注释掉则会回来 150 条）。
        //
        // 另外注意是"静默夹取"而不是"报错"：前端把 size 传大了不会白屏，
        // 只是拿到的条数比要的少 —— 对用户友好，对数据库安全。
        pagination.setMaxLimit(MAX_PAGE_SIZE);

        interceptor.addInnerInterceptor(pagination);
        return interceptor;
    }
}
