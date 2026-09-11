package com.yigalaxy.yiguixingtu.setting.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 站点设置的返回对象（前台与后台共用同一个）。
 *
 * 【为什么前台后台不各写一个 VO，与 About 同一个判断】
 *   后台要填的表单字段，和前台要读的字段【一模一样】（七个字段全都要用上：
 *   站点名进页眉页脚、公告进首页、评论开关进文章页、页脚三行进页脚、
 *   每页条数进首页的列表请求）。再开一个"后台专用读"只是多一处要维护、
 *   要写测试、将来还可能忘同步字段的东西。
 *
 * 【这里 commentEnabled 是 Boolean，而实体里是 Integer】
 *   转换的理由与写法见 SiteSetting 的类注释（一句话：数据库存 0/1 便于用 SQL 排查，
 *   接口给布尔是因为前端那个控件就是 el-switch）。
 *
 * 【为什么没有 id】
 *   只有一行，id 恒为 1。把它暴露出去只会让人以为"可以指定改哪一行"。
 *
 * 【updateTime 有什么用】
 *   它是"设置是不是最新的"的唯一线索：前端是缓存读的（走 Redis），
 *   排查"为什么我改了没生效"时，先看这个时间对不对就能定位到是缓存还是保存没成功。
 */
@Data
@Schema(description = "站点设置")
public class SettingVO {

    @Schema(description = "站点名", example = "亿轨星途")
    private String siteName;

    /** 首页公告：null 表示不显示（前端据此整块不渲染，而不是渲染一个空条） */
    @Schema(description = "首页公告（null 表示不显示）")
    private String announcement;

    @Schema(description = "评论总开关", example = "true")
    private Boolean commentEnabled;

    /** ICP 备案号：null 表示页脚不显示备案信息 */
    @Schema(description = "ICP 备案号（null 表示不显示）")
    private String icpNumber;

    /**
     * 公安网安备案号：null 表示页脚不显示公安备案信息。
     * 与 icpNumber 是两个独立字段（两套备案体系，链接指向的平台也不同），
     * 一个为 null 只影响页脚的那一行，不会连带另一个。
     */
    @Schema(description = "公安网安备案号（null 表示不显示）")
    private String policeNumber;

    /** 页脚版权：null 表示页脚不显示版权行 */
    @Schema(description = "页脚版权（null 表示不显示）")
    private String copyright;

    @Schema(description = "每页文章条数", example = "10")
    private Integer pageSize;

    @Schema(description = "更新时间（排查「改了没生效」时先看它）")
    private LocalDateTime updateTime;
}
