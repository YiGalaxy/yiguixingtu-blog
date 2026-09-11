package com.yigalaxy.yiguixingtu.music.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.yigalaxy.yiguixingtu.audit.AuditTarget;
import com.yigalaxy.yiguixingtu.audit.OperationAction;
import com.yigalaxy.yiguixingtu.audit.OperationLogRecorder;
import com.yigalaxy.yiguixingtu.common.ResultCode;
import com.yigalaxy.yiguixingtu.common.cache.ContentCacheVersion;
import com.yigalaxy.yiguixingtu.common.exception.BusinessException;
import com.yigalaxy.yiguixingtu.config.RedisConfig;
import com.yigalaxy.yiguixingtu.music.dto.MusicForm;
import com.yigalaxy.yiguixingtu.music.dto.MusicVO;
import com.yigalaxy.yiguixingtu.music.entity.Music;
import com.yigalaxy.yiguixingtu.music.mapper.MusicMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.stream.Collectors;

/**
 * =====================================================================
 * 音乐服务实现
 *
 * 【它和 F5 四个模块实现的三处差异】
 *
 * ① url 必填，且走的是 MEDIA_URL（而不是 EXTERNAL_URL / IMAGE_URL）
 *    它是这张表的主体 —— 一首没有音源的音乐没有意义。
 *    形态上同时接受"上传回来的 /uploads/music/..."和 http(s) 外链，
 *    为什么不为它新开一个只有音频的规则，见 UrlPatterns 的注释。
 *
 * ② lyrics 是长文本，上限 20000 字（TEXT 列）
 *    归一化规则与其它可选字段一样：空白 → NULL（"清空歌词"要真的清掉）。
 *    ⚠️ 它是这几个内容模块里唯一一个"长文本"字段，所以这里额外说明
 *    为什么不限得更死（比如 2000 字）：一份带逐句时间戳的 LRC 很容易超过 2000 字，
 *    卡在那里会让正当的输入被拒，而它只被当文本存与渲染。
 *
 * ③ ⚠️ 删除【不】删磁盘上的音频文件
 *    逻辑删除只标记数据库行。为什么不去删文件，见 Music 实体的注释
 *    （一句话：同一个文件可能被多条记录引用，而删文件不可逆 —— 猜错的代价太大）。
 *
 * 【其余逐行同构】排序与状态缺省（新建给默认值、编辑保持原值）、
 * LambdaUpdateWrapper 显式 SET（保证"清空"真的写成 NULL）、
 * 内容缓存版本号 + 审计 —— 每处的"为什么"都写在 FavoriteServiceImpl 里，这里不重复抄。
 *
 * 【⚠️ 缓存不复用 ArticleCacheVersion】
 *   理由与 F5 四个模块完全一样：音乐和文章毫无关系，
 *   若共用文章版本号，改一首歌会把文章列表 / 详情 / 归档 / RSS / 分类 / 标签
 *   六份缓存一起作废 —— 功能不会错，但纯属无谓的重新查库。
 *   这里用 ContentCacheVersion 那一个计数器（见 ContentCacheVersion 的类注释）。
 * =====================================================================
 */
@Slf4j
@Service
public class MusicServiceImpl implements MusicService {

    /** 曲名长度上限，与 DTO 校验、数据库列长度三处一致 */
    private static final int TITLE_MAX_LENGTH = 100;

    /** 歌手长度上限（与列长度一致） */
    private static final int ARTIST_MAX_LENGTH = 100;

    /** 音频地址长度上限（与列长度一致） */
    private static final int URL_MAX_LENGTH = 500;

    /** 封面地址长度上限（与列长度一致） */
    private static final int COVER_MAX_LENGTH = 255;

    /** 歌词长度上限（与 DTO 校验一致，远小于 text 的容量） */
    private static final int LYRICS_MAX_LENGTH = 20000;

    private final MusicMapper musicMapper;

    /** 内容缓存版本号：任何音乐写操作都要推进它，否则前台要等 TTL 才更新 */
    private final ContentCacheVersion contentCacheVersion;

    /** 操作审计：后台的增删改都要留痕 */
    private final OperationLogRecorder operationLogRecorder;

