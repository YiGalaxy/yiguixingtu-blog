package com.yigalaxy.yiguixingtu.auth.controller;

import com.yigalaxy.yiguixingtu.auth.LoginUser;
import com.yigalaxy.yiguixingtu.auth.cache.TokenBlacklist;
import com.yigalaxy.yiguixingtu.auth.dto.LoginRequest;
import com.yigalaxy.yiguixingtu.auth.dto.LoginVO;
import com.yigalaxy.yiguixingtu.auth.dto.RegisterRequest;
import com.yigalaxy.yiguixingtu.auth.util.JwtUtil;
import com.yigalaxy.yiguixingtu.common.Result;
import com.yigalaxy.yiguixingtu.common.metrics.BusinessMetrics;
import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import com.yigalaxy.yiguixingtu.user.entity.User;
import com.yigalaxy.yiguixingtu.user.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;

@Slf4j
@Tag(name = "认证", description = "登录/登出/当前用户等认证接口")
@RestController
@RequestMapping("/auth")
public class AuthController {

    private final AuthenticationManager authenticationManager;
    private final JwtUtil jwtUtil;
    private final UserService userService;
    private final TokenBlacklist tokenBlacklist;
    private final BusinessMetrics metrics;

    public AuthController(AuthenticationManager authenticationManager, JwtUtil jwtUtil,
                          UserService userService,
                          TokenBlacklist tokenBlacklist, BusinessMetrics metrics) {
        this.authenticationManager = authenticationManager;
        this.jwtUtil = jwtUtil;
        this.userService = userService;
        this.tokenBlacklist = tokenBlacklist;
        this.metrics = metrics;
    }

    /**
     * 用户登录：校验用户名密码，成功后签发 token
     *
     * 【限流：5 次 / 分钟（配置在 application.properties，那里写了为什么是这个数）】
     *   登录是唯一一个"可以被反复尝试、且猜对了就有收益"的公开接口 ——
     *   暴力破解的入口。加一个很小的全局配额之后，脚本爆破就失去意义了。
     *
     *   ⚠️ 这里的配额是【整站】的，不是"每个 IP 5 次"：
     *   Resilience4j 的 RateLimiter 是进程级计数器，它不知道请求来自谁。
     *   按 IP 的细粒度限制由 Nginx 的 limit_req 负责（见 README 部署章节），
     *   两层互补 —— 理由写在配置文件的注释里。
     *
     *   注解由 Resilience4j 自带的切面处理，配额用完时它抛 RequestNotPermitted，
     *   再由 GlobalExceptionHandler 翻译成 HTTP 429。**不需要写任何 @Aspect。**
     */
    @Operation(summary = "用户登录（有限流）")
    @RateLimiter(name = "loginRateLimiter")
    @PostMapping("/login")
    public Result<LoginVO> login(@Valid @RequestBody LoginRequest request) {
        // 认证（失败会自动抛异常，由全局异常处理器统一转成 Result）
        Authentication authentication = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(request.getUsername(), request.getPassword())
        );

        LoginUser loginUser = (LoginUser) authentication.getPrincipal();
        User user = loginUser.getUser();

        String token = jwtUtil.generateToken(user.getId(), user.getUsername(), user.getRole());

        LoginVO vo = new LoginVO();
        vo.setToken(token);
        vo.setId(user.getId());
        vo.setUsername(user.getUsername());
        vo.setNickname(user.getNickname());
        vo.setRole(user.getRole());

