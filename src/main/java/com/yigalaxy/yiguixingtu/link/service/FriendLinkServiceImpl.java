package com.yigalaxy.yiguixingtu.link.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.yigalaxy.yiguixingtu.audit.AuditTarget;
import com.yigalaxy.yiguixingtu.audit.OperationAction;
import com.yigalaxy.yiguixingtu.audit.OperationLogRecorder;
import com.yigalaxy.yiguixingtu.common.cache.ContentCacheVersion;
import com.yigalaxy.yiguixingtu.common.ResultCode;
import com.yigalaxy.yiguixingtu.common.exception.BusinessException;
import com.yigalaxy.yiguixingtu.config.RedisConfig;
import com.yigalaxy.yiguixingtu.link.dto.FriendLinkForm;
import com.yigalaxy.yiguixingtu.link.dto.FriendLinkVO;
import com.yigalaxy.yiguixingtu.link.entity.FriendLink;
import com.yigalaxy.yiguixingtu.link.mapper.FriendLinkMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.stream.Collectors;

/**
 * =====================================================================
 * 友链服务实现
 *
 * 【这个类里四处需要想清楚的地方】
 *
 * ① 缓存 key 为什么是 {@code @contentCacheVersion.current()}，而不是文章那个版本号
 *    友链和文章没有任何关系（没有任何表引用它）。若共用
 *    {@code article:page:version}，每加一条友链都会让文章列表 / 详情 / 归档 / RSS /
 *    分类 / 标签六份缓存一起作废 —— 功能不错，但纯属无谓的重新查库，
 *    而且排查时会让人误以为"文章那边的缓存为什么老在重建"。
 *    所以用 {@code ContentCacheVersion}：F5 四个内容模块共用它一个计数器
 *    （四个共用的理由写在那个类的注释里）。
 *
 * ② 编辑为什么用 {@code LambdaUpdateWrapper} 而不是 {@code updateById}
 *    updateById 只更新【非 null】字段 —— 这是 MyBatis-Plus 的默认策略，
 *    本来挺贴心，但在这里会出问题：管理员把"头像"清空、提交 avatar = null，
 *    updateById 会跳过这一列，于是头像根本删不掉，用户以为界面卡了。
 *    用 LambdaUpdateWrapper.set(...) 是显式声明"这几列就要改成这个值"，
 *    传 null 也会老老实实写成 NULL。
 *    （这个坑是文章模块先踩到的：ArticleServiceImpl.update 里有一段同样的说明，
 *      测试是 ArticleAdminTest.update_clearCover_shouldActuallySetNull。）
 *
 * ③ 排序与状态"不传"时的行为，与 tag / category 完全一致
 *    · sort：新建时 null → 0；编辑时 null → 保持原值（不传 ≠ 想改成 0）
 *    · status：新建时 null → 1（显示）；编辑时 null → 保持原状态
 *    两者都是"缺省即保留"，只有新建才需要给一个具体的默认值。
 *    测试各有一条用例钉住（FriendLinkTest ④ 与 ⑨）。
 *
 * ④ 空串归一化成 null
 *    可选字段（avatar / description）在数据库里"没有值"只有一种表示：NULL。
 *    而前端清空输入框提交上来的往往是 ""（Element Plus 的行为）——
 *    两者混着存，后人写查询时就得同时考虑 "" 和 null（`IS NULL OR = ''`），
 *    迟早有人漏掉一种。所以入口处统一归一化：空白 → null。
 * =====================================================================
 */
@Slf4j
@Service
public class FriendLinkServiceImpl implements FriendLinkService {

    /** 站点名称长度上限，与 DTO 校验、数据库列长度三处保持一致 */
    private static final int NAME_MAX_LENGTH = 50;

    /** 站点地址长度上限（与列长度一致） */
    private static final int URL_MAX_LENGTH = 255;

    /** 头像地址长度上限（与列长度一致） */
    private static final int AVATAR_MAX_LENGTH = 255;

    /** 简介长度上限（与列长度一致） */
    private static final int DESCRIPTION_MAX_LENGTH = 200;

    private final FriendLinkMapper friendLinkMapper;

    /**
     * 内容缓存版本号：任何友链写操作都要推进它，
     * 否则前台会一直显示旧列表直到 TTL（5 分钟）到期。
     */
    private final ContentCacheVersion contentCacheVersion;

    /** 操作审计：后台的增删改都要留痕（谁、什么时候、改了什么） */
    private final OperationLogRecorder operationLogRecorder;

    public FriendLinkServiceImpl(FriendLinkMapper friendLinkMapper,
                                 ContentCacheVersion contentCacheVersion,
                                 OperationLogRecorder operationLogRecorder) {
        this.friendLinkMapper = friendLinkMapper;
        this.contentCacheVersion = contentCacheVersion;
        this.operationLogRecorder = operationLogRecorder;
    }

