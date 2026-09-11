package com.yigalaxy.yiguixingtu.link;

import com.yigalaxy.yiguixingtu.common.Result;
import com.yigalaxy.yiguixingtu.link.dto.FriendLinkForm;
import com.yigalaxy.yiguixingtu.link.dto.FriendLinkVO;
import com.yigalaxy.yiguixingtu.link.service.FriendLinkService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 友链管理接口（后台，仅管理员）。
 *
 * 【权限写法与其它后台接口完全一致】类上直接
 * {@code @PreAuthorize("hasRole('ADMIN')")}，好处是以后往这个类里加方法不会漏加注解。
 * 三种身份的表现：
 *   不带 token       → 401（过滤器层拦住，不知道你是谁）
 *   游客带合法 token  → 403（知道你是谁，但你不能干这个）
 *   管理员           → 正常返回
 *
 * 【为什么路径是 /admin/link 而不是 /link】
 *   前台已经有 GET /link/list（公开）。写操作放到 /admin/ 前缀下，
 *   一眼就能看出"这是后台接口"；SecurityConfig 里也不用为它单独写规则
 *   （anyRequest().authenticated() 已经覆盖，"必须是管理员"靠 @PreAuthorize）。
 */
@Slf4j
@Tag(name = "友链（后台）", description = "友链的增删改查，仅管理员")
@RestController
@RequestMapping("/admin/link")
@PreAuthorize("hasRole('ADMIN')")
public class AdminLinkController {

    private final FriendLinkService friendLinkService;

    public AdminLinkController(FriendLinkService friendLinkService) {
        this.friendLinkService = friendLinkService;
    }

    /**
     * 友链列表（后台，含隐藏的）。
     * 【它和前台 /link/list 的区别】多返回 status = 0 的那些，而且【不走缓存】——
     * 管理员刚点完"隐藏"就该立刻看到，缓存只会带来"是不是没保存成功"的疑惑。
     */
    @Operation(summary = "友链列表（后台，含隐藏，不走缓存）")
    @GetMapping("/list")
    public Result<List<FriendLinkVO>> list() {
        return Result.success(friendLinkService.listAll());
    }

    @Operation(summary = "新建友链")
    @PostMapping
    public Result<Long> create(@Valid @RequestBody FriendLinkForm form) {
        return Result.success(friendLinkService.create(form));
    }

    @Operation(summary = "编辑友链")
    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable Long id, @Valid @RequestBody FriendLinkForm form) {
        friendLinkService.update(id, form);
        return Result.success();
    }

    /**
     * 删除友链（逻辑删除）。
     * 【为什么不返回"删掉了哪一条"】站点名快照已经留在审计记录（DELETE_LINK）里，
     * 接口多返回一个字段只会让前端多一个不知道怎么用的东西。
     */
    @Operation(summary = "删除友链（逻辑删除）")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        friendLinkService.delete(id);
        return Result.success();
    }
}
