package com.yigalaxy.yiguixingtu.about.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 关于页出参。
 *
 * 【前台和后台共用这一个 VO】
 *   数据只有一份，前台要用它渲染关于页、后台要用它填表单 ——
 *   两边要的字段一模一样（这也是"后台不另开一个 GET 接口"的前提，
 *   见 AdminAboutController 的类注释）。为后台再建一个 VO 只会多一处要同步的重复。
 *
 * 【id 也返回】虽然它恒为 1 且前端用不上，但它是"这份数据的主键"这个事实的一部分，
 *   返回值里带上它，前端把响应缓存起来时也更容易判断"拿到的到底是不是同一份数据"。
 */
@Data
@Schema(description = "关于页信息")
public class AboutVO {

    @Schema(description = "固定为 1（这张表只有一行）", example = "1")
    private Long id;

    @Schema(description = "昵称", example = "别太在亿啦")
    private String nickname;

    @Schema(description = "头像地址")
    private String avatar;

    @Schema(description = "自我介绍（Markdown 原文）")
    private String bio;

    @Schema(description = "邮箱")
    private String email;

    @Schema(description = "GitHub 地址")
    private String github;

    @Schema(description = "微信")
    private String wechat;

    @Schema(description = "QQ")
    private String qq;

    @Schema(description = "更新时间")
    private LocalDateTime updateTime;
}
