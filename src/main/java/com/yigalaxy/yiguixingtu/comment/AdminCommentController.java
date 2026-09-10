package com.yigalaxy.yiguixingtu.comment;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.yigalaxy.yiguixingtu.comment.dto.AdminCommentVO;
import com.yigalaxy.yiguixingtu.comment.dto.CommentQuery;
import com.yigalaxy.yiguixingtu.comment.service.CommentService;
import com.yigalaxy.yiguixingtu.common.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 评论管理接口（后台，仅管理员）。
 *
 * 【权限写法与其它后台接口完全一致】类级 {@code @PreAuthorize("hasRole('ADMIN')")}，
 * 新增方法时不会漏加注解。
 *
 * 【这个类返回 AdminCommentVO 而不是 CommentVO】
 *   区别只有两个字段：email 和 ip。它们是给管理员看的（判断是不是同一个人刷评论），
 *   绝不能出现在公开接口的响应体里 —— 用两个不同的 VO 类型把这件事交给编译器管，
 *   而不是靠"记得别加那个字段"。
 */
@Slf4j
@Tag(name = "评论（后台）", description = "评论审核与删除，仅管理员")
@RestController
@RequestMapping("/admin/comment")
@PreAuthorize("hasRole('ADMIN')")
public class AdminCommentController {

    /** 常用筛选项：待审核。定义成常量，前端传参会话里也说得清"0 是待审核" */
    private static final int STATUS_PENDING = 0;

    private final CommentService commentService;

    public AdminCommentController(CommentService commentService) {
        this.commentService = commentService;
    }

    /**
     * 评论分页（可按状态 / 文章筛选）。
     *
     * 【前端怎么拿"待审核"那一批】`?status=0` ——
     * 状态值就是 comment 表里那几个数字（0 待审核 / 1 已通过 / 2 已拒绝）。
     */
    @Operation(summary = "评论分页（含待审核，带邮箱与IP）")
    @GetMapping("/page")
    public Result<IPage<AdminCommentVO>> page(CommentQuery query) {
        return Result.success(commentService.pageForAdmin(query));
    }

    /**
     * 审核：通过 / 拒绝。
     *
     * @param status 1 = 通过（前台可见）；2 = 拒绝（前台不可见，记录保留）
     */
    @Operation(summary = "审核评论（1 通过 / 2 拒绝）")
    @PutMapping("/{id}/status")
    public Result<Void> updateStatus(@PathVariable Long id, @RequestParam Integer status) {
        commentService.updateStatus(id, status);
        return Result.success();
    }

    @Operation(summary = "删除评论（逻辑删除）")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        commentService.delete(id);
        return Result.success();
    }
}
