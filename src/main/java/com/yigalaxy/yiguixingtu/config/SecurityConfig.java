package com.yigalaxy.yiguixingtu.config;

import com.yigalaxy.yiguixingtu.auth.filter.JwtAuthenticationFilter;
import org.springframework.beans.factory.annotation.Value;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.List;

/**
 * =====================================================================
 * 【这是什么？】Spring Security 安全配置。
 *
 * 之前你被默认登录页拦截，就是因为它没有这个配置。
 * 现在这个配置是"接入 JWT 之后的正式版"，告诉 Spring Security：
 *   1. 哪些接口需要登录，哪些不用
 *   2. 用什么方式验证身份（JWT token，没用 session）
 *   3. 用什么加密密码（BCrypt）
 *   4. 认证管理器怎么建
 * =====================================================================
 */
@Configuration                       // 这是一个配置类
@EnableWebSecurity                   // 启用 Spring Security 的自定义配置（关掉默认那套）
@EnableMethodSecurity                // 启用 @PreAuthorize 注解（以后做接口权限用）
public class SecurityConfig {

    /** 我们自己的 JWT 过滤器，用它来验 token */
    private final JwtAuthenticationFilter jwtAuthenticationFilter;

    /**
     * 允许跨域访问的前端来源（英文逗号分隔）。
     *
     * 【为什么从配置读，而不是写死在代码里】
     *   原来这里是 {@code List.of("http://localhost:3000")}，导致：
     *     · 前端换个端口（比如 vite 的 5173）就跨域失败
     *     · 一部署上线（前端变成 https://你的域名）必然跨域失败
     *     · 而且改完还得重新编译打包
     *   现在取值顺序是：环境变量 CORS_ALLOWED_ORIGINS → application.properties 的默认值。
     *   本地开发不用做任何事（默认就是 localhost:3000），线上注入环境变量即可。
     *
     * 【@Value 是怎么把字符串变成 List 的】
     *   配置里写成一整个字符串（"a,b"），这里手动按逗号切开、去空格、丢空串。
     *   不直接用 List 类型注入，是因为环境变量本身就是"一个字符串"，
     *   手动切分能让"线上怎么填"这件事更直观（填一串用逗号隔开的地址）。
     */
    @Value("${app.cors.allowed-origins}")
    private String allowedOrigins;

    /**
     * 接口文档（Swagger）是否启用。
     *
     * 【为什么 SecurityConfig 需要知道这件事】
     *   dev 里它是 true、prod 里是 false（见 application-prod.properties）。
     *   它的作用不只是"让 springdoc 别生成文档"，还要决定
     *   {@code /v3/api-docs/**}、{@code /swagger-ui/**} 这些路径【放不放行】。
     *   一开始只配了 springdoc 的开关、没管放行名单，结果实测发现
     *   生产环境里 /v3/api-docs 依然返回 200 —— 等于文档还开着。
     *   所以这两件事必须由同一个开关控制，原因见下面 securityFilterChain 里的注释。
     */
    @Value("${springdoc.api-docs.enabled:false}")
    private boolean apiDocsEnabled;

    /** 构造注入 JWT 过滤器 */
    public SecurityConfig(JwtAuthenticationFilter jwtAuthenticationFilter) {
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
    }