    public MusicServiceImpl(MusicMapper musicMapper,
                            ContentCacheVersion contentCacheVersion,
                            OperationLogRecorder operationLogRecorder) {
        this.musicMapper = musicMapper;
        this.contentCacheVersion = contentCacheVersion;
        this.operationLogRecorder = operationLogRecorder;
    }

    // =================================================================
    //  一、读
    // =================================================================

    /** 前台音乐列表（只含显示的），带缓存；key 里只有版本号（这个查询没有参数） */
    @Override
    @Cacheable(cacheNames = RedisConfig.CACHE_MUSIC_LIST, key = "@contentCacheVersion.current()")
    public List<MusicVO> listVisible() {
        return query(Music.STATUS_VISIBLE);
    }

    @Override
    public List<MusicVO> listAll() {
        return query(null);
    }

    /**
     * 真正的查询：一次取回全部，两段排序（sort 升序，sort 相同按 id 升序）。
     *
     * 【为什么必须带第二段 id 排序】这是前台的契约：
     *   只按 sort 排的话，两条 sort 相同的记录在两次查询里的先后顺序
     *   是由存储引擎决定的（不保证稳定），用户会看到列表"自己在跳"。
     *   加上 id 之后顺序完全确定，而且正好能命中 V11 里的 (status, sort, id) 索引。
     *
     * @param status 只要这个状态的数据；传 null 表示"全部"（后台列表）
     */
    private List<MusicVO> query(Integer status) {
        LambdaQueryWrapper<Music> wrapper = new LambdaQueryWrapper<Music>()
                .orderByAsc(Music::getSort)
                .orderByAsc(Music::getId);

        // 不能写成 eq(Music::getStatus, status) 而不判空：MyBatis-Plus 会生成
        // status = NULL（永远不成立），于是后台列表恒为空 —— 不报错、只是查不到数据
        if (status != null) {
            wrapper.eq(Music::getStatus, status);
        }

        return musicMapper.selectList(wrapper).stream()
                .map(this::toVO)
                .collect(Collectors.toList());
    }

    // =================================================================
    //  二、写（仅管理员）
    // =================================================================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long create(MusicForm form) {
        Music music = new Music();
        music.setTitle(normalizeTitle(form));
        music.setArtist(normalizeOptional(form.getArtist(), ARTIST_MAX_LENGTH, "歌手"));
        music.setUrl(normalizeUrl(form));
        music.setCover(normalizeOptional(form.getCover(), COVER_MAX_LENGTH, "封面地址"));
        music.setLyrics(normalizeOptional(form.getLyrics(), LYRICS_MAX_LENGTH, "歌词"));
        music.setSort(form.getSort() == null ? 0 : form.getSort());
        music.setStatus(form.getStatus() == null
                ? Music.STATUS_VISIBLE : requireValidStatus(form.getStatus()));
        music.setDeleted(0);

        musicMapper.insert(music);

        contentCacheVersion.bump();

        operationLogRecorder.record(OperationAction.CREATE_MUSIC, AuditTarget.MUSIC,
                music.getId(), "音乐=" + music.getTitle());

        log.info("新建音乐: id={}, title={}, url={}",
                music.getId(), music.getTitle(), music.getUrl());
        return music.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void update(Long id, MusicForm form) {
        Music exist = requireMusic(id);

        String title = normalizeTitle(form);

        // 逐字段显式 SET：传 null 会真的写成 NULL。
        // 音乐这个模块尤其明显 —— 歌手、封面、歌词都是"随时可能清空"的字段，
        // 用 updateById 的话它们会"清不掉"，用户会以为界面卡了
        musicMapper.update(null, new LambdaUpdateWrapper<Music>()
                .eq(Music::getId, id)
                .set(Music::getTitle, title)
                .set(Music::getArtist, normalizeOptional(form.getArtist(), ARTIST_MAX_LENGTH, "歌手"))
                .set(Music::getUrl, normalizeUrl(form))
                .set(Music::getCover, normalizeOptional(form.getCover(), COVER_MAX_LENGTH, "封面地址"))
                .set(Music::getLyrics, normalizeOptional(form.getLyrics(), LYRICS_MAX_LENGTH, "歌词"))
                .set(Music::getSort, form.getSort() == null ? exist.getSort() : form.getSort())
                .set(Music::getStatus, form.getStatus() == null
                        ? exist.getStatus() : requireValidStatus(form.getStatus())));

        contentCacheVersion.bump();

        String detail = title.equals(exist.getTitle())
                ? "音乐=" + title
                : "音乐 " + exist.getTitle() + " → " + title;
        operationLogRecorder.record(OperationAction.UPDATE_MUSIC, AuditTarget.MUSIC, id, detail);

        log.info("编辑音乐: id={}, {} -> {}", id, exist.getTitle(), title);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        Music exist = requireMusic(id);

        musicMapper.deleteById(id);   // @TableLogic：UPDATE music SET deleted = 1

        contentCacheVersion.bump();

        // detail 留曲名快照：删完之后前台、后台列表都看不到它，
        // 只有审计记录能回答"当时删掉的是哪一首"
        operationLogRecorder.record(OperationAction.DELETE_MUSIC, AuditTarget.MUSIC, id,
                "音乐=" + exist.getTitle());

        log.info("删除音乐: id={}, title={}（磁盘上的音频文件不删，见 Music 实体注释）",
                id, exist.getTitle());
    }

