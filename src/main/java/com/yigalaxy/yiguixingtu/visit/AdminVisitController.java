package com.yigalaxy.yiguixingtu.visit;

import com.yigalaxy.yiguixingtu.common.Result;
import com.yigalaxy.yiguixingtu.visit.dto.VisitStatsVO;
import com.yigalaxy.yiguixingtu.visit.dto.VisitorIpVO;
import com.yigalaxy.yiguixingtu.visit.service.VisitLogService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

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
 * 【为什么画图只有一个接口，而访客 IP 另开一个】页面上所有的图（曲线、三个饼图、
 *   两张排行）都是同一份数据的不同切面，一次全给比让前端打六次接口更省事、
 *   也不会出现"曲线是按 7 天算的、饼图是按 30 天算的"这种口径不一致。
 *   而访客 IP 列表**不在这份数据里**：它是点开才看的，且带着完整 IP（个人信息）——
 *   合并进来的话，每次打开概览都会把访客 IP 传到浏览器，哪怕没人点开。
 *   两者的 days 仍然共用同一套区间计算，口径不会分叉。
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

    /**
     * 访客 IP 列表（统计页下钻出来的那一层）。
     *
     * 【为什么单独一个接口，而不是塞进上面的报表里】
     *   上面的报表是**打开页面就要画**的东西；这份列表是**点开才看**的，
     *   而且带着完整 IP（全是个人信息）。合并之后每一次打开后台概览都会
     *   把访客 IP 一起传到浏览器里，哪怕根本没人点开它 —— 让数据的传输
     *   范围与实际使用范围对齐，是一条不需要额外成本就能守住的边界。
     *
     * 【days 的口径与上面的报表完全一致】两者共用 VisitLogService 里同一个
     *   区间计算（sinceOf），所以"曲线说这段时间有 12 个访客、列表里却有 15 行"
     *   这种对不上的情况不会出现。
     *
     * @param days 看最近多少天（含今天），默认 30；超出范围会被夹到 [1, 90]（同上面的报表）
     */
    @Operation(summary = "访客 IP 列表（按 IP 聚合，含归属地）")
    @GetMapping("/ips")
    public Result<List<VisitorIpVO>> visitorIps(
            @RequestParam(value = "days", required = false, defaultValue = "30") int days) {
        return Result.success(visitLogService.visitorIps(days));
    }
}
