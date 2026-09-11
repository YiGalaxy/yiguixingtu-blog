package com.yigalaxy.yiguixingtu.favorite.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.yigalaxy.yiguixingtu.audit.AuditTarget;
import com.yigalaxy.yiguixingtu.audit.OperationAction;
import com.yigalaxy.yiguixingtu.audit.OperationLogRecorder;
import com.yigalaxy.yiguixingtu.common.ResultCode;
import com.yigalaxy.yiguixingtu.common.cache.ContentCacheVersion;
import com.yigalaxy.yiguixingtu.common.exception.BusinessException;
import com.yigalaxy.yiguixingtu.config.RedisConfig;
import com.yigalaxy.yiguixingtu.favorite.dto.FavoriteForm;
import com.yigalaxy.yiguixingtu.favorite.dto.FavoriteVO;
import com.yigalaxy.yiguixingtu.favorite.entity.Favorite;
import com.yigalaxy.yiguixingtu.favorite.mapper.FavoriteMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.stream.Collectors;

/**
 * =====================================================================
 * 收藏服务实现
 *
 * 【它和友链 / 项目实现的三处差异】
 *
 * ① 必填的是 url 而不是"两个地址至少一个"
 *    收藏的对象就是一个网址 —— 没有地址的收藏没有任何意义。
 *    （项目允许"只有仓库或只有在线的"，因为它的两个字段回答不同的问题；
 *      收藏只有一个目标。）
 *
 * ② category 是自由文本，既不做关联也不做校验
 *    它【不是】category 表的 id（那张表是文章栏目，有唯一索引、
 *    删之前还要数"还有几篇文章在用"）。完整推导见 V9 迁移脚本的注释。
 *    这里只做长度与空白归一化：空白 → null（空 = 未分组）。
 *
 * ③ title 不做任何抓取
 *    后端不会去访问目标网页读 <title>：那要发外部请求（SSRF 风险、慢、
 *    对方改版或页面无标题时还拿不到），而这张表是站长自己维护的，
 *    填标题时顺手写一个更准。
 *
 * 【其余逐行同构】排序与状态缺省（新建给默认值、编辑保持原值）、
 * LambdaUpdateWrapper 显式 SET（保证"清空"真的写成 NULL）、
 * 内容缓存版本号 + 审计 —— 每处的"为什么"都写在 FriendLinkServiceImpl /
 * ProjectServiceImpl 里，这里不重复抄。
 * =====================================================================
 */
@Slf4j
@Service
public class FavoriteServiceImpl implements FavoriteService {

    /** 标题长度上限，与 DTO 校验、数据库列长度三处一致 */
    private static final int TITLE_MAX_LENGTH = 100;

    /** 地址长度上限（与列长度一致） */
    private static final int URL_MAX_LENGTH = 255;

    /** 备注长度上限（与列长度一致） */
    private static final int DESCRIPTION_MAX_LENGTH = 500;

    /** 分组名长度上限（与列长度一致） */
    private static final int CATEGORY_MAX_LENGTH = 50;

    private final FavoriteMapper favoriteMapper;

    /** 内容缓存版本号：任何收藏写操作都要推进它，否则前台要等 TTL 才更新 */
    private final ContentCacheVersion contentCacheVersion;

    /** 操作审计：后台的增删改都要留痕 */
    private final OperationLogRecorder operationLogRecorder;

    public FavoriteServiceImpl(FavoriteMapper favoriteMapper,
                               ContentCacheVersion contentCacheVersion,
                               OperationLogRecorder operationLogRecorder) {
        this.favoriteMapper = favoriteMapper;
        this.contentCacheVersion = contentCacheVersion;
        this.operationLogRecorder = operationLogRecorder;
    }

    // =================================================================
    //  一、读
    // =================================================================

    /** 前台收藏列表（只含显示的），带缓存；key 里只有版本号（这个查询没有参数） */
    @Override
    @Cacheable(cacheNames = RedisConfig.CACHE_FAVORITE_LIST, key = "@contentCacheVersion.current()")
    public List<FavoriteVO> listVisible() {
        return query(Favorite.STATUS_VISIBLE);
    }

    @Override
    public List<FavoriteVO> listAll() {
        return query(null);
    }

    /**
     * 真正的查询：一次取回全部（含分组），排序在后端只做一次。
     * 分组交给前端 —— 后端不 GROUP BY 的理由见 FavoriteMapper 的注释
     * （分组名是自由文本，SQL 分组与前端分组在"首尾空格 / 大小写"上会不一致）。
     *
     * @param status 只要这个状态的数据；传 null 表示"全部"（后台列表）
     */
    private List<FavoriteVO> query(Integer status) {
        LambdaQueryWrapper<Favorite> wrapper = new LambdaQueryWrapper<Favorite>()
                .orderByAsc(Favorite::getSort)
                .orderByAsc(Favorite::getId);

        // 不能写成 eq(Favorite::getStatus, status) 而不判空：MyBatis-Plus 会生成
        // status = NULL（永远不成立），于是后台列表恒为空 —— 不报错、只是查不到数据
        if (status != null) {
            wrapper.eq(Favorite::getStatus, status);
        }

        return favoriteMapper.selectList(wrapper).stream()
                .map(this::toVO)
                .collect(Collectors.toList());
    }

