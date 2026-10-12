package com.yigalaxy.yiguixingtu.comment.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.yigalaxy.yiguixingtu.auth.LoginUser;
import com.yigalaxy.yiguixingtu.comment.dto.AdminCommentVO;
import com.yigalaxy.yiguixingtu.comment.dto.CommentForm;
import com.yigalaxy.yiguixingtu.comment.dto.CommentQuery;
import com.yigalaxy.yiguixingtu.comment.dto.CommentVO;
import jakarta.servlet.http.HttpServletRequest;

/** 评论服务。公开与管理接口使用不同 VO，避免向访客暴露私有字段。 */
public interface CommentService {

    /** 前台：某篇文章下已通过的评论。 */
    IPage<CommentVO> pagePublished(CommentQuery query);

    /**
     * 发表评论。游客昵称来自表单，登录用户昵称来自账号；管理员评论直接通过。
     * @param currentUser 当前用户；游客为 null
     */
    CommentVO create(CommentForm form, HttpServletRequest request, LoginUser currentUser);

    /** 后台：全部评论，可按状态和文章筛选。 */
    IPage<AdminCommentVO> pageForAdmin(CommentQuery query);

    /** 后台：审核；status 只能是 1（通过）或 2（拒绝）。 */
    void updateStatus(Long id, Integer status);

    /** 后台：逻辑删除评论。 */
    void delete(Long id);
}
