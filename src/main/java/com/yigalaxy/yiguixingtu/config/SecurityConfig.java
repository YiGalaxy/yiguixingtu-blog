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
                    auth.requestMatchers(
                            "/auth/login",
                            "/auth/register"
                    ).permitAll();

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
                            "/article/*",        // 文章详情 /article/123
                            "/category/list"     // 分类列表
                    ).permitAll();

                    // authenticated = 必须登录（带了合法 token）才能访问
                    // 其余所有请求都要登录
                    auth.anyRequest().authenticated();
                })

                // 4. 关闭默认的"表单登录页"和"Basic认证弹窗" -----------------
                // 就是之前你被拦的那个 Please sign in 页面，关掉，不用它
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)

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
        // 缓存预检结果 1 小时，减少 OPTIONS 预检请求
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        // 对后端所有接口(/**)都应用这个跨域规则
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}