    // =================================================================
    //  一、读
    // =================================================================

    /**
     * 前台友链列表（只含显示的），带缓存。
     *
     * 【缓存 key 里为什么只有一个版本号】
     *   这个查询没有任何参数 —— 全站就这一份"显示的友链列表"，
     *   所以 key = 版本号就够了（和 {@code CategoryServiceImpl.listAll} 一样）。
     *   {@code @contentCacheVersion} 是 SpEL 里"取容器里这个 bean 并调它的方法"的写法，
     *   bean 名由 Spring 按类名首字母小写推导；每次算 key 时现取一次版本号，
     *   所以刚 bump 过的版本立刻生效。
     */
    @Override
    @Cacheable(cacheNames = RedisConfig.CACHE_FRIEND_LINK_LIST, key = "@contentCacheVersion.current()")
    public List<FriendLinkVO> listVisible() {
        return query(FriendLink.STATUS_VISIBLE);
    }

    @Override
    public List<FriendLinkVO> listAll() {
        return query(null);
    }

    /**
     * 真正的查询。
     *
     * @param status 只要这个状态的数据；传 null 表示"全部"（后台列表）
     */
    private List<FriendLinkVO> query(Integer status) {
        LambdaQueryWrapper<FriendLink> wrapper = new LambdaQueryWrapper<FriendLink>()
                // 排序规则：先 sort 升序（人工指定的优先级），
                // 再 id 升序 —— 少了第二个条件时，sort 相同的那些行
                // 由 MySQL 自己决定顺序，表现是"刷新一下顺序就变了"
                .orderByAsc(FriendLink::getSort)
                .orderByAsc(FriendLink::getId);

        // 【注意 eq 的两种写法】这里不能用 status == null ? 不加条件 : eq(...) 之外的办法：
        // 直接 eq(FriendLink::getStatus, null) 在 MyBatis-Plus 里不会"自动忽略"，
        // 它会生成 status = NULL（永远不成立），于是后台列表变成空列表 ——
        // 一个不报错、只是"查不到数据"的 bug
        if (status != null) {
            wrapper.eq(FriendLink::getStatus, status);
        }

        return friendLinkMapper.selectList(wrapper).stream()
                .map(this::toVO)
                .collect(Collectors.toList());
    }

    // =================================================================
    //  二、写（仅管理员）
    // =================================================================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long create(FriendLinkForm form) {
        FriendLink link = new FriendLink();
        link.setName(normalizeName(form));
        link.setUrl(normalizeUrl(form));
        link.setAvatar(normalizeOptional(form.getAvatar(), AVATAR_MAX_LENGTH, "头像地址"));
        link.setDescription(normalizeOptional(form.getDescription(), DESCRIPTION_MAX_LENGTH, "简介"));
        // 新建：排序不传当 0，状态不传当"显示"（理由见类注释 ③）
        link.setSort(form.getSort() == null ? 0 : form.getSort());
        link.setStatus(form.getStatus() == null ? FriendLink.STATUS_VISIBLE : requireValidStatus(form.getStatus()));
        link.setDeleted(0);

        friendLinkMapper.insert(link);

        // 前台列表的缓存必须失效：不然新建的友链要等 5 分钟 TTL 才出现
        contentCacheVersion.bump();

        operationLogRecorder.record(OperationAction.CREATE_LINK, AuditTarget.LINK,
                link.getId(), "友链=" + link.getName());

        log.info("新建友链: id={}, name={}, url={}", link.getId(), link.getName(), link.getUrl());
        return link.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void update(Long id, FriendLinkForm form) {
        FriendLink exist = requireLink(id);

        String name = normalizeName(form);

        // 逐字段显式 SET：传 null 也会写成 NULL（理由见类注释 ②）。
        // sort / status 缺省时保持原值 —— "不传"和"想改成 0"必须能区分开
        friendLinkMapper.update(null, new LambdaUpdateWrapper<FriendLink>()
                .eq(FriendLink::getId, id)
                .set(FriendLink::getName, name)
                .set(FriendLink::getUrl, normalizeUrl(form))
                .set(FriendLink::getAvatar, normalizeOptional(form.getAvatar(), AVATAR_MAX_LENGTH, "头像地址"))
                .set(FriendLink::getDescription, normalizeOptional(form.getDescription(), DESCRIPTION_MAX_LENGTH, "简介"))
                .set(FriendLink::getSort, form.getSort() == null ? exist.getSort() : form.getSort())
                .set(FriendLink::getStatus, form.getStatus() == null
                        ? exist.getStatus() : requireValidStatus(form.getStatus())));

        contentCacheVersion.bump();

        // detail 里记下"从什么改成什么"：只记新名字的话，
        // 事后根本看不出这是一次改名，还是只调了排序（和标签/分类同一个取舍）
        String detail = name.equals(exist.getName())
                ? "友链=" + name
                : "友链 " + exist.getName() + " → " + name;
        operationLogRecorder.record(OperationAction.UPDATE_LINK, AuditTarget.LINK, id, detail);

        log.info("编辑友链: id={}, {} -> {}", id, exist.getName(), name);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        FriendLink exist = requireLink(id);

        // @TableLogic：这条语句实际是 UPDATE friend_link SET deleted = 1 WHERE id = ?
        // 物理行还在，所以"删错了能恢复"；而所有走 Mapper 的查询都会自动带上 deleted = 0
        friendLinkMapper.deleteById(id);

        contentCacheVersion.bump();

        // detail 里留下站点名快照：删掉之后前台查不到、后台列表也看不到它，
        // 只有审计记录能回答"当时删掉的是哪个站点"
        operationLogRecorder.record(OperationAction.DELETE_LINK, AuditTarget.LINK, id,
                "友链=" + exist.getName());

        log.info("删除友链: id={}, name={}", id, exist.getName());
    }

