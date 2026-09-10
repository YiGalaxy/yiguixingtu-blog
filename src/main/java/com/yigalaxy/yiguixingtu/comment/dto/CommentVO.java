package com.yigalaxy.yiguixingtu.comment.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 评论（对外返回）。
 *
 * 【⚠️ 这里【没有】email 和 ip，是刻意的】
 *   评论列表是【公开接口】，任何人都能拿到响应体。
 *   把 email / ip 放进 VO 就等于把读者和管理员的信息公开了 ——
 *   这类泄漏的可怕之处在于"接口看起来一切正常"，没人会去注意响应体多了一个字段。
 *   所以后台要用这两个字段时，另起一个 AdminCommentVO（见那个类）。
 *
 * 【status 为什么在前台也返回】
 *   前台只会返回已通过的评论（status 恒为 1），看起来多余。
 *   但提交评论的接口会返回这条 VO —— 那时 status = 0（待审核），
 *   前端据此提示"评论已提交，等待审核"。这样一个字段解决了"提交后该说什么"。
 */
@Data
@Schema(description = "评论")
public class CommentVO {

    @Schema(description = "评论ID")
    private Long id;

    @Schema(description = "所属文章ID")
    private Long articleId;

    @Schema(description = "评论者昵称")
    private String nickname;

    @Schema(description = "评论内容")
    private String content;

    @Schema(description = "状态：0待审核 1已通过 2已拒绝")
    private Integer status;

    @Schema(description = "创建时间")
    private LocalDateTime createTime;
}
