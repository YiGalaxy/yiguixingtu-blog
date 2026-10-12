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

/** Stateless JWT security rules with explicit public routes. */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;

    // Comma-separated CORS origin patterns supplied by deployment configuration.
    @Value("${app.cors.allowed-origins}")
    private String allowedOrigins;

    // Keep Swagger route access tied to the same switch that enables documentation.
    @Value("${springdoc.api-docs.enabled:false}")
    private boolean apiDocsEnabled;

    public SecurityConfig(JwtAuthenticationFilter jwtAuthenticationFilter) {
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                // Header-based JWT authentication is stateless.
                .csrf(AbstractHttpConfigurer::disable)
                .cors(Customizer.withDefaults())
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))

                .authorizeHttpRequests(auth -> {
                    // Logout remains public so repeated logout requests are idempotent.
                    auth.requestMatchers(
                            "/auth/login",
                            "/auth/register",
                            "/auth/logout"
                    ).permitAll();

                    // Public files allow GET and HEAD only; uploads still require authentication.
                    auth.requestMatchers(HttpMethod.GET, "/uploads/**").permitAll();
                    auth.requestMatchers(HttpMethod.HEAD, "/uploads/**").permitAll();

                    auth.requestMatchers("/actuator/health").permitAll();

                    // Deployment must bind the backend to loopback and deny /api/actuator/ in Nginx.
                    auth.requestMatchers("/actuator/prometheus").permitAll();

                    // Disabled documentation routes fall through to authentication.
                    if (apiDocsEnabled) {
                        auth.requestMatchers(
                                "/v3/api-docs/**",
                                "/swagger-ui/**",
                                "/swagger-ui.html"
                        ).permitAll();
                    }

                    // Only these read routes are public; later routes require authentication.
                    auth.requestMatchers(HttpMethod.GET,
                            "/article/page",
                            "/article/stats",
                            "/article/archive",
                            "/article/rss",
                            "/article/*",
                            "/category/list",
                            "/tag/list",
                            "/link/list",
                            "/project/list",
                            "/favorite/list",
                            "/about",
                            "/setting",
                            "/music/list",
                            "/comment/list"
                    ).permitAll();

                    // Keep anonymous write routes exact so future admin paths cannot become public.
                    auth.requestMatchers(HttpMethod.POST, "/comment").permitAll();

                    auth.requestMatchers(HttpMethod.POST, "/article/*/view").permitAll();

                    auth.requestMatchers(HttpMethod.POST, "/visit").permitAll();

                    auth.anyRequest().authenticated();
                })

                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)

                .headers(headers -> headers
                        .contentTypeOptions(Customizer.withDefaults())

                        .frameOptions(frame -> frame.deny())

                        .referrerPolicy(referrer -> referrer
                                .policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER))

                        // HSTS is sent for HTTPS; all covered subdomains must support HTTPS.
                        .httpStrictTransportSecurity(hsts -> hsts
                                .includeSubDomains(true)
                                .maxAgeInSeconds(31536000))
                )

                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint((request, response, authException) -> {
                            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                            response.setContentType("application/json;charset=UTF-8");
                            response.getWriter().write("{\"code\":401,\"message\":\"未登录或token无效\"}");
                        })
                        .accessDeniedHandler((request, response, accessDeniedException) -> {
                            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                            response.setContentType("application/json;charset=UTF-8");
                            response.getWriter().write("{\"code\":403,\"message\":\"无权限访问\"}");
                        })
                )

                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration configuration) throws Exception {
        return configuration.getAuthenticationManager();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();

        List<String> origins = Arrays.stream(allowedOrigins.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
        // Patterns permit configured subdomains while credentials are allowed.
        config.setAllowedOriginPatterns(origins);

        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS", "PATCH"));
        config.setAllowedHeaders(List.of("*"));
        config.setAllowCredentials(true);
        // Browser clients need explicit access to this response header.
        config.addExposedHeader("X-Trace-Id");
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