    // =================================================================
    //  三、私有工具
    // =================================================================

    /** 站点名归一化：去首尾空格 + 卡长度（@NotBlank 已经拦了纯空格，这里是第二道） */
    private String normalizeName(FriendLinkForm form) {
        String name = form.getName() == null ? "" : form.getName().trim();
        if (!StringUtils.hasText(name)) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "站点名称不能为空");
        }
        if (name.length() > NAME_MAX_LENGTH) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "站点名称最长 " + NAME_MAX_LENGTH + " 字");
        }
        return name;
    }

    /**
     * 站点地址归一化。
     *
     * 【为什么这里还要再判一次"必须以 http(s) 开头"】
     *   DTO 上的 @Pattern 已经拦了同样的东西，看起来是重复。
     *   但 Service 是"所有调用路径的必经之地"：将来可能有人从定时任务、
     *   数据导入脚本、或者别的 Service 直接调它，那时候 DTO 校验根本不会执行。
     *   这条判断的代价是一次 startsWith，收益是"这个不变量不管从哪进来都成立"。
     *   （README「接口安全约定」里"双重校验"说的就是这件事。）
     */
    private String normalizeUrl(FriendLinkForm form) {
        String url = form.getUrl() == null ? "" : form.getUrl().trim();
        if (!StringUtils.hasText(url)) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "站点地址不能为空");
        }
        if (url.length() > URL_MAX_LENGTH) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "站点地址最长 " + URL_MAX_LENGTH + " 字");
        }
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "站点地址必须以 http:// 或 https:// 开头");
        }
        return url;
    }

    /**
     * 可选文本字段的归一化：空白 → null，并卡长度。
     *
     * 【为什么空串要变成 null】见类注释 ④。
     *
     * @param fieldName 出错提示里用的字段名（"头像地址"/"简介"），
     *                  这样提示能直接说清是哪一项超长了
     */
    private String normalizeOptional(String value, int maxLength, String fieldName) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.length() > maxLength) {
            throw new BusinessException(ResultCode.PARAM_ERROR,
                    fieldName + "最长 " + maxLength + " 字");
        }
        return trimmed;
    }

    /**
     * 状态取值校验：只允许 0 / 1。
     *
     * 【为什么要显式拒绝，而不是"不属于 0/1 就当 0"】
     *   静默纠正会让调用方永远发现不了自己传错了值 ——
     *   前端传了 status=2（比如复制了评论的三态代码），
     *   页面显示"已保存"，实际效果是"被隐藏了"，用户会以为是显示逻辑坏了。
     *   直接报错，问题在第一次调用时就暴露。
     */
    private Integer requireValidStatus(Integer status) {
        if (status != FriendLink.STATUS_HIDDEN && status != FriendLink.STATUS_VISIBLE) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "状态只能是 0(隐藏) 或 1(显示)");
        }
        return status;
    }

    private FriendLink requireLink(Long id) {
        FriendLink link = id == null ? null : friendLinkMapper.selectById(id);
        if (link == null) {
            // 404 而不是 403：这里的语义是"这条数据不存在"（连逻辑删除的也查不到），
            // 和"你没权限"是两件事 —— 权限在 Controller 那一层就已经判过了
            throw new BusinessException(ResultCode.LINK_NOT_FOUND);
        }
        return link;
    }

    /** 实体 -> VO（接口的形状与数据库的形状解耦，理由见 FriendLinkVO 的注释） */
    private FriendLinkVO toVO(FriendLink link) {
        FriendLinkVO vo = new FriendLinkVO();
        vo.setId(link.getId());
        vo.setName(link.getName());
        vo.setUrl(link.getUrl());
        vo.setAvatar(link.getAvatar());
        vo.setDescription(link.getDescription());
        vo.setSort(link.getSort());
        vo.setStatus(link.getStatus());
        vo.setCreateTime(link.getCreateTime());
        return vo;
    }
}
