package com.yigalaxy.yiguixingtu.auth.controller;

import com.yigalaxy.yiguixingtu.auth.LoginUser;
import com.yigalaxy.yiguixingtu.auth.dto.LoginRequest;
import com.yigalaxy.yiguixingtu.auth.dto.LoginVO;
import com.yigalaxy.yiguixingtu.auth.util.JwtUtil;
import com.yigalaxy.yiguixingtu.common.Result;
import com.yigalaxy.yiguixingtu.common.ResultCode;
import com.yigalaxy.yiguixingtu.user.entity.User;
import com.yigalaxy.yiguixingtu.user.mapper.UserMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@Tag(name = "认证", description = "登录/登出/当前用户等认证接口")
@RestController
@RequestMapping("/auth")
public class AuthController {

    private final AuthenticationManager authenticationManager;
    private final JwtUtil jwtUtil;
    private final UserMapper userMapper;

    public AuthController(AuthenticationManager authenticationManager, JwtUtil jwtUtil, UserMapper userMapper) {
        this.authenticationManager = authenticationManager;
        this.jwtUtil = jwtUtil;
        this.userMapper = userMapper;
    }

    /**
     * 用户登录：校验用户名密码，成功后签发 token
     */
    @Operation(summary = "用户登录")
    @PostMapping("/login")
    public Result<LoginVO> login(@Valid @RequestBody LoginRequest request) {

        try {
            // 1. 认证：Spring Security 会自动调 UserDetailsService 查用户 + BCrypt 比对密码
            Authentication authentication = authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(request.getUsername(), request.getPassword())
            );

            // 2. 认证成功，取当前登录用户
            LoginUser loginUser = (LoginUser) authentication.getPrincipal();
            User user = loginUser.getUser();

            // 3. 用登录信息签发 token
            String token = jwtUtil.generateToken(user.getId(), user.getUsername(), user.getRole());

            // 4. 组装返回
            LoginVO vo = new LoginVO();
            vo.setToken(token);
            vo.setId(user.getId());
            vo.setUsername(user.getUsername());
            vo.setNickname(user.getNickname());
            vo.setRole(user.getRole());

            log.info("登录成功: {}", request.getUsername());
            return Result.success(vo);

        } catch (AuthenticationException e) {
            // 用户名/密码错误、账号被禁用 等都会走到这里
            log.warn("登录失败: {} 原因: {}", request.getUsername(), e.getMessage());
            return Result.error(ResultCode.BAD_CREDENTIALS);
        }
    }

    /**
     * 退出登录（JWT 无状态，前端删掉 token 即可）
     */
    @Operation(summary = "退出登录")
    @PostMapping("/logout")
    public Result<?> logout() {
        log.info("退出登录");
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