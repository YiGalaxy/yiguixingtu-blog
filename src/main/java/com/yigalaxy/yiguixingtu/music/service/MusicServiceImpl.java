package com.yigalaxy.yiguixingtu.music.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.yigalaxy.yiguixingtu.article.mapper.ArticleAttachmentMapper;
import com.yigalaxy.yiguixingtu.article.mapper.ArticleMapper;
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
import com.yigalaxy.yiguixingtu.upload.UploadedFileCleaner;
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
 * ③ ⚠️ 删除时【会】把磁盘上的音频文件清掉 —— 但只在确认没有别人引用它之后
 *    这条在 2026-09 之前是反过来的（当时刻意不删文件、只标记数据库行）。
 *    改掉的原因与判断依据见 delete 方法的长注释（一句话：音乐的文件实际上
 *    与记录一一对应，而"只标记不删"会让 uploads/music/ 只涨不落）。
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

    /**
     * 「已上传文件」这一侧的工具：把 url 翻译成对象 key、按 key 删文件、
     * 并且保证删除发生在【事务提交之后】（见 UploadedFileCleaner.deleteAfterCommit）。
     * 它属于 upload 包：音乐模块只说"这个地址对应的文件要不要清掉"，
     * 字符串处理与磁盘操作不在业务代码里。
     */
    private final UploadedFileCleaner uploadedFileCleaner;

    /**
     * 文章 Mapper：用来问"有没有文章的正文/封面引用了这个音频地址"。
     *
     * 【为什么音乐模块要碰文章的表】音频地址是同源的 {@code /uploads/music/...}，
     *   而文章正文是一段 Markdown —— 站长完全可以在里面嵌一段
     *   {@code <audio src="/uploads/music/xxx.mp3">}（本站上传的音频、
     *   本站的静态映射，嵌进去就能播）。那这条音频文件就不再是"音乐专属"的了。
     *   跨模块直接用对方 Mapper 查询在本项目里有先例：ArticleServiceImpl 就注入
     *   CategoryMapper 来取分类名 —— 单体的一个应用里，这比再造一层"跨模块服务"
     *   要直白得多（SQL 本身写在 ArticleMapper 上，归属仍然清楚）。
     */
    private final ArticleMapper articleMapper;

    /**
     * 附件 Mapper：用来问"有没有文章的【附件行】引用了这个音频地址"。
     * 附件的地址只要求落在本站上传目录之内、没有强制在 attachment/ 子目录，
     * 所以理论上可以指向 music/ 下的文件 —— 见 ArticleAttachmentMapper.countReferencing。
     */
    private final ArticleAttachmentMapper articleAttachmentMapper;

    public MusicServiceImpl(MusicMapper musicMapper,
                            ContentCacheVersion contentCacheVersion,
                            OperationLogRecorder operationLogRecorder,
                            UploadedFileCleaner uploadedFileCleaner,
                            ArticleMapper articleMapper,
                            ArticleAttachmentMapper articleAttachmentMapper) {
        this.musicMapper = musicMapper;
        this.contentCacheVersion = contentCacheVersion;
        this.operationLogRecorder = operationLogRecorder;
        this.uploadedFileCleaner = uploadedFileCleaner;
        this.articleMapper = articleMapper;
        this.articleAttachmentMapper = articleAttachmentMapper;
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

    /**
     * 删除音乐（逻辑删除数据库行），并顺手清掉它独占的音频文件。
     *
     * =====================================================================
     * 【2026-09 的行为变化：原来"删歌不删文件"，现在会删】
     *
     * 一、为什么改（原来那条理由为什么不再站得住）
     *   原来不删的理由是"同一个文件可能被多条记录引用，而删文件不可逆"
     *   （见旧版本的 Music 实体注释）。但对着代码看，这个风险其实是可控的：
     *     · 音乐的 url 由站长手动填，两条记录共用同一个地址只可能来自
     *       "复制一条改名字忘了换地址"这类手滑，不是正常流程；
     *     · 而"只标记不删"的代价是确定的：uploads/music/ 只涨不落，
     *       反复试听、换歌就会不断堆积几百 KB～20MB 的孤儿文件，
     *       而它们是【没有任何入口能再访问到】的死数据。
     *   ⇒ 所以改成"能判断就判断，判断完再删"：把不可逆的那部分风险，
     *     用一次引用检查消掉，而不是用"永远不删"来回避。
     *
     * 二、★ 删之前必须问清的三件事（任何一件为真就不删文件）
     *   ① 还有别的【曲目】用着这个文件吗？—— music 表内查（MusicMapper.countOthersReferencing）
     *   ② 有【文章】在正文或封面里引用了这个地址吗？—— 正文可以嵌
     *      {@code <audio src="/uploads/music/...">}（ArticleMapper.countReferencing）
     *   ③ 有【文章的附件行】指向这个地址吗？—— 附件的地址只要求"在本站上传目录内"
     *      （ArticleAttachmentMapper.countReferencing）
     *   三处都问完、都为零，才认为这个文件是这一首歌独占的。
     *   判断偏保守：多认一次引用只是少删一个文件（浪费点磁盘），
     *   漏认一次就是删掉别人正在用的资源、而且【不可恢复】—— 两个方向的代价不对称。
     *
     * 三、url 是外链时怎么办
     *   {@code music.url} 走的是 MEDIA_URL 白名单，允许 http(s) 外链
     *   （音频放在自己的对象存储 / CDN 上是常见做法）。那种地址
     *   {@code toObjectKey} 会返回 null，我们【一个字节都不碰】——
     *   去"删"一个不属于自己的地址，轻则删错路径，重则删到别的东西上。
     *
     * 四、删除的时机：事务提交之后
     *   见 UploadedFileCleaner.deleteAfterCommit 的注释：删文件不可逆、
     *   而事务可能回滚，所以先让"数据库这一步"落地成功再动磁盘。
     *   最坏的结果只是"行没了、文件没删掉"（留个垃圾文件 + 一条日志），
     *   而不是"行还在、文件没了"（前台一个播不出来的曲目，且找不回文件）。
     *
     * 五、数据库行仍然是【逻辑删除】（deleted = 1），没有改成物理删除
     *   两点考虑：① 审计表之外还留着一份"这首歌当时叫什么"的快照；
     *   ② 万一站长手工恢复那一行（目前唯一的恢复方式是直接改库），
     *      只要那个文件没被别人接手，就仍然能播 —— 这也是"引用检查"
     *      要排除【已删除】行的原因：已删除的行不算"还有人要用"
     *      （那首歌自己都不显示了），否则文件会永远清理不掉。
     * =====================================================================
     */
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

        // ---- 清理这个文件（如果它是本站的、而且没人再用）----
        String objectKey = uploadedFileCleaner.toObjectKey(exist.getUrl());
        if (objectKey == null) {
            // 外链（或任何不是本项目上传目录的地址）：不碰任何文件。
            // 这不是"漏了"，而是正确的边界 —— 我们只清理自己生成的文件
            log.info("删除音乐: id={}, title={}（url 不是本项目的上传地址，不删除任何文件）",
                    id, exist.getTitle());
            return;
        }

        if (referencedByOthers(objectKey, id)) {
            // 日志把 key 也带上：将来站长问"为什么删了歌磁盘没变小"，
            // 这行日志能直接回答"它当时还被谁用着"
            log.info("删除音乐: id={}, title={}（文件仍被别处引用，跳过删除）objectKey={}",
                    id, exist.getTitle(), objectKey);
            return;
        }

        log.info("删除音乐: id={}, title={}（音频文件将在事务提交后删除）objectKey={}",
                id, exist.getTitle(), objectKey);
        uploadedFileCleaner.deleteAfterCommit(List.of(objectKey));
    }

    /**
     * 这个上传文件还有没有别的地方在用？（曲目 / 文章正文与封面 / 文章附件，三处都查）
     *
     * 【为什么三处都要查】见 delete 的长注释第二点。三处的 SQL 各自写在自己那张表的
     * Mapper 上（MusicMapper / ArticleMapper / ArticleAttachmentMapper），
     * 这里只负责把结论合起来 —— 判断规则集中在这一个方法里，读的人不用跳三个文件。
     *
     * 【⚠️ 与 ArticleServiceImpl.remove 里那段逻辑的关系】
     *   那边也有一份"这个文件还有没有别人在用"的判断，但两边的"自己"不同
     *   （那边要排除"正在删的这篇文章"，这边要排除"正在删的这首歌"），
     *   而且查的表也不完全一样（那边不查 music 表，这边要查）。
     *   强行抽一个共用组件就得传"我是谁、我在哪张表"这样的参数，
     *   为一个只有几行的判断引入一层间接、反而更难读 —— 所以各自写清楚。
     *
     * @param objectKey 上传目录里的对象 key
     * @param musicId   正在删除的曲目 id（它自己不算"别人"）
     */
    private boolean referencedByOthers(String objectKey, Long musicId) {
        if (musicMapper.countOthersReferencing(musicId, objectKey) > 0) {
            return true;
        }
        if (articleMapper.countReferencing(objectKey) > 0) {
            return true;
        }
        return articleAttachmentMapper.countReferencing(objectKey) > 0;
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
