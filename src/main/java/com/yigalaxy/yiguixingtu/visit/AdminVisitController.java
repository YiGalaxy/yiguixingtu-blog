package com.yigalaxy.yiguixingtu.visit;

import com.yigalaxy.yiguixingtu.common.Result;
import com.yigalaxy.yiguixingtu.visit.dto.VisitStatsVO;
import com.yigalaxy.yiguixingtu.visit.service.VisitLogService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * =====================================================================
 * 后台访问统计接口（仅管理员）
 *
 * 【为什么路径是 /admin/stats/visits 而不是 /admin/visit】
 *   按"这个接口产出的是什么"命名：它产出的是**统计报表**，不是某条访问记录。
 *   后台菜单里它挂在「概览」下（概览本来就是"这个站现在怎么样"那一页），
 *   路径与菜单不必一一对应 —— 与 /admin/upload/storage 那种"管这个模块的数据"
 *   是两类东西。
 *
 * 【为什么只有一个接口】页面上所有的图（曲线、三个饼图、两张排行）都是
 *   同一份数据的不同切面，一次全给比让前端打六次接口更省事、也不会出现
 *   "曲线是按 7 天算的、饼图是按 30 天算的"这种口径不一致。
 * =====================================================================
 */
@Tag(name = "访问统计", description = "后台访问统计（仅管理员）")
@RestController
@RequestMapping("/admin/stats/visits")
@PreAuthorize("hasRole('ADMIN')")
public class AdminVisitController {

    private final VisitLogService visitLogService;

    public AdminVisitController(VisitLogService visitLogService) {
        this.visitLogService = visitLogService;
    }

    /**
     * 访问统计报表。
     *
     * @param days 看最近多少天（含今天），默认 30；超出范围会被夹到 [1, 90]，
     *             ⚠️ 不是报错 —— 前端传 365 时给它"最多 90 天"的数据，
     *             比返回一个 400 让整页空白要好（90 是保留期能覆盖的上限，
     *             再往前的数据已经按保留期删掉了）
     */
    @Operation(summary = "访问统计（曲线 + 构成 + 排行）")
    @GetMapping
    public Result<VisitStatsVO> stats(@RequestParam(value = "days", required = false, defaultValue = "30") int days) {
        return Result.success(visitLogService.stats(days));
    }
}
