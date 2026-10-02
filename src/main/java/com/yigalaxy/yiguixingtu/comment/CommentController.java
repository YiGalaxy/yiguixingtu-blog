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

/** 前台公开评论接口。游客可读写，评论默认审核并受限流保护。 */
@Slf4j
@Tag(name = "评论（前台）", description = "看评论、发评论，无需登录")
@RestController
@RequestMapping("/comment")
public class CommentController {

    private final CommentService commentService;

    public CommentController(CommentService commentService) {
        this.commentService = commentService;
    }

    /** 分页返回已通过评论；审核状态由服务层固定。 */
    @Operation(summary = "文章评论列表（仅已通过）")
    @GetMapping("/list")
    public Result<IPage<CommentVO>> list(CommentQuery query) {
        return Result.success(commentService.pagePublished(query));
    }

    /** 发表评论；游客可用，来源 IP 由服务层记录，接口受限流保护。 */
    @Operation(summary = "发表评论（游客/注册用户需审核，管理员直接发布，有限流）")
    @PostMapping
    @RateLimiter(name = "commentRateLimiter")
    public Result<CommentVO> create(@Valid @RequestBody CommentForm form, HttpServletRequest request) {
        return Result.success(commentService.create(form, request, currentUserOrNull()));
    }

    /** 返回当前用户；匿名请求返回 null。 */
    private LoginUser currentUserOrNull() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof LoginUser loginUser) {
            return loginUser;
        }
        return null;
    }
}
