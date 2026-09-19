package com.yigalaxy.yiguixingtu.comment;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.yigalaxy.yiguixingtu.comment.dto.CommentForm;
import com.yigalaxy.yiguixingtu.comment.dto.CommentQuery;
import com.yigalaxy.yiguixingtu.comment.dto.CommentVO;
import com.yigalaxy.yiguixingtu.comment.service.CommentService;
import com.yigalaxy.yiguixingtu.auth.LoginUser;
import com.yigalaxy.yiguixingtu.common.Result;
import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 评论接口（前台公开）。
 *
 * 【为什么这个类上【没有】@PreAuthorize】
 *   读评论和发评论都应当允许游客 —— 博客的评论区不该要求注册（理由写在 CommentForm 里）。
 *   代价是它成了全站唯一一个"任何人都能往数据库写东西"的入口，
 *   所以三道闸都压在它上面：
 *     ① 评论默认【待审核】，不通过就不对外可见（最主要的一道）
 *     ② 发表接口加了 @RateLimiter（见 application.properties 的 commentRateLimiter）
 *     ③ 内容/昵称长度上限 + 入库前 HTML 转义
 *   安全管理靠的是这三层叠加，而不是"要求登录"这一条。
 *
 * 【2026-09 起：登录了就用账号身份，管理员免审】
 *   · 游客：昵称自己填，评论进待审核队列
 *   · 注册用户：昵称取账号昵称（表单里那个忽略），同样进待审核
 *   · 管理员：同上，但**直接通过、前台立刻可见** —— 在自己的站上回复读者
 *     还要去后台点一次"通过"纯属多余（完整推导见 CommentServiceImpl.create 第 3 步）
 *   三种身份都会记下账号 id（游客为 null）：昵称只是一个谁都能填的字符串，
 *   真正能回答"这是谁发的"的只有那一列。
 */
@Slf4j
@Tag(name = "评论（前台）", description = "看评论、发评论，无需登录")
@RestController
@RequestMapping("/comment")
public class CommentController {

    private final CommentService commentService;

    public CommentController(CommentService commentService) {
        this.commentService = commentService;
    }

    /**
     * 某篇文章下【已通过】的评论（分页）。
     * 未审核的评论永远不会出现在这里 —— 状态在 Service 里写死，不读前端参数。
     */
    @Operation(summary = "文章评论列表（仅已通过）")
    @GetMapping("/list")
    public Result<IPage<CommentVO>> list(CommentQuery query) {
        return Result.success(commentService.pagePublished(query));
    }

    /**
     * 发表评论（游客可用，**有限流**）。
     *
     * 【为什么要显式接 HttpServletRequest】要记来源 IP。
     *   在这里取而不是在 Service 里用 RequestContextHolder：
     *   把"从哪个请求来"当作参数传进来，Service 就不依赖 Web 上下文，
     *   单测里可以直接传 null（Service 里已经做了 null 保护）。
     *
     * 【为什么返回整个 CommentVO 而不是只返回 id】
     *   前端要显示"你的评论已提交，等待审核"以及刚提交的内容 ——
     *   返回 VO 可以直接渲染，不用再发一次请求去查（何况它现在根本查不到：
     *   待审核的评论在列表接口里是不可见的）。
     *
     * 【⚠️ 限流是"按请求次数"，不是"按成功的评论数"—— 这是刻意的】
     *   联调时前端发现：用必然失败的请求（比如 articleId 填一个不存在的）连打 21 次，
     *   照样会把 20 次/分钟的配额耗光，于是"参数写错的请求会挤占正常读者的额度"。
     *   这不是 bug，而是限流该有的语义：
     *     · 限流的目的是"挡住滥用的流量"，而滥用者发的大多正是无效请求 ——
     *       如果只统计"成功"的请求，攻击者用垃圾参数就能无限刷而不受限，限流形同虚设
     *     · 它保护的是【后端自己】（数据库连接、CPU），而这些开销在参数校验之前就已经发生
     *   反过来说，如果哪天想让"参数错误"不占额度，那就不该用全局配额，
     *   而应该按 IP 记账（Nginx 那层已经在做）。
     *
     * 【返回的 status 前端要读】它区分两种完全不同的结果：
     *   0 = 待审核（游客与注册用户）→ 提示"评论已提交，等待审核"；
     *   1 = 已发布（管理员）→ 提示"评论已发布"，而且这条会立刻出现在下面的列表里。
     *   前端只提示一句固定文案的话，管理员会以为自己的回复还要等审核。
     */
    @Operation(summary = "发表评论（游客/注册用户需审核，管理员直接发布，有限流）")
    @PostMapping
    @RateLimiter(name = "commentRateLimiter")
    public Result<CommentVO> create(@Valid @RequestBody CommentForm form, HttpServletRequest request) {
        // 【为什么要把"当前用户"从 SecurityContext 里取出来传下去】
        //   与上面那条"显式接 HttpServletRequest"是同一个理由：Service 不依赖
        //   Web 上下文（单测里可以直接传 null），"我是谁"由入口处问清楚再往下传。
        //   这里取而不是在 Service 里用 SecurityContextHolder，也让"未登录是常态"
        //   这件事在方法签名上看得见（参数可空），而不是藏在 Service 内部的一个 try 里。
        return Result.success(commentService.create(form, request, currentUserOrNull()));
    }

    /**
     * 当前登录用户；**未登录时返回 null**。
     *
     * 【为什么不能直接强转】这个接口是匿名开放的，未登录时 SecurityContext 里
     *   放的不是 {@code LoginUser}，而是 Spring Security 的匿名令牌
     *   （其 principal 是一个字符串 "anonymousUser"），强转会抛 ClassCastException。
     *   与 OperationLogRecorder 里那处是同一个判断（那里也处理了匿名的情况）。
     */
    private LoginUser currentUserOrNull() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof LoginUser loginUser) {
            return loginUser;
        }
        return null;
    }
}
