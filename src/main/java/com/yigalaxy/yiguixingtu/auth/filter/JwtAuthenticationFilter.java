package com.yigalaxy.yiguixingtu.auth.filter;

import com.yigalaxy.yiguixingtu.auth.LoginUser;
import com.yigalaxy.yiguixingtu.auth.cache.TokenBlacklist;
import com.yigalaxy.yiguixingtu.auth.cache.UserAuthCache;
import com.yigalaxy.yiguixingtu.auth.util.JwtUtil;
import com.yigalaxy.yiguixingtu.user.entity.User;
import com.yigalaxy.yiguixingtu.user.mapper.UserMapper;
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

    /** 用户 Mapper：用来查数据库，确认用户是否还存在 / 是否被禁用 */
    private final UserMapper userMapper;

    /** 用户认证信息缓存：先查它，命中就不用查库了 */
    private final UserAuthCache userAuthCache;

    /** 已登出 token 的黑名单：用来让"退出登录"真的即时生效（见第 3.5 步） */
    private final TokenBlacklist tokenBlacklist;

    /** 构造方法：Spring 创建这个过滤器时，把依赖一起传进来。 */
    public JwtAuthenticationFilter(JwtUtil jwtUtil, UserMapper userMapper,
                                   UserAuthCache userAuthCache, TokenBlacklist tokenBlacklist) {
        this.jwtUtil = jwtUtil;
        this.userMapper = userMapper;
        this.userAuthCache = userAuthCache;
        this.tokenBlacklist = tokenBlacklist;


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

        // ============ 第2步：解析 token（纯本地校验，不碰任何外部资源）============
        Claims claims;
        Long userId;
        try {
            // parseToken 会做两件事：① 验签名（有没有被篡改）② 验过期时间
            // 如果签名不对或已过期，这里会抛异常，跳到下面这个 catch
            claims = jwtUtil.parseToken(token);

            // ============ 第3步：从 token 里取出用户 ID ============
            userId = ((Number) claims.get("userId")).longValue();

        } catch (Exception e) {
            // 【这一类失败是预期内的】签名不对、已过期、格式不对、或者压根不是我们签的。
            // 客户端拿着过期 token 来访问是正常现象，所以只记一行 warn、不打堆栈。
            // 也不在这里写响应 —— 让 Security 的异常处理入口统一产出 401 的 JSON，
            // 格式才和别处一致（和下面黑名单分支是同一个理由）
            log.warn("解析 token 失败（无效 / 已过期 / 格式不对）: {}", e.getMessage());
            filterChain.doFilter(request, response);
            return;
        }

        // ============ 第3.5步 ~ 第7步：以下每一步都要访问 Redis 或 MySQL ============
        // 【为什么从这里开始要单独一个 try，不和上面合并】
        //   前后两段的失败原因分属两个完全不同的方向：
        //     上面那段失败 = "token 有问题"    → 查 JWT 密钥、有效期、客户端拿的是什么
        //     这里这段失败 = "基础设施有问题"  → 查 Redis / MySQL / 网络
        //   混在同一个 catch 里、又只打 message 不打堆栈的后果（这正是改动前的情况）：
        //   Redis 抖动时会写出 "解析token失败: Connection refused" ——
        //   看日志的人会先去怀疑 token 和密钥，而真正的异常连堆栈都没留下。
        try {
            // ============ 第3.5步：这个 token 是否已被登出拉黑 ============
            // 【为什么只查 UserAuthCache 不够】
            //   上面那套机制解决的是"用户的身份/权限变了"（被禁用、被删除、被降级），
            //   它管不了"用户主动退出登录"。因为退出时用户本身没有任何变化 ——
            //   账号还在、角色还在、状态还是正常的，只是这个 token 不该再被认。
            //   所以要把登出过的 token 单独拉黑，这里查一次。
            //
            // 【代价】每个带 token 的请求多一次 Redis 查询（hasKey）。
            //   这是"能撤销 token"必须付的代价 —— 无状态的 JWT 本身做不到撤销。
            //   要省这一次查询就只能改用"有状态 session"，那是另一个方向的取舍。
            if (tokenBlacklist.contains(claims.getId())) {
                log.warn("token 已登出（在黑名单中），拒绝本次请求, userId={}, jti={}",
                        userId, claims.getId());
                // 不设置认证信息 → 后续 anyRequest().authenticated() 会返回 401。
                // 注意这里【不抛异常、也不直接写响应】：
                // 让 Security 的异常处理入口统一产出 401 的 JSON，格式才和别处一致
                filterChain.doFilter(request, response);
                return;
            }

            // ============ 第4步【核心修复】：拿到这个人的"当前"状态 ============
            // 【为什么必须校验？】token 一旦签发就无法撤销，光看 token 看不出：
            //   ① 用户是否已被【禁用】(status = 0)
            //   ② 用户是否已被【删除】(逻辑删除)
            //   ③ 用户角色是否已被【修改】（token 里存的是签发那一刻的旧角色）
            // 不校验的后果：被禁用/被删除的人拿着旧 token 依然畅通无阻，
            //              直到 token 自然过期（你配的是 24 小时）。
            //
            // 【取数顺序：先缓存、后数据库】
            //   · 命中缓存 -> 0 次数据库查询（绝大多数请求走这条）
            //   · 未命中   -> 查一次库，把结果写回缓存，供后续请求使用
            //
            // 缓存里存的是 id/username/nickname/role/status，【不含密码】。
            // 禁用、删除、改角色时 UserServiceImpl 会主动清掉这个缓存，
            // 所以这里读到的一定是最新状态（TTL 30 分钟只是兜底）。
            User dbUser = userAuthCache.get(userId);

            if (dbUser == null) {
                // 缓存没命中才查库。
                //
                // 【注意】selectById 是 MyBatis-Plus 的方法，User 实体上有 @TableLogic，
                //        它会自动追加 "AND deleted = 0" —— 已删除的用户查出来就是 null。
                dbUser = userMapper.selectById(userId);

                if (dbUser != null) {
                    // 查到就写回缓存，下次请求就不用查库了
                    userAuthCache.put(dbUser);
                }
            }

            if (dbUser == null) {
                // 情况①：用户已被删除
                log.warn("token 对应用户不存在或已删除, userId={}", userId);
            } else if (dbUser.getStatus() == null || dbUser.getStatus() != 1) {
                // 情况②：用户已被禁用
                log.warn("用户已被禁用, userId={}, username={}", userId, dbUser.getUsername());
            } else {
                // ============ 第5步：用【数据库里的最新信息】组装当前登录用户 ============
                // 关键：这里不再使用 token 里的 role，而是用 dbUser.getRole()。
                // 好处是"刚被降级的管理员"立刻失去权限，不用等 token 过期；
                // "刚被提升的游客"也立刻获得权限。
                //
                // 安全习惯：密码哈希后面用不到了，先清掉，
                // 避免它跟着 principal 在内存里被误用、或被日志打出来。
                dbUser.setPassword(null);
                LoginUser loginUser = new LoginUser(dbUser);

                // ============ 第6步：构造"已认证"令牌 ============
                // 3个参数：(当前登录用户, 密码凭证, 权限列表)
                // 第二个参数传 null，因为 token 已验证过，不需要密码
                UsernamePasswordAuthenticationToken authentication =
                        new UsernamePasswordAuthenticationToken(loginUser, null, loginUser.getAuthorities());

                // setDetails：记录请求细节（来源 IP 等）
                authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));

                // ============ 第7步：放入"登记簿" ============
                SecurityContextHolder.getContext().setAuthentication(authentication);
            }

        } catch (Exception e) {
            // 【这是基础设施故障，不是 token 的问题】Redis / MySQL 挂了、超时、或者连接被拒。
            // 打 error 级别并带上完整堆栈 —— "什么异常、打在哪一步"是排查的起点，
            // 只留一句 message 的话，连是连接被拒还是读超时都分不出来。
            // 同样不做认证设置：SecurityConfig 的 .anyRequest().authenticated()
            // 会把请求拦下来返回 401。这是有意取向 ——
            // 宁可让用户重新登录一次，也不要因为基础设施抖动就放行一个未经校验的请求。
            log.error("认证过程中访问缓存/数据库失败（与 token 无关），本次按未认证处理, userId={}", userId, e);
        }

        // ============ 第8步：放行 ============
        // 把请求交给下一个过滤器/最终到 Controller
        filterChain.doFilter(request, response);
    }
}