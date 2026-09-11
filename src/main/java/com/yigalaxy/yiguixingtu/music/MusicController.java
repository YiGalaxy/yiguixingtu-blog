package com.yigalaxy.yiguixingtu.music;

import com.yigalaxy.yiguixingtu.common.Result;
import com.yigalaxy.yiguixingtu.music.dto.MusicVO;
import com.yigalaxy.yiguixingtu.music.service.MusicService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 音乐接口（前台公开）。
 *
 * 【类上没有 @PreAuthorize，SecurityConfig 里放行了 GET /music/list】
 *   位置与写法与 /link/list、/project/list、/favorite/list 完全一致。
 *   后台的增删改在 {@link AdminMusicController}（类级 ADMIN）。
 *
 * 【为什么一次返回全部（不分页）】
 *   一个站点的曲目是几十条的量级，一次返回最省事，播放器也正好要一份完整列表
 *   （下一首 / 随机播放都在这份列表里挑）。等真到了几百首再谈分页 ——
 *   那时接口形状会变，但"先简单、按需演进"比"提前设计一个没人用的分页参数"更划算。
 *
 * 【⚠️ 列表为空不是错误】库里一首都没有时返回的是空数组（code 200），
 *   前端会回落成内置的那一首（static-media/bg-music.mp3）——
 *   这是它的既有行为，不是"后端漏了数据"。见 V11 迁移脚本开头的说明。
 */
@Slf4j
@Tag(name = "音乐（前台）", description = "音乐列表，无需登录")
@RestController
@RequestMapping("/music")
public class MusicController {

    private final MusicService musicService;

    public MusicController(MusicService musicService) {
        this.musicService = musicService;
    }

    /** 音乐列表（只含"显示"的，按 sort 升序、sort 相同按 id 升序；走 Redis 缓存） */
    @Operation(summary = "音乐列表（含显示中的，无需登录）")
    @GetMapping("/list")
    public Result<List<MusicVO>> list() {
        return Result.success(musicService.listVisible());
    }
}
