package com.yigalaxy.yiguixingtu.comment.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 发表评论的提交表单。
 *
 * 【为什么游客也能评论（不要求登录）】
 *   博客的评论区是"读者说话的地方"。要求注册才能留言，等于把 90% 的读者挡在门外 ——
 *   而博客本身并没有"用户体系"要运营（注册用户只有站长自己）。
 *   代价是要自己处理垃圾评论，本项目的对策有三层：
 *     ① 评论默认【待审核】，不通过就不对外可见（最主要的一层）
 *     ② 接口限流（见 application.properties 的 commentRateLimiter）
 *     ③ 内容与昵称的长度上限 + HTML 转义（见 CommentServiceImpl）
 *
 * 【为什么昵称是必填、邮箱是选填】
 *   昵称会展示在评论区，没有它评论就成了匿名攻击；邮箱只用于站长联系/回复，
 *   读者不给也能评论 —— 强制填邮箱会显著降低评论意愿，而它对博客没什么价值。
 */
@Data
@Schema(description = "发表评论表单")
public class CommentForm {

    @Schema(description = "文章ID", example = "1")
    @jakarta.validation.constraints.NotNull(message = "文章ID不能为空")
    private Long articleId;

    @NotBlank(message = "昵称不能为空")
    @Size(max = 50, message = "昵称最长 50 字")
    @Schema(description = "昵称", example = "路过的读者")
    private String nickname;

    /**
     * 邮箱：选填。
     * 【为什么用 @Email 而不是自己写正则】校验邮箱的正则极难写对
     * （合法格式的范围比直觉宽得多），而 Jakarta Validation 提供的实现
     * 是业界通用的一套规则 —— 自己写一个更严格的反而会拒掉合法邮箱。
     * 注意：它只在【填了】的时候校验，空值由 @Email 之外的逻辑放行。
     */
    @Email(message = "邮箱格式不正确")
    @Size(max = 100, message = "邮箱最长 100 字")
    @Schema(description = "邮箱（选填，不对外展示）", example = "reader@example.com")
    private String email;

    @NotBlank(message = "评论内容不能为空")
    @Size(max = 1000, message = "评论最长 1000 字")
    @Schema(description = "评论内容", example = "写得很清楚，收藏了！")
    private String content;
}
