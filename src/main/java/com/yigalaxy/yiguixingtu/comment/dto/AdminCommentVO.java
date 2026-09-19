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

    /**
     * 评论者的注册账号 id；**游客为 null**。
     *
     * 【后台为什么需要它】昵称是一个谁都能填的字符串 —— 光看昵称，
     * 管理员分不出"这是注册用户发的"还是"游客随手填了个一样的名字"
     * （也就是说任何人都能冒用站长的昵称发评论）。
     * 这一列才是判断依据：不为 null 就说明它来自一个真实账号，可以照着 id 去用户管理里查。
     *
     * 【为什么只在后台 VO 里】公开接口的 CommentVO 里没有它（也不该有：
     * 前台展示一条评论不需要、也不应该暴露它是哪个账号发的）。
     */
    @Schema(description = "评论者的注册账号 id（游客为 null）")
    private Long userId;

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
