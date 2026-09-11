package com.yigalaxy.yiguixingtu.music.dto;

import com.yigalaxy.yiguixingtu.common.validation.UrlPatterns;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 新建 / 编辑音乐的提交表单。
 *
 * 【校验的强度按"字段会不会被浏览器解释"分档（与 F5 四个模块同一套风格）】
 *   · title —— 必填 + 长度，它会被渲染成播放列表里的一行文字
 *   · url —— 必填 + 长度 + 格式白名单（{@link UrlPatterns#MEDIA_URL}）：
 *     它会被塞进 {@code <audio src>}，是这张表里唯一能被浏览器"解释"的字段，
 *     所以只有它需要格式校验
 *   · cover —— 可选 + 图片白名单（{@link UrlPatterns#IMAGE_URL}）：进 {@code <img src>}
 *   · artist / lyrics —— 可选，只卡长度，不做任何格式校验：
 *     歌手名可以是任何文字（"Various Artists"、"周杰伦 & 方文山"），
 *     歌词是一份带时间戳的文本，给它们加格式规则只会挡住正当输入
 */
@Data
@Schema(description = "音乐提交表单")
public class MusicForm {

    /** 曲名，1–100 字（与列长度一致） */
    @NotBlank(message = "曲名不能为空")
    @Size(max = 100, message = "曲名最长 100 字")
    @Schema(description = "曲名", example = "夜曲")
    private String title;

    /** 歌手，可选，最长 100（与列长度一致）。可为空：纯音乐没有歌手 */
    @Size(max = 100, message = "歌手最长 100 字")
    @Schema(description = "歌手（可选）", example = "周杰伦")
    private String artist;

    /**
     * 音频地址，必填。
     *
     * 【为什么是 MEDIA_URL 而不是 IMAGE_URL】
     *   两者接受【同一种形态】（http(s) 外链 / 以 / 开头的站内路径 / 空串），
     *   但语义不同：这个字段指向的是音频文件，用 IMAGE_URL 会让后来的人以为
     *   "这里限制了只能填图片" —— 名字误导比重复一条正则更贵。
     *   两者的关系与为什么不合并，写在 UrlPatterns 里。
     */
    @NotBlank(message = "音频地址不能为空")
    @Size(max = 500, message = "音频地址最长 500 字")
    @Pattern(regexp = UrlPatterns.MEDIA_URL, message = UrlPatterns.MEDIA_URL_MESSAGE)
    @Schema(description = "音频地址（上传接口返回的地址，或 http(s) 外链）",
            example = "http://localhost:8082/uploads/music/2026/09/xxx.mp3")
    private String url;

    /**
     * 封面图地址，可选。
     * 空串由 {@code @Pattern} 放行（= 想清空这一栏），Service 里归一化成 NULL。
     */
    @Size(max = 255, message = "封面地址最长 255 字")
    @Pattern(regexp = UrlPatterns.IMAGE_URL, message = UrlPatterns.IMAGE_URL_MESSAGE)
    @Schema(description = "封面图地址（可选）")
    private String cover;

    /**
     * 歌词，LRC 原文文本，最长 20000 字。
     *
     * 【为什么不校验它的格式（比如必须含 [mm:ss] 时间戳）】
     *   纯音乐就是没有歌词；有人想贴一段纯文本歌词也完全正当。
     *   一校验格式，这两种输入都会被挡住，而它们并不会产生任何安全问题
     *   （歌词只被当文字渲染，不进任何 URL / 属性）。
     */
    @Size(max = 20000, message = "歌词最长 20000 字")
    @Schema(description = "歌词（LRC 原文，可为空）")
    private String lyrics;

    /** 排序值，越小越靠前；不传时新建按 0、编辑保持原值（与 F5 四个模块一致） */
    @Schema(description = "排序值，越小越靠前", example = "1")
    private Integer sort;

    /** 状态：0 隐藏 / 1 显示；不传时新建按 1、编辑保持原值 */
    @Min(value = 0, message = "状态只能是 0(隐藏) 或 1(显示)")
    @Max(value = 1, message = "状态只能是 0(隐藏) 或 1(显示)")
    @Schema(description = "状态：0隐藏 1显示（不传时新建按 1、编辑保持原值）", example = "1")
    private Integer status;
}
