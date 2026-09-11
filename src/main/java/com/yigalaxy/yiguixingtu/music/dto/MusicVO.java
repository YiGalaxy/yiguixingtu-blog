package com.yigalaxy.yiguixingtu.music.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 音乐出参。
 *
 * 【字段就是前端播放器要渲染的那几样，一个不多一个不少】
 *   id / title / artist / url / cover / lyrics / sort / status / createTime
 *   名字与前端约定【一个字都不能改】（前端已经按这份契约写好解析代码，
 *   字段改名不会报错，只会让播放器拿到 undefined —— 最难查的一类问题）。
 *
 * 【lyrics 是 LRC 原文文本，不是文件地址】
 *   前端自己解析时间戳（见前端 app/utils/lrc.ts）。后端不做解析：
 *   解析结果（什么时间点显示哪一行）是纯展示逻辑，放后端只会让它多一份要跟着前端改的东西。
 *
 * 【url / cover / lyrics 都可能是 null】
 *   url 在业务上必填，但历史上可能有数据缺口，所以类型上仍写可空、
 *   前端按"拿不到就别渲染这一项"处理。数据库列上它其实是 NOT NULL。
 */
@Data
@Schema(description = "音乐信息")
public class MusicVO {

    @Schema(description = "音乐ID", example = "1")
    private Long id;

    @Schema(description = "曲名", example = "夜曲")
    private String title;

    @Schema(description = "歌手（可为空）", example = "周杰伦")
    private String artist;

    @Schema(description = "音频地址（业务上必填）")
    private String url;

    @Schema(description = "封面图地址（可为空）")
    private String cover;

    @Schema(description = "歌词（LRC 原文文本，可为空）")
    private String lyrics;

    @Schema(description = "排序值，越小越靠前", example = "1")
    private Integer sort;

    @Schema(description = "状态：0隐藏 1显示（前台列表里恒为 1）")
    private Integer status;

    @Schema(description = "创建时间")
    private LocalDateTime createTime;
}
