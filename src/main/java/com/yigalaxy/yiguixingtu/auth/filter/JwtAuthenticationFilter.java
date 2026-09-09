package com.yigalaxy.yiguixingtu.auth.filter;

import com.yigalaxy.yiguixingtu.auth.LoginUser;
import com.yigalaxy.yiguixingtu.auth.util.JwtUtil;
import com.yigalaxy.yiguixingtu.user.entity.User;
import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * =====================================================================
 * 【这是什么？】JWT 认证过滤器。
 *
 * 先搞清楚"过滤器(Filter)"是什么：
 * 你的后端有很多请求进来，每个请求都要经过一串"检查站"（过滤器链 FilterChain）。
 * 我们这个过滤器就是其中一个检查站，专门负责"看这个请求有没有带合法的 token"。
 *
 * 【它做三件事】
 *   1. 从请求头取出 token
 *   2. 解析 token，如果合法，就把"当前登录用户"记到 SecurityContext（一个登记簿）里
 *   3. 放行，让请求继续往下走
 *
 * 【OncePerRequestFilter】表示"每个请求只经过一次"，防止某些情况重复执行。
 * =====================================================================
 */
@Slf4j
@Component   // 交给 Spring 管理，能被注入到别的地方
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    /** JWT 工具类：负责解析 token。由 Spring 注入进来。 */
    private final JwtUtil jwtUtil;

    /** 构造方法：Spring 创建这个过滤器时，把 JwtUtil 传进来。 */
    public JwtAuthenticationFilter(JwtUtil jwtUtil) {
        this.jwtUtil = jwtUtil;
    }

    /**
     * 核心方法：每个请求都会执行这里。
     * 参数说明：
     *   request  - 当前这个 HTTP 请求（能取请求头、参数等）
     *   response - 当前的响应对象（能写状态码、返回内容等）
     *   filterChain - 剩下的过滤器链（调用它就表示"放行，往下走"）
     */
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        // ============ 第1步：取 token ============
        // 前端通常这样带 token：Authorization: Bearer eyJhbGciOiJIUzI1NiJ9...
        // getHeader("Authorization") 就是取请求头里这一行
        String header = request.getHeader("Authorization");

        // 如果请求头为空，或者不是以 "Bearer " 开头（说明没带 token 或格式不对）
        if (header == null || !header.startsWith("Bearer ")) {
            // 不带 token 我们不做任何事，直接放行。
            // 注意：这只是"放行"，不代表允许访问！
            // 真正决定"要不要拦"的是 SecurityConfig 里的 .anyRequest().authenticated()
            filterChain.doFilter(request, response);
            return;   // 结束当前方法
        }

        // 去掉 "Bearer " 这 7 个字符，拿到真正的 token 字符串
        // "Bearer ".length() 正好是 7，所以从下标 7 开始截取
        String token = header.substring(7);

        try {
            // ============ 第2步：解析 token ============
            // parseToken 会做两件事：① 验签名（有没有被篡改）② 验过期时间
            // 如果签名不对或已过期，这里会抛异常，跳到下面的 catch
            Claims claims = jwtUtil.parseToken(token);

            // ============ 第3步：从 token 里取出用户信息 ============
            // claims 就是 token 里存的"数据"，即登录时放进去的 userId/role/username
            Long userId = ((Number) claims.get("userId")).longValue();   // 取 userId（转成 Long）
            String username = claims.getSubject();                       // 取用户名（我们存在 subject 字段）
            String role = (String) claims.get("role");                   // 取角色

            // ============ 第4步：组装一个"已登录用户" ============
            // 构造一个 User 对象，只填需要的字段。这里不需要密码，因为 token 已经证明身份了
            User user = new User();
            user.setId(userId);
            user.setUsername(username);
            user.setRole(role);

            // 用 LoginUser 把 User 包起来（LoginUser 实现了 Spring Security 的 UserDetails）
            LoginUser loginUser = new LoginUser(user);

            // ============ 第5步：构造"已认证"令牌 ============
            // UsernamePasswordAuthenticationToken 是"认证信息"的容器。
            // 3个参数：(当前登录用户, 密码凭证, 权限列表)
            //   - 第二个参数传 null，因为 token 已验证过，不需要密码
            //   - 第三个参数是权限（loginUser.getAuthorities() 返回 ROLE_ADMIN 等）
            // 用3参构造器 = 表示"已认证"（authenticated=true），这是合法的登录状态
            UsernamePasswordAuthenticationToken authentication =
                    new UsernamePasswordAuthenticationToken(loginUser, null, loginUser.getAuthorities());

            // setDetails：记录一些请求细节（比如来源IP等），通常这样写即可
            authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));

            // ============ 第6步：放入"登记簿" ============
            // SecurityContextHolder 相当于一个"当前请求的登记簿"，
            // 把 authentication 放进去，就表示"这个请求已经登录了，当前用户是 loginUser"
            // 之后在 Controller 里就能通过 SecurityContextHolder 拿到当前用户
            SecurityContextHolder.getContext().setAuthentication(authentication);

        } catch (Exception e) {
            // token 无效/过期，走到这里。我们不做设置认证，
            // 后续 SecurityConfig 的 .anyRequest().authenticated() 会拦下来返回 401
            log.warn("解析token失败: {}", e.getMessage());
        }

        // ============ 第7步：放行 ============
        // 把请求交给下一个过滤器/最终到 Controller
        filterChain.doFilter(request, response);
    }
}