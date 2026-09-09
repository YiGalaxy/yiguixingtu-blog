package com.yigalaxy.yiguixingtu.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Spring Security 配置（开发阶段的临时版）
 * 暂时屏蔽默认登录拦截，放行所有请求；后续接入JWT时再改成真正的认证规则
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                // 关CSRF（无状态接口用不到）
                .csrf(AbstractHttpConfigurer::disable)
                // 暂时放行所有请求——屏蔽默认拦截
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                // 关掉默认表单登录页（之前你被拦的那个页面）
                .formLogin(AbstractHttpConfigurer::disable)
                // 关掉默认HTTP Basic弹窗
                .httpBasic(AbstractHttpConfigurer::disable);

        return http.build();
    }

    /**
     * 密码加密器：BCrypt，以后登录校验密码用
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
