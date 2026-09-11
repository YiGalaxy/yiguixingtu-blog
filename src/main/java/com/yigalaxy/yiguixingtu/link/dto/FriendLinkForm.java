package com.yigalaxy.yiguixingtu.link.dto;

import com.yigalaxy.yiguixingtu.common.validation.UrlPatterns;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 新建 / 编辑友链的提交表单。
 *
 * 【为什么新建和编辑共用一个 DTO】能填的字段完全一样（后端没有"只有新建时才能填"的字段），
 *   拆成两个类只会多一份要同步的重复 —— 和 TagForm / CategoryForm 同一个思路。
 *
 * 【这里有三层校验，各自负责不同的事（README「接口安全约定」里的"双重校验"）】
 *   ① {@code @NotBlank} / {@code @Size} —— 必填与长度上限。
 *      ⚠️ 长度必须和数据库列长度【完全一致】：只靠数据库报错的话，
 *      用户看到的是 "Data too long for column 'name'"，既看不懂也帮不上忙；
 *      在这里拦住才能给出"站点名称最长 50 字"这种照着改就行的提示。
 *   ② {@code @Pattern} —— 格式白名单（URL 只放行 http/https，见 UrlPatterns）。
 *   ③ Service 里的业务校验 —— 比如状态只能是 0/1、空串归一化成 null。
 *      前端能绕过 DTO（直接调接口），但绕过不了 Service。
 */
@Data
@Schema(description = "友链提交表单")
public class FriendLinkForm {

    /**
     * 站点名称。长度 1–50，与 friend_link.name 的 varchar(50) 对齐。
     *
     * 【trim 谁来管】@NotBlank 会拒绝纯空格，但不会去掉首尾空格 ——
     * 而"某某的博客"和"某某的博客 "在列表里显示起来一模一样，
     * 却会让排序、查找对不上。所以 Service 里统一 trim 之后再落库。
     */
    @NotBlank(message = "站点名称不能为空")
    @Size(max = 50, message = "站点名称最长 50 字")
    @Schema(description = "站点名称", example = "某某的博客")
    private String name;

    /**
     * 站点地址。格式必须是 http(s) 开头的完整地址（白名单，理由见 UrlPatterns）；
     * 长度 255 与数据库列 varchar(255) 对齐。
     * 空串由上面的 {@code @NotBlank} 拦住（友链没有地址就没有意义）——
     * 这也是"为什么正则本身允许空串"能成立的前提：必填靠 @NotBlank，格式靠 @Pattern。
     */
    @NotBlank(message = "站点地址不能为空")
    @Size(max = 255, message = "站点地址最长 255 字")
    @Pattern(regexp = UrlPatterns.EXTERNAL_URL, message = UrlPatterns.EXTERNAL_URL_MESSAGE)
    @Schema(description = "站点地址", example = "https://example.com")
    private String url;

    /**
     * 头像/站点图标，可选。
     * 允许两种形态：上传接口返回的绝对地址，或 / 开头的站内路径（见 UrlPatterns.IMAGE_URL）。
     * 传空串按"清空"处理（Service 归一化成 null）。
     */
    @Size(max = 255, message = "头像地址最长 255 字")
    @Pattern(regexp = UrlPatterns.IMAGE_URL, message = UrlPatterns.IMAGE_URL_MESSAGE)
    @Schema(description = "头像地址（可选）")
    private String avatar;

    /** 一句话介绍，可选，最长 200（与列长度一致） */
    @Size(max = 200, message = "简介最长 200 字")
    @Schema(description = "一句话介绍（可选）")
    private String description;

    /**
     * 排序值，越小越靠前。
     *
     * 【不传时是什么行为 —— 与 tag / category 保持一致】
     *   新建：当成 0（也就是"排在最前面那批里"）
     *   编辑：保持原来的值不变（不传 ≠ 想改成 0）
     *   两处分叉的理由写在 FriendLinkServiceImpl 里，测试各有一条用例钉住。
     */
    @Schema(description = "排序值，越小越靠前", example = "1")
    private Integer sort;

    /**
     * 状态：0 隐藏 / 1 显示。
     *
     * 【不传时】新建按 1（显示）处理，编辑保持原状态不变。
     * 【@Min / @Max 为什么放在 DTO 上，明明 Service 里还会再判一次】
     *   这里是"能给出的最快反馈"（请求还没进 Service 就被拦下），
     *   Service 那道则保证"不管从哪条路径进来都拦得住"（比如将来别的地方直接调 Service）。
     *   两道校验的消息文案一致，所以用户看不出是哪一道拦的 —— 这正是想要的效果。
     */
    @Min(value = 0, message = "状态只能是 0(隐藏) 或 1(显示)")
    @Max(value = 1, message = "状态只能是 0(隐藏) 或 1(显示)")
    @Schema(description = "状态：0隐藏 1显示（不传时新建按 1、编辑保持原值）", example = "1")
    private Integer status;
}
