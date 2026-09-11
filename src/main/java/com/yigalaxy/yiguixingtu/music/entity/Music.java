package com.yigalaxy.yiguixingtu.music.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 音乐实体，对应数据库表 music。
 *
 * 【它和 F5 那四个内容模块（友链 / 项目 / 收藏 / 关于）的差别只有一处：多了"文件"】
 *   其余字段的形状完全一样（title / sort / status / 时间与逻辑删除），
 *   但音乐多了一个必须真实存在的音频文件：
 *     · {@code url} 由 {@code POST /upload?type=audio} 上传后拿到，
 *       形如 {@code http://localhost:8082/uploads/music/2026/09/xxx.mp3}
 *     · 它也可以填 http(s) 外链（有人把音源放在自己的对象存储或 CDN 上），
 *       格式白名单见 {@code common/validation/UrlPatterns.MEDIA_URL}
 *
 * 【⚠️ 删掉一条音乐会【连带清理】磁盘上的音频文件（2026-09 起的行为）】
 *   这条以前是反过来的：当时只标记数据库行、不删文件（留下的孤儿文件要靠人工按目录清）。
 *   现在改成"先确认没有人再用这个文件，然后才删"——三处都查：别的曲目行、
 *   文章正文/封面、文章的附件行（正文里嵌一段
 *   {@code <audio src="/uploads/music/...">} 是同源可达的，所以文章也算引用方）。
 *   完整推导见 {@code MusicServiceImpl.delete} 的长注释；用例见 MusicFileCleanupTest。
 *   ⚠️ 一条例外：{@code url} 是 http(s) 外链时（音源放在自己的对象存储 / CDN 上），
 *   那个地址不属于本项目的上传目录，我们一个字节都不碰。
 *
 * 【lyrics 是 LRC 原文文本，不是文件路径】完整理由见 V11 迁移脚本的注释 ②。
 */
@Data
@Schema(description = "音乐")
@TableName("music")
public class Music implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 状态：隐藏（前台查不到） */
    public static final int STATUS_HIDDEN = 0;

    /** 状态：显示（前台可见，也是新建时的默认值） */
    public static final int STATUS_VISIBLE = 1;

    @Schema(description = "音乐ID", example = "1")
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 曲名，最长 100（与 DTO 校验、数据库列长度三处一致） */
    @Schema(description = "曲名", example = "夜曲")
    private String title;

    /** 歌手，可为空（纯音乐没有歌手）。只当文字渲染，不做格式校验 */
    @Schema(description = "歌手（可为空）", example = "周杰伦")
    private String artist;

    /**
     * 音频地址，必填。上传回来的是 {@code /uploads/music/...} 形式的绝对地址
     * （{@code app.upload.base-url} + 路径），也允许 http(s) 外链。
     * 格式由 DTO 上的 {@code @Pattern} 白名单保证（只放行这两种形态）。
     */
    @Schema(description = "音频地址", example = "http://localhost:8082/uploads/music/2026/09/xxx.mp3")
    private String url;

    /** 封面图地址，可为空（读不到时前端用自带的兜底图） */
    @Schema(description = "封面图地址（可为空）")
    private String cover;

    /** 歌词：LRC 原文文本（含 {@code [00:12.34]} 时间戳），不是文件路径 */
    @Schema(description = "歌词（LRC 原文，可为空）")
    private String lyrics;

    @Schema(description = "排序值，越小越靠前", example = "1")
    private Integer sort;

    @Schema(description = "状态：0隐藏 1显示")
    private Integer status;

    @Schema(description = "创建时间")
    private LocalDateTime createTime;

    @Schema(description = "更新时间")
    private LocalDateTime updateTime;

    /** 逻辑删除：本表没有任何唯一索引，所以不会踩"逻辑删除 + 唯一索引"那个坑 */
    @Schema(description = "逻辑删除：0未删 1已删", hidden = true)
    @TableLogic
    private Integer deleted;
}