    /**
     * 核心：定义"安全过滤链"，也就是一套完整的规则。
     * 返回 SecurityFilterChain，Spring Security 会用它来拦截请求。
     */
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                // 1. 关闭 CSRF 校验 ------------------------------------------
                // CSRF 是防跨站请求伪造的，但它是基于 session/cookie 的；
                // 我们用的是 JWT（放请求头），所以用不到，关掉。
                .csrf(AbstractHttpConfigurer::disable)
                .cors(Customizer.withDefaults())
                // 2. 设置会话为"无状态"（STATELESS）-------------------------
                // 意思是：不用 session 记录登录态，全部靠 token。
                // 这是 JWT 的典型做法。
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))

                // 3. 配置"路径权限规则" --------------------------------------
                .authorizeHttpRequests(auth -> {
                    // permitAll = 允许所有人访问，不需要登录。
                    // 登录/注册接口是所有人都要能调的（不然没法登录）。
                    //
                    // 【登出为什么也放行】—— 这是被测试逼出来的一个决定
                    //   一开始 /auth/logout 也是"必须登录"，结果出现一个很别扭的现象：
                    //   连续调两次登出，第二次返回 401（因为第一次已经把 token 拉黑了）。
                    //   放到前端就是：用户点"退出" → 请求 401 → useApi 弹"登录已过期"
                    //   并弹出登录框。用户刚点完退出却被要求登录，非常莫名其妙。
                    //   所以登出改成【幂等】：永远返回成功，
                    //   如果带的 token 有效就顺手拉黑它，没带或无效就什么都不做。
                    //
                    //   放行它安全吗？安全。它只影响"你拿出来的那一个 token"——
                    //   想拉黑别人的 token，你得先有别人的 token，而那时候你本来就能用。
                    //   它也不返回任何信息（永远是成功）。
                    auth.requestMatchers(
                            "/auth/login",
                            "/auth/register",
                            "/auth/logout"
                    ).permitAll();

                    // 【本地存储的上传文件必须放行】
                    //   用本地磁盘存储时，/uploads/** 就是图片的访问地址，
                    //   而封面图是给【所有访客】看的（包括未登录的游客）。
                    //   不放行的话图片会返回 401，前台全变成裂图 ——
                    //   这种问题还特别隐蔽：后台上传明明成功了，前台就是不显示。
                    //   注意只放行 GET（读图片），上传接口 POST /upload 依然要求管理员。
                    auth.requestMatchers(HttpMethod.GET, "/uploads/**").permitAll();

                    // 【健康检查端点必须放行】
                    //   容器的健康检查（docker-compose.prod.yaml 里 backend 的 healthcheck）
                    //   会去请求 /actuator/health，而它是不带 token 的 ——
                    //   如果这里不放行，探针永远收到 401，容器会被判定为 unhealthy，
                    //   然后 compose 就会一直重启它。这是个"配了健康检查反而导致服务起不来"的典型坑。
                    //
                    // 【放行它安全吗】安全。它默认只返回 {"status":"UP"}，
                    //   不含数据库地址、连接串等细节（细节由
                    //   management.endpoint.health.show-details=never 控制，见 application.properties）。
                    auth.requestMatchers("/actuator/health").permitAll();

                    // 【指标端点 /actuator/prometheus：也放行，但靠"网络位置"保护它】
                    //
                    // 为什么放行：抓取指标的是 Prometheus，它没有也不该有我们的 JWT。
                    //   如果这个端点要求登录，实际结果只有两种：要么抓不到，
                    //   要么为了能抓而给它配一个长期不过期的 token —— 那比放行更糟。
                    //
                    // 那放行它安全吗？取决于谁能访问到它。这里的护栏不在代码里，而在部署上：
                    //   ① docker-compose.prod.yaml 里后端端口是【只绑到 127.0.0.1】的
                    //      （"127.0.0.1:8082:8082"），公网根本连不到这个端口；
                    //   ② Nginx 只反代 /api/ 前缀，没有 /actuator 的 location，
                    //      所以从域名访问不到它。
                    //   也就是：只有宿主机上和容器内网里能拿到它 ——
                    //   而这正是 Prometheus 所在的位置。
                    //
                    // ⚠️ 所以这两件事必须【一起】改：谁要是把 compose 里的
                    //    "127.0.0.1:" 前缀去掉、或者给 Nginx 加上 location /actuator/，
                    //    这个端点就暴露到公网了（里面能看到接口路径、调用量、
                    //    连接池占用、JVM 内存等内部信息）。
                    //    MetricsEndpointTest 只断言"端点能用"，
                    //    拦不住这种部署上的改动 —— 这一条靠 README 的核对清单兜底。
                    //
                    // 更严格的方案（不做，但要知道）：设置 management.server.port=8081，
                    //   把管理端点挪到另一个端口，主端口上根本没有 /actuator。
                    //   没采用的原因见 TECH_ROADMAP §9 M5 的 5.7 备注。
                    auth.requestMatchers("/actuator/prometheus").permitAll();

                    // 【接口文档的路径：只在文档开着的时候才放行】
                    //
                    // 这里是一个真实的安全缺口，是写 prod profile 测试时发现的：
                    //   原来不管什么环境，都把 /v3/api-docs/** 和 /swagger-ui/** 无条件放行，
                    //   同时以为"prod 里 springdoc.api-docs.enabled=false 就关掉了"。
                    //   实测发现【关不掉】：属性确实是 false，但请求 /v3/api-docs 依然返回 200 ——
                    //   也就是说文档路径依然对外可达。
                    //   对生产环境而言，Swagger 等于一份写好的接口说明书
                    //   （有哪些接口、参数叫什么、哪些要管理员），不该公开。
                    //
                    // 修法：把"是否放行文档路径"和"文档是否启用"绑在同一个开关上，
                    // 于是 prod（enabled=false）下这些路径不再被放行，
                    // 会落到最后的 anyRequest().authenticated() 上 —— 未登录访问得到 401。
                    // 一个开关同时控制"生成"和"放行"，就不会出现"以为关了其实没关"。
                    if (apiDocsEnabled) {
                        auth.requestMatchers(
                                "/v3/api-docs/**",
                                "/swagger-ui/**",
                                "/swagger-ui.html"
                        ).permitAll();
                    }

                    // 【前台公开读取接口】---------------------------------
                    // 只放行 GET！
                    // 写操作（POST/PUT/DELETE）一个都没放行 —— 它们都在
                    // /admin/article/** 下面，依然要求登录 + 管理员身份。
                    //
                    // 注意 requestMatchers 是【按顺序匹配】的，
                    // 这几条必须写在 anyRequest() 前面，否则永远轮不到它们。
                    auth.requestMatchers(HttpMethod.GET,
                            "/article/page",     // 首页信息流
                            "/article/stats",    // 首页那三个统计数字（文章数 / 浏览量 / 分类数）
                            "/article/archive",  // 归档页（按年月分组的已发布文章）
                            "/article/rss",      // RSS 数据（前端用它拼 feed.xml）
                            "/article/*",        // 文章详情 /article/123
                            "/category/list",    // 分类列表
                            "/tag/list",         // 标签列表（标签云，带每个标签下的已发布文章数）
                            // 友链列表：前台友情链接页要用。它只返回 status = 1 的那些
                            //（过滤写在 Service 的查询里，不是"由前端决定不显示"）
                            "/link/list",
                            // 项目列表：前台"我的项目"页要用。同样只返回 status = 1 的那些
                            "/project/list",
                            // 收藏列表：前台"收藏"页要用。同样只返回 status = 1 的那些
                            "/favorite/list",
                            // 关于页信息：前台关于页 / 导航栏要用。它是单条对象（不是列表），
                            // 所以路径就是 /about 本身，没有 /list 后缀
                            "/about",
                            // 音乐列表：前台「音乐」页的播放器要用。同样只返回 status = 1 的那些。
                            // ⚠️ 列表为空时返回空数组（不是 404）—— 前端会回落成内置的那一首，
                            // 所以这里放行的是"读列表"这件事，和库里有没有数据无关
                            "/music/list",
                            "/comment/list"      // 评论列表（只返回【已通过】的评论，状态在 Service 里写死）
                    ).permitAll();

                    // 【发评论单独放行，而且只放行 POST /comment】
                    //   它是全站唯一一个"游客能往数据库写内容"的入口，
                    //   所以这里刻意只写这一条路径（不用 /comment/**，
                    //   避免以后往这个模块加了管理接口却被一起放行）。
                    //   防刷靠的是接口限流 + 默认待审核，见 CommentController 的类注释。
                    auth.requestMatchers(HttpMethod.POST, "/comment").permitAll();

                    // authenticated = 必须登录（带了合法 token）才能访问
                    // 其余所有请求都要登录
                    auth.anyRequest().authenticated();
                })

                // 4. 关闭默认的"表单登录页"和"Basic认证弹窗" -----------------
                // 就是之前你被拦的那个 Please sign in 页面，关掉，不用它
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)

                // 4.5 安全响应头 -------------------------------------------------
                //
                // 【为什么一个 JSON 接口也要管这些头】
                //   这些头是给【浏览器】看的，用来约束浏览器怎么对待我们的响应。
                //   本项目虽然是前后端分离的接口服务，但同一台机器上还挂着前端页面，
                //   而且将来可能有人把接口地址直接贴进浏览器打开 ——
                //   多这几行成本几乎为零，却挡掉了几类最常见的网页攻击。
                //
                // 【Spring Security 的默认值】
                //   其实它默认已经带了下面前两项（X-Content-Type-Options / X-Frame-Options），
                //   这里显式写出来是为了"看得见"：安全配置最怕的就是
                //   "我以为它开了" —— 显式配置 + 用例断言，才不会靠猜。
                .headers(headers -> headers
                        // ① 禁止浏览器"猜"内容类型。
                        //    没有它的话，浏览器可能把一个被上传的文本文件当 HTML 解析执行，
                        //    这是上传功能最典型的一类连带风险（XSS）。
                        .contentTypeOptions(Customizer.withDefaults())

                        // ② 禁止本接口被任何页面用 iframe 嵌套。
                        //    防的是"点击劫持"：攻击者把自己的页面套在上面，
                        //    诱导用户在看不见真实界面的情况下点到某个按钮。
                        .frameOptions(frame -> frame.deny())

                        // ③ 控制 Referer（来源页地址）怎么发给别的站点。
                        //    我们的接口地址可能带查询参数（比如 ?keyword=xxx），
                        //    默认策略下这些会跟着 Referer 泄漏给第三方站点。
                        //    no-referrer 表示"一个字节都不发"，最省心。
                        .referrerPolicy(referrer -> referrer
                                .policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER))

                        // ④ HSTS：告诉浏览器"以后访问我这个域名，一律只用 HTTPS"。
                        //    防的是"降级攻击"——把用户的 HTTPS 请求劫持成 HTTP 再窃听。
                        //
                        //    ⚠️ 一个容易误解的点：浏览器【只在 HTTPS 响应上】认这个头。
                        //    本地开发是 http://localhost，所以本地看不到它 —— 这是对的，
                        //    不是配置没生效。用例里用 .secure(true) 模拟 HTTPS 请求来验证。
                        //
                        //    maxAge 设一年（31536000 秒），并包含子域名。
                        //    ⚠️ 这个值不要随便改大或加 preload：一旦浏览器记住了，
                        //    在 maxAge 到期前【无法撤销】，如果域名将来不支持 HTTPS 就彻底打不开了。
                        .httpStrictTransportSecurity(hsts -> hsts
                                .includeSubDomains(true)
                                .maxAgeInSeconds(31536000))
                )

                // 【新增】统一处理"未登录/无权限"，返回JSON而不是默认的HTML/403
                .exceptionHandling(ex -> ex
                        // 未登录 或 token无效 → 401
                        .authenticationEntryPoint((request, response, authException) -> {
                            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                            response.setContentType("application/json;charset=UTF-8");
                            response.getWriter().write("{\"code\":401,\"message\":\"未登录或token无效\"}");
                        })
                        // 已登录但权限不够 → 403
                        .accessDeniedHandler((request, response, accessDeniedException) -> {
                            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                            response.setContentType("application/json;charset=UTF-8");
                            response.getWriter().write("{\"code\":403,\"message\":\"无权限访问\"}");
                        })
                )


                // 5. 挂上我们自己的 JWT 过滤器 --------------------------------
                // addFilterBefore(过滤器, 指定位置) 表示把 JWT 过滤器
                // 插在"用户名密码认证过滤器(UsernamePasswordAuthenticationFilter)"之前，
                // 这样请求先经过我们 JWT 过滤器验 token，再去走后面的认证
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();   // 构建好过滤链并返回
    }

    /**
     * 密码加密器：BCrypt。
     * 注册用户时用它对密码加密存储；登录时用它比对密码。
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * 认证管理器：登录接口会调用 authenticate(...) 进行真正的登录认证。
     * Spring Security 会"自动"发现你项目里的 UserDetailsService（查用户）和
     * PasswordEncoder（BCrypt），组合成一个 DaoAuthenticationProvider，
     * 这里直接从 AuthenticationConfiguration 里把它取出来即可。
     */
    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration configuration) throws Exception {
        return configuration.getAuthenticationManager();
    }

    /**
     * 跨域配置：允许【配置里列出的】前端来源调用后端接口。
     *
     * 【为什么需要它】浏览器同源策略里"端口不同/域名不同"都算跨域。
     * 本地前端在 localhost:3000、线上前端在自己的域名，两者都不是后端自己的来源，
     * 不配置就会被浏览器拦掉（注意：被拦的是浏览器，用 curl 是测不出来的）。
     *
     * 【允许的来源从哪来】见上面 allowedOrigins 字段的注释。
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();

        // 把配置里的 "a,b,c" 切成列表：trim 去掉手抖多打的空格，过滤掉空串
        // （末尾多一个逗号是很常见的输入，不能因此让整个配置失效）
        List<String> origins = Arrays.stream(allowedOrigins.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
        // 【为什么用 setAllowedOriginPatterns 而不是 setAllowedOrigins】
        //   setAllowedOrigins 只接受完全精确的地址，而且和 allowCredentials(true)
        //   同时使用时会被 Spring 拒绝（安全限制）。
        //   setAllowedOriginPatterns 支持通配（例如 https://*.example.com），
        //   也能和凭证一起用 —— 这是官方推荐的做法。
        config.setAllowedOriginPatterns(origins);

        // 允许哪些 HTTP 方法
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS", "PATCH"));
        // 允许请求带哪些头（我们要带 Authorization token，所以放行所有）
        config.setAllowedHeaders(List.of("*"));
        // 允许携带凭证（这里用 token，其实可关；先开着省心）
        config.setAllowCredentials(true);
        // 【浏览器能不能读到这个响应头，取决于这里】
        // 跨域请求里，响应头默认只有少数几个（Content-Type 等）对前端可见；
        // X-Trace-Id 是自定义头，不加这一行的话，前端 fetch 里
        // response.headers.get('X-Trace-Id') 会返回 null ——
        // 头其实发出了，只是浏览器拦着不让读。这是 CORS 最容易踩的点之一。
        config.addExposedHeader("X-Trace-Id");
        // 缓存预检结果 1 小时，减少 OPTIONS 预检请求
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        // 对后端所有接口(/**)都应用这个跨域规则
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}