        log.info("登录成功: {}", request.getUsername());
        return Result.success(vo);
    }

    @Operation(summary = "用户注册")
    @PostMapping("/register")
    public Result<?> register(@Valid @RequestBody RegisterRequest request) {
        userService.register(request.getUsername(), request.getPassword(), request.getNickname());
        log.info("注册成功：: {}", request.getUsername());
        return Result.success();
    }

    /**
     * 退出登录。
     *
     * 【这里到底做了什么】
     *   把当前这个 token 的 jti 记进 Redis 黑名单，之后带它的请求一律 401。
     *   在此之前这个接口是空的（只打了一行日志）—— 也就是"退出登录"其实
     *   只是个假动作：前端删掉本地 token，但那个 token 依然能用到自然过期。
     *   要是它已经被人截获，或者浏览器历史里还留着，就还能用 24 小时。
     *
     * 【为什么从请求头里再取一次 token，而不是从 SecurityContext 拿】
     *   SecurityContext 里放的是"用户是谁"（LoginUser），它是过滤器解析 token 之后
     *   组装出来的，里面没有原始 token 字符串，也就拿不到 jti。
     *   而拉黑需要 jti，所以这里直接从请求头取一次。
     *
     * 【为什么只拉黑"这一个" token 而不是这个人的全部】
     *   用户可能手机和电脑同时登着，在手机上点退出不该把电脑也踢下线。
     *   要踢掉某人的全部 token 是另一种需求（管理员禁用账号），
     *   那套机制在 UserAuthCache 里。见 TokenBlacklist 的类注释。
     */
    @Operation(summary = "退出登录")
    @PostMapping("/logout")
    public Result<?> logout(
            @RequestHeader(value = "Authorization", required = false) String authorization) {

        if (authorization == null || !authorization.startsWith("Bearer ")) {
            // 走到这里说明没带 token。但 /auth/logout 在 SecurityConfig 里要求登录，
            // 所以正常情况下轮不到这个分支 —— 留着是为了防御性编程：
            // 万一将来有人把它加进 permitAll，这里也不会 NPE
            log.warn("退出登录时没有拿到 token");
            return Result.success();
        }

        String token = authorization.substring(7);
        try {
            String jti = jwtUtil.getJti(token);
            long remainingMillis = jwtUtil.getRemainingValidityMillis(token);

            // 按"剩余有效期"设置 TTL：token 自然过期的时刻，这条黑名单记录也一起消失，
            // 不需要任何定时清理任务
            tokenBlacklist.add(jti, Duration.ofMillis(remainingMillis));

            // 【为什么统计放在 add 之后】
            //   这段代码在 try 里面：拉黑失败会走 catch（token 无效），
            //   而"token 无效"本来就没有任何东西被作废。
            //   所以计数写在 add 成功之后，指标才等于"真的生效了的登出次数"。
            //   放在前面的话，一个伪造 token 反复调登出也能把这个数字刷上去，
            //   这个指标就失去了意义。
            metrics.recordTokenRevoked();

            log.info("退出登录成功, token 已作废");
        } catch (Exception e) {
            // token 解析失败（伪造的、格式错的）：那它本来就用不了，
            // 没必要让登出接口报错。用户点退出就是想退出，让他退成功
            log.warn("退出登录时解析 token 失败（token 本身可能就是无效的）: {}", e.getMessage());
        }

        return Result.success();
    }

    /**
     * 获取当前登录用户信息（需要带 token）
     *
     * 【为什么这里不再查一次数据库】
     *   JwtAuthenticationFilter 在这个请求进来时，已经把用户的完整信息
     *   （id / username / nickname / role / status）从 UserAuthCache 或数据库
     *   取出来、装进了 LoginUser。这里再 selectById 一次，就是在同一个请求里
     *   查了两遍同一行 —— 而且两次拿到的必然是同一份数据（缓存比刚才那次查库更新）。
     *
     * 【直接取那一份是安全的，理由是一致性策略，不是"应该没问题"】
     *   UserAuthCache 的策略是"写时清除"：所有会改用户字段的方法
     *   （UserServiceImpl 的 updateStatus / updateRole / resetPassword / removeUser）
     *   都会调 evict 把缓存删掉；而项目里【根本没有"改昵称"的接口】。
     *   所以缓存里的 nickname 不会滞后，那个 30 分钟的 TTL 只是"万一漏清"的兜底。
     *
     * 【password 不会被带出去】
     *   JwtAuthenticationFilter 构造 LoginUser 之前已经执行了
     *   {@code dbUser.setPassword(null)}（那行有它自己的理由：避免哈希在内存里被误用），
     *   而 LoginVO 本来也不含密码字段 —— 两条加起来，不存在"返回哈希"的可能。
     */
    @Operation(summary = "获取当前登录用户")
    @GetMapping("/me")
    public Result<LoginVO> me() {
        // 当前登录用户。它由 JwtAuthenticationFilter 放进 SecurityContext，
        // 里面那个 User 就是本请求已经取到的那一份，不需要再查库
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        LoginUser loginUser = (LoginUser) authentication.getPrincipal();
        User user = loginUser.getUser();

        LoginVO vo = new LoginVO();
        vo.setId(user.getId());
        vo.setUsername(user.getUsername());
        vo.setNickname(user.getNickname());
        vo.setRole(user.getRole());

        return Result.success(vo);
    }
}