    // =================================================================
    //  三、私有工具
    // =================================================================

    private String normalizeTitle(MusicForm form) {
        String title = form.getTitle() == null ? "" : form.getTitle().trim();
        if (!StringUtils.hasText(title)) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "曲名不能为空");
        }
        if (title.length() > TITLE_MAX_LENGTH) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "曲名最长 " + TITLE_MAX_LENGTH + " 字");
        }
        return title;
    }

    /**
     * 音频地址归一化：必填 + 必须是"外链或站内路径"（DTO 的 @Pattern 已拦一遍，
     * 这里是必经之地的第二道 —— 直接调 Service 的调用方绕不过它）。
     */
    private String normalizeUrl(MusicForm form) {
        String url = form.getUrl() == null ? "" : form.getUrl().trim();
        if (!StringUtils.hasText(url)) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "音频地址不能为空");
        }
        if (url.length() > URL_MAX_LENGTH) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "音频地址最长 " + URL_MAX_LENGTH + " 字");
        }
        if (!url.startsWith("http://") && !url.startsWith("https://") && !url.startsWith("/")) {
            throw new BusinessException(ResultCode.PARAM_ERROR,
                    "音频地址必须是 http(s):// 开头的完整地址，或以 / 开头的站内路径");
        }
        return url;
    }

    /**
     * 可选文本归一化：空白 → null，并卡长度。
     * 【为什么空串要变成 null】前端清空输入框提交的是 ""，而数据库里"没有值"
     * 只有一种表示：NULL。混着存的话，以后写查询就得同时考虑 "" 和 null（IS NULL OR = ''），
     * 迟早有人漏掉一种 —— 结果是"清空歌手之后这首歌在某个查询里还出现"。
     */
    private String normalizeOptional(String value, int maxLength, String fieldName) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.length() > maxLength) {
            throw new BusinessException(ResultCode.PARAM_ERROR, fieldName + "最长 " + maxLength + " 字");
        }
        return trimmed;
    }

    /** 状态只允许 0 / 1；不静默纠正（静默纠正会让前端传错值也能"保存成功"） */
    private Integer requireValidStatus(Integer status) {
        if (status != Music.STATUS_HIDDEN && status != Music.STATUS_VISIBLE) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "状态只能是 0(隐藏) 或 1(显示)");
        }
        return status;
    }

    private Music requireMusic(Long id) {
        Music music = id == null ? null : musicMapper.selectById(id);
        if (music == null) {
            throw new BusinessException(ResultCode.MUSIC_NOT_FOUND);
        }
        return music;
    }

    /** 实体 -> VO */
    private MusicVO toVO(Music music) {
        MusicVO vo = new MusicVO();
        vo.setId(music.getId());
        vo.setTitle(music.getTitle());
        vo.setArtist(music.getArtist());
        vo.setUrl(music.getUrl());
        vo.setCover(music.getCover());
        vo.setLyrics(music.getLyrics());
        vo.setSort(music.getSort());
        vo.setStatus(music.getStatus());
        vo.setCreateTime(music.getCreateTime());
        return vo;
    }
}
