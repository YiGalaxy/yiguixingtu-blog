package com.yigalaxy.yiguixingtu.auth.controller;

import com.yigalaxy.yiguixingtu.auth.LoginUser;
import com.yigalaxy.yiguixingtu.auth.cache.TokenBlacklist;
import com.yigalaxy.yiguixingtu.auth.dto.LoginRequest;
import com.yigalaxy.yiguixingtu.auth.dto.LoginVO;
import com.yigalaxy.yiguixingtu.auth.dto.RegisterRequest;
import com.yigalaxy.yiguixingtu.auth.util.JwtUtil;
import com.yigalaxy.yiguixingtu.common.Result;
import com.yigalaxy.yiguixingtu.common.metrics.BusinessMetrics;
import com.yigalaxy.yiguixingtu.user.entity.User;
import com.yigalaxy.yiguixingtu.user.mapper.UserMapper;
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
    private final UserMapper userMapper;
    private final UserService userService;
    private final TokenBlacklist tokenBlacklist;
    private final BusinessMetrics metrics;

    public AuthController(AuthenticationManager authenticationManager, JwtUtil jwtUtil,
                          UserMapper userMapper, UserService userService,
                          TokenBlacklist tokenBlacklist, BusinessMetrics metrics) {
        this.authenticationManager = authenticationManager;
        this.jwtUtil = jwtUtil;
        this.userMapper = userMapper;
        this.userService = userService;
        this.tokenBlacklist = tokenBlacklist;
        this.metrics = metrics;
    }

    /**
     * 用户登录：校验用户名密码，成功后签发 token
     */
    @Operation(summary = "用户登录")
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
     */
    @Operation(summary = "获取当前登录用户")
    @GetMapping("/me")
    public Result<LoginVO> me() {
        // 1. 当前登录用户（由 JwtAuthenticationFilter 放进 SecurityContext）
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        LoginUser loginUser = (LoginUser) authentication.getPrincipal();

        // 2. 从数据库查完整用户信息（token 里没存昵称等）
        User user = userMapper.selectById(loginUser.getUser().getId());

        // 3. 组装返回
        LoginVO vo = new LoginVO();
        vo.setId(user.getId());
        vo.setUsername(user.getUsername());
        vo.setNickname(user.getNickname());
        vo.setRole(user.getRole());

        return Result.success(vo);
    }
}