    // =================================================================
    //  二、写（仅管理员）
    // =================================================================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long create(FavoriteForm form) {
        Favorite favorite = new Favorite();
        favorite.setTitle(normalizeTitle(form));
        favorite.setUrl(normalizeUrl(form));
        favorite.setDescription(normalizeOptional(form.getDescription(), DESCRIPTION_MAX_LENGTH, "备注"));
        favorite.setCategory(normalizeOptional(form.getCategory(), CATEGORY_MAX_LENGTH, "分组名"));
        favorite.setSort(form.getSort() == null ? 0 : form.getSort());
        favorite.setStatus(form.getStatus() == null
                ? Favorite.STATUS_VISIBLE : requireValidStatus(form.getStatus()));
        favorite.setDeleted(0);

        favoriteMapper.insert(favorite);

        contentCacheVersion.bump();

        operationLogRecorder.record(OperationAction.CREATE_FAVORITE, AuditTarget.FAVORITE,
                favorite.getId(), "收藏=" + favorite.getTitle());

        log.info("新建收藏: id={}, title={}, url={}",
                favorite.getId(), favorite.getTitle(), favorite.getUrl());
        return favorite.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void update(Long id, FavoriteForm form) {
        Favorite exist = requireFavorite(id);

        String title = normalizeTitle(form);

        // 逐字段显式 SET：传 null 会真的写成 NULL。
        // 收藏这个模块尤其明显 —— 分组名和备注都是"随时可能清空"的字段，
        // 用 updateById 的话它们会"清不掉"，用户会以为界面卡了
        favoriteMapper.update(null, new LambdaUpdateWrapper<Favorite>()
                .eq(Favorite::getId, id)
                .set(Favorite::getTitle, title)
                .set(Favorite::getUrl, normalizeUrl(form))
                .set(Favorite::getDescription,
                        normalizeOptional(form.getDescription(), DESCRIPTION_MAX_LENGTH, "备注"))
                .set(Favorite::getCategory,
                        normalizeOptional(form.getCategory(), CATEGORY_MAX_LENGTH, "分组名"))
                .set(Favorite::getSort, form.getSort() == null ? exist.getSort() : form.getSort())
                .set(Favorite::getStatus, form.getStatus() == null
                        ? exist.getStatus() : requireValidStatus(form.getStatus())));

        contentCacheVersion.bump();

        String detail = title.equals(exist.getTitle())
                ? "收藏=" + title
                : "收藏 " + exist.getTitle() + " → " + title;
        operationLogRecorder.record(OperationAction.UPDATE_FAVORITE, AuditTarget.FAVORITE, id, detail);

        log.info("编辑收藏: id={}, {} -> {}", id, exist.getTitle(), title);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        Favorite exist = requireFavorite(id);

        favoriteMapper.deleteById(id);   // @TableLogic：UPDATE favorite SET deleted = 1

        contentCacheVersion.bump();

        // detail 留标题快照：删完之后前台、后台列表都看不到它，
        // 只有审计记录能回答"当时删掉的是哪一条收藏"
        operationLogRecorder.record(OperationAction.DELETE_FAVORITE, AuditTarget.FAVORITE, id,
                "收藏=" + exist.getTitle());

        log.info("删除收藏: id={}, title={}", id, exist.getTitle());
    }

    // =================================================================
    //  三、私有工具
    // =================================================================

    private String normalizeTitle(FavoriteForm form) {
        String title = form.getTitle() == null ? "" : form.getTitle().trim();
        if (!StringUtils.hasText(title)) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "标题不能为空");
        }
        if (title.length() > TITLE_MAX_LENGTH) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "标题最长 " + TITLE_MAX_LENGTH + " 字");
        }
        return title;
    }

    /** 地址归一化：必填 + 必须 http(s)（DTO 的 @Pattern 已拦一遍，这里是必经之地的第二道） */
    private String normalizeUrl(FavoriteForm form) {
        String url = form.getUrl() == null ? "" : form.getUrl().trim();
        if (!StringUtils.hasText(url)) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "地址不能为空");
        }
        if (url.length() > URL_MAX_LENGTH) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "地址最长 " + URL_MAX_LENGTH + " 字");
        }
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "地址必须以 http:// 或 https:// 开头");
        }
        return url;
    }

    /**
     * 可选文本归一化：空白 → null，并卡长度。
     * 【为什么空串要变成 null】前端清空输入框提交的是 ""，而数据库里"没有值"
     * 只有一种表示：NULL。混着存的话，以后写查询就得同时考虑 "" 和 null（IS NULL OR = ''），
     * 迟早有人漏掉一种 —— 结果是"清空分组之后这条记录在某个查询里还出现"。
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
        if (status != Favorite.STATUS_HIDDEN && status != Favorite.STATUS_VISIBLE) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "状态只能是 0(隐藏) 或 1(显示)");
        }
        return status;
    }

    private Favorite requireFavorite(Long id) {
        Favorite favorite = id == null ? null : favoriteMapper.selectById(id);
        if (favorite == null) {
            throw new BusinessException(ResultCode.FAVORITE_NOT_FOUND);
        }
        return favorite;
    }

    /** 实体 -> VO */
    private FavoriteVO toVO(Favorite favorite) {
        FavoriteVO vo = new FavoriteVO();
        vo.setId(favorite.getId());
        vo.setTitle(favorite.getTitle());
        vo.setUrl(favorite.getUrl());
        vo.setDescription(favorite.getDescription());
        vo.setCategory(favorite.getCategory());
        vo.setSort(favorite.getSort());
        vo.setStatus(favorite.getStatus());
        vo.setCreateTime(favorite.getCreateTime());
        return vo;
    }
}
