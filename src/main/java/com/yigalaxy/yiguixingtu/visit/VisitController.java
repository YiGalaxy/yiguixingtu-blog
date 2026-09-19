package com.yigalaxy.yiguixingtu.visit;

import com.yigalaxy.yiguixingtu.auth.LoginUser;
import com.yigalaxy.yiguixingtu.common.Result;
import com.yigalaxy.yiguixingtu.visit.dto.VisitReportForm;
import com.yigalaxy.yiguixingtu.visit.service.VisitLogService;
import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * =====================================================================
 * 访问上报接口（前台公开）
 *
 * 【为什么要前端上报，而不是后端从访问日志里统计】
 *   这是个 SPA：用户点链接在站内跳转时**根本不经过后端**
 *   （页面是前端路由切换的，只可能顺带打几个接口）。
 *   所以"用户看了哪几个页面"只有前端知道 —— 后端从自己的请求日志里
 *   永远看不出这件事。浏览量计数（POST /article/{id}/view）当初也是同一个理由。
 *
 * 【它和 POST /article/{id}/view 的分工】
 *   view 接口给的是**每篇文章一个累计数字**（Redis INCR，走的是浏览量那条链路）；
 *   这里记的是**一条一条的访问明细**（直接落库），用来算曲线与构成。
 *   两者不合并：累计数字给不出曲线，明细也给不出"一眼看到的总量"。
 *
 * 【⚠️ 它写库，而且是公开的】所以有三道约束：
 *   ① 限流（visitRateLimiter）—— 防"脚本一分钟灌几万行把表写爆"
 *   ② 路径先洗干净再入库（见 VisitLogServiceImpl.normalizePath）
 *   ③ 保留期到期自动清理（见 VisitLogCleanupTask）—— 行数是有界的
 * =====================================================================
 */
@Slf4j
@Tag(name = "访问统计（前台）", description = "页面访问上报，无需登录")
@RestController
@RequestMapping("/visit")
public class VisitController {

    private final VisitLogService visitLogService;

    public VisitController(VisitLogService visitLogService) {
        this.visitLogService = visitLogService;
    }

    /**
     * 上报一次页面访问。
     *
     * 【这个接口对"失败"的态度 —— 分清两类，别混】
     *   · **运行期失败**（数据库连不上、被限流）：一律吞掉、只记日志，返回成功。
     *     统计是锦上添花的东西，记不上一条访问绝不该让用户的页面出问题 ——
     *     而前端拿到 500 也没有任何补救动作可做（重试只会再写一条）。
     *     限流被拒同理：那只是"这一分钟报得太频繁"，少记几条而已。
     *   · **参数不合法**（路径为空、超长）：仍然按全站统一约定返回 code 400。
     *     那是**调用方**的问题，与"统计失败了要不要打扰用户"不是一回事 ——
     *     而且前端只会在路径非空时才发这个请求（见 web 端的 shouldReportVisit），
     *     正常路径上根本不会出现这种请求。
     *   ⚠️ 一句话：**它不因为"统计不重要"就放行畸形输入**，
     *      但也不会因为"统计没记上"去打扰任何一个访客。
     */
    @Operation(summary = "上报页面访问（无需登录，失败静默）")
    @PostMapping
    @RateLimiter(name = "visitRateLimiter")
    public Result<Void> report(@Valid @RequestBody VisitReportForm form, HttpServletRequest request) {
        try {
            visitLogService.record(form.path(), request, currentUserOrNull());
        } catch (Exception e) {
            // 不打堆栈之外的东西：这是"少记一条统计"，不是故障
            log.warn("记录访问失败（忽略，不影响用户）: path={}, 原因={}", form.path(), e.getMessage());
        }
        return Result.success();
    }

    /** 当前登录用户；未登录时为 null（与 CommentController 里那份是同一个判断） */
    private LoginUser currentUserOrNull() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof LoginUser loginUser) {
            return loginUser;
        }
        return null;
    }
}
