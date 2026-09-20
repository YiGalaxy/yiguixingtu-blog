package com.yigalaxy.yiguixingtu.auth.dto;


import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 注册请求。
 *
 * 【为什么邮箱是必填的】在这之前站长手里没有任何联系注册用户的方式 ——
 *   将来要做找回密码、给用户发通知，第一步都是"先有一个能发信过去的地址"。
 *   把它放在注册入口，是让这件事从"以后再说"变成"从第一条记录起就有"。
 *
 * 【⚠️ 本期只做"必填 + 格式校验"，不发验证邮件】
 *   也就是说这个邮箱**没有被验证过**：它可能是别人的、可能是随手编的。
 *   由此带来两条必须守住的结论：
 *     · **不能当身份键**（不加唯一索引、不用它登录），见 V18 迁移脚本里的说明
 *     · 后台看到它时要知道它只是"用户自己填的"，与昵称同级的可信度
 *   真要做验证（发验证码 / 激活链接）时，@NotBlank + @Email 这两条依然成立，
 *   那时的改动是"再加一步验证流程"，不是把现有校验换掉。
 */
@Data
@Schema(description = "注册请求")
public class RegisterRequest {


    @Schema(description = "用户名",example = "yigalaxy")
    @NotBlank(message = "用户名不能为空")
    private String username;

    @Schema(description = "密码",example = "123456")
    @NotBlank(message = "密码不能为空")
    @Size(min = 6, max = 20, message = "密码长度需要在6-20之间")
    private String password;

    @Schema(description = "昵称", example = "小亿同学")
    private String nickname;

    /**
     * 邮箱。
     *
     * 【为什么 @Email 之外还要一条 @Pattern —— 实测出来的】
     *   {@code @Email} 只校验"是不是邮箱的形状"，它**不要求域名里有点**：
     *   {@code a@b}、{@code plain@localhost} 它都判为合法（实测 Hibernate Validator 9.1）。
     *   对"选填的评论邮箱"来说这无所谓，但对这一条不行 ——
     *   这个字段存在的唯一目的是**站长能发信联系到用户**，
     *   而一个没有点的域名在他点"回复"的那一刻就是死的：
     *   记录看起来是完整的，实际问题要到几个月后想联系人的时候才会暴露。
     *   所以这里补一条"域名必须有点"，用的正是前端 checkEmail 里那条正则
     *   （app/utils/contentForm.ts），**两边保持同一个口径** ——
     *   否则会出现"前端说格式不对、直接调接口却成功"这种对不上的情况。
     *
     * 【为什么不用 @Pattern 取代 @Email】@Email 拦得住的那一大类畸形值
     *   （多个 @、非法字符、本地部分过长）不值得自己再写一遍，
     *   自己写正则正是 CommentForm 里点名警告过的事。两条各管一段。
     *   两条的 message 写成同一句：用户看到的是同一件"这个邮箱填得不对"，
     *   而哪一条先报取决于校验器内部顺序，不该让他看出差别。
     *
     * 【为什么长度卡在 100】数据库列是 varchar(100)（与 comment.email、
     *   about.email 一致）。不卡的话超长邮箱会在 INSERT 时直接失败，
     *   用户看到的是一句「服务器内部错误」——而这是他自己能改的输入问题。
     */
    @Schema(description = "邮箱", example = "someone@example.com")
    @NotBlank(message = "邮箱不能为空")
    @Email(message = "邮箱格式不正确")
    @Pattern(regexp = "^[^@\\s]+@[^@.\\s]+(\\.[^@.\\s]+)+$", message = "邮箱格式不正确")
    @Size(max = 100, message = "邮箱最长 100 字")
    private String email;
}
