package com.yigalaxy.yiguixingtu.project;

import com.yigalaxy.yiguixingtu.common.Result;
import com.yigalaxy.yiguixingtu.project.dto.ProjectVO;
import com.yigalaxy.yiguixingtu.project.service.ProjectService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 项目接口（前台公开）。
 *
 * 【类上没有 @PreAuthorize，SecurityConfig 里放行了 GET /project/list】
 *   和 TagController / CategoryController / LinkController 同一个位置、同一个写法。
 *   后台的增删改在 {@link AdminProjectController} 里（类级 ADMIN）。
 *
 * 【为什么不分页】
 *   个人博客的"我的项目"是十几个的量级，一页放得下；
 *   加一个分页参数只会让前端多一层无用逻辑，而分组/筛选的需求也不存在。
 *   等真的有几十个项目时再加（那时接口形状变了，但那次改动是值得的）。
 */
@Slf4j
@Tag(name = "项目（前台）", description = "项目展示列表，无需登录")
@RestController
@RequestMapping("/project")
public class ProjectController {

    private final ProjectService projectService;

    public ProjectController(ProjectService projectService) {
        this.projectService = projectService;
    }

    /**
     * 项目列表（只含"显示"的，按 sort 升序）。
     *
     * 【为什么"只含显示"必须在 SQL 里过滤】见 LinkController 里同一处的说明：
     * 后台把项目设成隐藏就是想让它立刻从前台消失，
     * 而不是"接口还返回着、只是页面没渲染"。
     */
    @Operation(summary = "项目列表（含显示中的，无需登录）")
    @GetMapping("/list")
    public Result<List<ProjectVO>> list() {
        return Result.success(projectService.listVisible());
    }
}
