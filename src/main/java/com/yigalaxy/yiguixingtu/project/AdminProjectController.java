package com.yigalaxy.yiguixingtu.project;

import com.yigalaxy.yiguixingtu.common.Result;
import com.yigalaxy.yiguixingtu.project.dto.ProjectForm;
import com.yigalaxy.yiguixingtu.project.dto.ProjectVO;
import com.yigalaxy.yiguixingtu.project.service.ProjectService;
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
 * 项目管理接口（后台，仅管理员）。
 *
 * 【权限写法与其它后台接口完全一致】类级 {@code @PreAuthorize("hasRole('ADMIN')")}。
 * 三种身份的表现：无 token → 401，GUEST + 合法 token → 403，ADMIN → 正常返回。
 *
 * 【创建与更新都用了 @Valid：校验失败由 GlobalExceptionHandler 统一转成
 *  HTTP 200 + body.code 400（业务错误不走 HTTP 状态码，见 README「统一返回与错误处理」）】
 */
@Slf4j
@Tag(name = "项目（后台）", description = "项目的增删改查，仅管理员")
@RestController
@RequestMapping("/admin/project")
@PreAuthorize("hasRole('ADMIN')")
public class AdminProjectController {

    private final ProjectService projectService;

    public AdminProjectController(ProjectService projectService) {
        this.projectService = projectService;
    }

    /**
     * 项目列表（后台，含隐藏的，不走缓存）。
     * 【为什么不和前台共用带缓存的那个方法】管理员刚点完"隐藏"就要看到效果；
     * 缓存只会带来"是不是没保存成功"的疑惑（和标签 / 友链的后台列表同一个决定）。
     */
    @Operation(summary = "项目列表（后台，含隐藏，不走缓存）")
    @GetMapping("/list")
    public Result<List<ProjectVO>> list() {
        return Result.success(projectService.listAll());
    }

    @Operation(summary = "新建项目")
    @PostMapping
    public Result<Long> create(@Valid @RequestBody ProjectForm form) {
        return Result.success(projectService.create(form));
    }

    @Operation(summary = "编辑项目")
    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable Long id, @Valid @RequestBody ProjectForm form) {
        projectService.update(id, form);
        return Result.success();
    }

    @Operation(summary = "删除项目（逻辑删除）")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        projectService.delete(id);
        return Result.success();
    }
}
