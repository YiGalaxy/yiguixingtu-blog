package com.yigalaxy.yiguixingtu.music;

import com.yigalaxy.yiguixingtu.common.Result;
import com.yigalaxy.yiguixingtu.music.dto.MusicForm;
import com.yigalaxy.yiguixingtu.music.dto.MusicVO;
import com.yigalaxy.yiguixingtu.music.service.MusicService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 音乐管理接口（后台，仅管理员）。
 *
 * 【权限写法与其它后台接口完全一致】类级 {@code @PreAuthorize("hasRole('ADMIN')")}：
 * 无 token → 401（过滤器层），GUEST + 合法 token → 403，ADMIN → 正常返回。
 *
 * 【{@code url} 怎么来】先调 {@code POST /upload?type=audio} 拿到地址，
 * 再把那个地址放进这里的表单。这个模块【不】自己接收文件 ——
 * 上传是独立的一类能力（校验、落盘、路径生成都在 upload 包里），
 * 混进业务接口会让"能传什么"这件事散落到每个模块各写一遍。
 */
@Slf4j
@Tag(name = "音乐（后台）", description = "音乐的增删改查，仅管理员")
@RestController
@RequestMapping("/admin/music")
@PreAuthorize("hasRole('ADMIN')")
public class AdminMusicController {

    private final MusicService musicService;

    public AdminMusicController(MusicService musicService) {
        this.musicService = musicService;
    }

    /** 音乐列表（后台，含隐藏的，不走缓存 —— 管理员刚点完"隐藏"就要看到效果） */
    @Operation(summary = "音乐列表（后台，含隐藏，不走缓存）")
    @GetMapping("/list")
    public Result<List<MusicVO>> list() {
        return Result.success(musicService.listAll());
    }

    @Operation(summary = "新建音乐（url 来自 POST /upload?type=audio）")
    @PostMapping
    public Result<Long> create(@Valid @RequestBody MusicForm form) {
        return Result.success(musicService.create(form));
    }

    @Operation(summary = "编辑音乐")
    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable Long id, @Valid @RequestBody MusicForm form) {
        musicService.update(id, form);
        return Result.success();
    }

    /**
     * 删除歌曲（逻辑删除数据库行）。
     *
     * 【磁盘上的 mp3 也会被清掉 —— 但先确认没有别人在用】
     *   三处引用各查一次（别的曲目行 / 文章正文与封面 / 文章附件行），
     *   全都为零才删文件；url 是 http(s) 外链时不属于本站上传目录，不碰任何文件。
     *   为什么这么改、以及"宁可多认不可漏认"的取舍，见 MusicServiceImpl.delete 的长注释。
     */
    @Operation(summary = "删除音乐（逻辑删除；音频文件在确认无人引用后一并清掉）")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        musicService.delete(id);
        return Result.success();
    }
}
