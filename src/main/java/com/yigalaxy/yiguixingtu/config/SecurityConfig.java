package com.yigalaxy.yiguixingtu.config;

import com.yigalaxy.yiguixingtu.auth.filter.JwtAuthenticationFilter;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
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

                // 2. 设置会话为"无状态"（STATELESS）-------------------------
                // 意思是：不用 session 记录登录态，全部靠 token。
                // 这是 JWT 的典型做法。
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))

                // 3. 配置"路径权限规则" --------------------------------------
                .authorizeHttpRequests(auth -> auth
                        // permitAll = 允许所有人访问，不需要登录
                        // 放行：登录接口 + 接口文档（swagger）
                        .requestMatchers("/auth/login", "/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()

                        // authenticated = 必须登录（带了合法 token）才能访问
                        // 其余所有请求都要登录
                        .anyRequest().authenticated()
                )

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
}