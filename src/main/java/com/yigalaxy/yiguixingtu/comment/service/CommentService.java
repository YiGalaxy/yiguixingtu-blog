package com.yigalaxy.yiguixingtu.comment.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.yigalaxy.yiguixingtu.comment.dto.AdminCommentVO;
import com.yigalaxy.yiguixingtu.comment.dto.CommentForm;
import com.yigalaxy.yiguixingtu.comment.dto.CommentQuery;
import com.yigalaxy.yiguixingtu.comment.dto.CommentVO;
import jakarta.servlet.http.HttpServletRequest;

/**
 * 评论服务。
 *
 * 【前台与后台分成两组方法，是因为"能看见什么"完全不同】
 *   前台：只看得到【已通过】的评论，而且返回的字段里没有邮箱和 IP
 *   后台：看得到全部状态（待审核 / 已通过 / 已拒绝），并且带邮箱与 IP
 *   两组方法返回不同的 VO，就是让"前台不可能拿到敏感字段"这件事
 *   由【类型】保证，而不是靠"记得别写漏"。
 */
public interface CommentService {

    /**
     * 前台：某篇文章下【已通过】的评论（分页）。
     */
    IPage<CommentVO> pagePublished(CommentQuery query);

    /**
     * 发表评论（游客可用）。
     *
     * @return 新建的评论（status = 0 待审核），前端据此提示"等待审核"
     */
    CommentVO create(CommentForm form, HttpServletRequest request);

    /**
     * 后台：全部评论（可按状态 / 文章筛选，含邮箱与 IP）。
     */
    IPage<AdminCommentVO> pageForAdmin(CommentQuery query);

    /**
     * 后台：审核（通过 / 拒绝）。
     *
     * @param status 只能是 1（通过）或 2（拒绝）
     */
    void updateStatus(Long id, Integer status);

    /** 后台：删除评论（逻辑删除） */
    void delete(Long id);
}
