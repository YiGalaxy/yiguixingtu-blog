package com.yigalaxy.yiguixingtu.user;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.yigalaxy.yiguixingtu.auth.LoginUser;
import com.yigalaxy.yiguixingtu.common.Result;
import com.yigalaxy.yiguixingtu.common.ResultCode;
import com.yigalaxy.yiguixingtu.common.exception.BusinessException;
import com.yigalaxy.yiguixingtu.user.dto.ResetPasswordRequest;
import com.yigalaxy.yiguixingtu.user.dto.UserQuery;
import com.yigalaxy.yiguixingtu.user.dto.UserVO;
import com.yigalaxy.yiguixingtu.user.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

/**
 * 用户管理接口（后台专用）。
 *
 * 【权限设计】类上直接加 @PreAuthorize("hasRole('ADMIN')")，
 * 表示"这个类里所有接口都必须是管理员"，比在每个方法上重复写更干净。
 * 这依赖 SecurityConfig 上的 @EnableMethodSecurity（你已经开了）。
 *
 * 效果：
 *   游客(GUEST)带合法token调这些接口 -> 403 无权限
 *   不带token                       -> 401 未登录
 *   管理员(ADMIN)                    -> 正常返回
 */
@Slf4j
@Tag(name = "用户管理", description = "后台用户管理接口（仅管理员）")
@RestController
@RequestMapping("/user")
@PreAuthorize("hasRole('ADMIN')")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @Operation(summary = "用户分页列表")
    @GetMapping("/page")
    public Result<IPage<UserVO>> page(UserQuery query) {
        return Result.success(userService.pageUsers(query));
    }

    @Operation(summary = "启用/禁用用户")
    @PutMapping("/{id}/status")
    public Result<Void> updateStatus(@PathVariable Long id, @RequestParam Integer status) {

        // 【保护措施】不允许把自己禁用，否则管理员会把自己锁在系统外面
        Long currentId = getCurrentUserId();
        if (id.equals(currentId) && status != null && status == 0) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "不能禁用自己的账号");
        }

        userService.updateStatus(id, status);
        return Result.success();
    }

    @Operation(summary = "修改用户角色")
    @PutMapping("/{id}/role")
    public Result<Void> updateRole(@PathVariable Long id, @RequestParam String role) {

        // 【保护措施】不允许改自己的角色，同理防止把自己降级后失去后台权限
        Long currentId = getCurrentUserId();
        if (id.equals(currentId)) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "不能修改自己的角色");
        }

        userService.updateRole(id, role);
        return Result.success();
    }

    @Operation(summary = "删除用户")
    @DeleteMapping("/{id}")
    public Result<Void> remove(@PathVariable Long id) {

        // 【保护措施】不允许删除自己，防止管理员把自己删掉导致系统没人能管
        if (id.equals(getCurrentUserId())) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "不能删除自己的账号");
        }

        userService.removeUser(id);
        return Result.success();
    }

    @Operation(summary = "重置用户密码")
    @PutMapping("/{id}/password")
    public Result<Void> resetPassword(@PathVariable Long id,
                                      @Valid @RequestBody ResetPasswordRequest request) {
        userService.resetPassword(id, request.getPassword());
        return Result.success();
    }

    /**
     * 取当前登录用户的 ID。
     * 原理：JwtAuthenticationFilter 已经把 LoginUser 放进了 SecurityContext，
     * 这里取回来即可。
     *
     * 为什么放在 Controller 而不是 Service？
     * 因为"当前请求是谁在调用"是 Web 层的关注点，放这里可以避免
     * user 模块反向依赖 auth 模块，分层更干净。
     */
    private Long getCurrentUserId() {
        LoginUser loginUser = (LoginUser) SecurityContextHolder.getContext()
                .getAuthentication().getPrincipal();
        return loginUser.getUser().getId();
    }
}
