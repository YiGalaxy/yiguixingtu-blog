package com.yigalaxy.yiguixingtu.comment.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 后台看到的评论（比 CommentVO 多两个字段）。
 *
 * 【为什么要单独一个 VO，而不是给 CommentVO 加两个字段】
 *   因为 CommentVO 是【公开接口】的返回类型：给它加 email / ip，
 *   等于把这些信息挂到了任何人都能调的接口上。
 *   两个 VO 的差别本身就是一道防线 —— 以后有人想把 ip 展示到前台，
 *   他必须先改这个类的继承关系，而不是"顺手在公开 VO 里加一行"。
 */
@Data
@Schema(description = "评论（后台，含邮箱与来源IP）")
public class AdminCommentVO {

    @Schema(description = "评论ID")
    private Long id;

    @Schema(description = "所属文章ID")
    private Long articleId;

    /** 文章标题：后台列表要显示"这条评论是哪篇文章下的"，否则只能看到一串 id */
    @Schema(description = "文章标题")
    private String articleTitle;

    @Schema(description = "评论者昵称")
    private String nickname;

    @Schema(description = "邮箱")
    private String email;

    @Schema(description = "评论内容")
    private String content;

    @Schema(description = "状态：0待审核 1已通过 2已拒绝")
    private Integer status;

    @Schema(description = "来源IP")
    private String ip;

    @Schema(description = "创建时间")
    private LocalDateTime createTime;
}
