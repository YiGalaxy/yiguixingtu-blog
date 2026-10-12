package com.yigalaxy.yiguixingtu.comment.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** 发表评论表单。游客填写昵称，登录用户使用账号昵称。 */
@Data
@Schema(description = "发表评论表单")
public class CommentForm {

    @Schema(description = "文章ID", example = "1")
    @jakarta.validation.constraints.NotNull(message = "文章ID不能为空")
    private Long articleId;

    /** 游客必填；登录用户的昵称由服务端决定。 */
    @Size(max = 50, message = "昵称最长 50 字")
    @Schema(description = "昵称", example = "路过的读者")
    private String nickname;

    @Email(message = "邮箱格式不正确")
    @Size(max = 100, message = "邮箱最长 100 字")
    @Schema(description = "邮箱（选填，不对外展示）", example = "reader@example.com")
    private String email;

    @NotBlank(message = "评论内容不能为空")
    @Size(max = 1000, message = "评论最长 1000 字")
    @Schema(description = "评论内容", example = "写得很清楚，收藏了！")
    private String content;
}
