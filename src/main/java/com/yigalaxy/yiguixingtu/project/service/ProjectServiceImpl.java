package com.yigalaxy.yiguixingtu.project.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.yigalaxy.yiguixingtu.audit.AuditTarget;
import com.yigalaxy.yiguixingtu.audit.OperationAction;
import com.yigalaxy.yiguixingtu.audit.OperationLogRecorder;
import com.yigalaxy.yiguixingtu.common.ResultCode;
import com.yigalaxy.yiguixingtu.common.cache.ContentCacheVersion;
import com.yigalaxy.yiguixingtu.common.exception.BusinessException;
import com.yigalaxy.yiguixingtu.config.RedisConfig;
import com.yigalaxy.yiguixingtu.project.dto.ProjectForm;
import com.yigalaxy.yiguixingtu.project.dto.ProjectVO;
import com.yigalaxy.yiguixingtu.project.entity.Project;
import com.yigalaxy.yiguixingtu.project.mapper.ProjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.stream.Collectors;

/**
 * =====================================================================
 * 项目服务实现
 *
 * 【它和 FriendLinkServiceImpl 的差异只有一处真正值得说："至少填一个地址"】
 *
 *   项目的 url（在线演示）与 repo（代码仓库）各自都可以为空：
 *   有些项目只有仓库（还没部署上线），有些只有演示地址（内部项目不给源码）。
 *   但两个都为空的项目卡片是【点不出任何东西】的 —— 访客看到一张写着名字的卡片，
 *   点哪儿都没反应，只会以为它坏了。
 *   所以：create / update 里都要校验"至少有一个"。
 *
 *   【为什么写在 Service 而不是 DTO 上】
 *     这条规则跨两个字段，单字段的 @NotBlank 表达不了。硬要放在 DTO 层面，
 *     得自己写一个类级校验注解（注解 + 校验器两个类），而收益只是
 *     "早几百微秒失败一次"。写在 Service 里还有一个额外好处：
 *     它是所有调用路径（Controller / 定时任务 / 数据导入脚本）的必经之地。
 *
 *   【更新时要注意"这次提交没改那个字段"的情况】
 *     校验用的是【提交上来的值】，所以编辑时如果只传了 repo、没传 url，
 *     那么"url 为空"这件事会不会误判成"两个都空"？
 *     会的 —— 但这里刻意【不做"缺省即保留"】：本项目所有编辑接口都是
 *     "整份表单覆盖式提交"（前端把当前表单的全部字段一起 PUT 上来，
 *      和文章编辑、标签编辑完全一致）。
 *     所以缺字段 = 用户把它清空了，就该按"清空后至少还剩一个"来校验。
 *     如果哪天改成 PATCH 式的部分更新，这条校验必须跟着改成"合并之后再看"。
 *
 * 【其余部分与友链逐行同构】排序与状态缺省行为、空串归一化成 null、
 *   LambdaUpdateWrapper 显式 SET（保证"清空"真的写成 NULL）、
 *   内容缓存版本号 + 审计 —— 每处的"为什么"都写在 FriendLinkServiceImpl 里，
 *   这里不重复抄一遍，只在真正有差异的地方（下面标了 ⚠️）展开。
 * =====================================================================
 */
@Slf4j
@Service
public class ProjectServiceImpl implements ProjectService {

    /** 项目名称长度上限，与 DTO 校验、数据库列长度三处一致 */
    private static final int NAME_MAX_LENGTH = 100;

    /** 简介长度上限（与列长度一致） */
    private static final int DESCRIPTION_MAX_LENGTH = 500;

    /** 地址类字段（url / repo / cover）的长度上限（与列长度一致） */
    private static final int URL_MAX_LENGTH = 255;

    /** 技术栈长度上限（与列长度一致） */
    private static final int TECH_MAX_LENGTH = 200;

    private final ProjectMapper projectMapper;

    /** 内容缓存版本号：任何项目写操作都要推进它，否则前台要等 5 分钟 TTL 才更新 */
    private final ContentCacheVersion contentCacheVersion;

    /** 操作审计：后台的增删改都要留痕 */
    private final OperationLogRecorder operationLogRecorder;

    public ProjectServiceImpl(ProjectMapper projectMapper,
                              ContentCacheVersion contentCacheVersion,
                              OperationLogRecorder operationLogRecorder) {
        this.projectMapper = projectMapper;
        this.contentCacheVersion = contentCacheVersion;
        this.operationLogRecorder = operationLogRecorder;
    }

    // =================================================================
    //  一、读
    // =================================================================

    /**
     * 前台项目列表（只含显示的），带缓存。
     * key 里只有一个版本号：这个查询没有任何参数，全站就这一份"显示的项目列表"。
     */
    @Override
    @Cacheable(cacheNames = RedisConfig.CACHE_PROJECT_LIST, key = "@contentCacheVersion.current()")
    public List<ProjectVO> listVisible() {
        return query(Project.STATUS_VISIBLE);
    }

    @Override
    public List<ProjectVO> listAll() {
        return query(null);
    }

    /**
     * 真正的查询。
     *
     * @param status 只要这个状态的数据；传 null 表示"全部"（后台列表）
     */
    private List<ProjectVO> query(Integer status) {
        LambdaQueryWrapper<Project> wrapper = new LambdaQueryWrapper<Project>()
                .orderByAsc(Project::getSort)
                // 第二段排序不能省：sort 相同的行否则由 MySQL 自行决定顺序，
                // 表现是"刷新一下，项目卡片换了位置"
                .orderByAsc(Project::getId);

        // 【⚠️ 这里不能写成 eq(Project::getStatus, status) 而不判空】
        //   MyBatis-Plus 不会把 null 当成"不加这个条件"，它会生成 status = NULL ——
        //   一个永远不成立的条件，于是后台列表恒为空。
        //   这种 bug 不报错、只是"查不到数据"，最难发现。
        if (status != null) {
            wrapper.eq(Project::getStatus, status);
        }

        return projectMapper.selectList(wrapper).stream()
                .map(this::toVO)
                .collect(Collectors.toList());
    }

    // =================================================================
    //  二、写（仅管理员）
    // =================================================================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long create(ProjectForm form) {
        String name = normalizeName(form);
        String url = normalizeUrl(form.getUrl(), URL_MAX_LENGTH, "在线地址");
        String repo = normalizeUrl(form.getRepo(), URL_MAX_LENGTH, "仓库地址");

        // ⚠️ 本项目特有的一条业务规则（理由见类注释）
        requireAtLeastOneAddress(url, repo);

        Project project = new Project();
        project.setName(name);
        project.setDescription(normalizeOptional(form.getDescription(), DESCRIPTION_MAX_LENGTH, "项目简介"));
        project.setUrl(url);
        project.setRepo(repo);
        project.setCover(normalizeOptional(form.getCover(), URL_MAX_LENGTH, "封面图地址"));
        project.setTech(normalizeOptional(form.getTech(), TECH_MAX_LENGTH, "技术栈"));
        project.setSort(form.getSort() == null ? 0 : form.getSort());
        project.setStatus(form.getStatus() == null ? Project.STATUS_VISIBLE : requireValidStatus(form.getStatus()));
        project.setDeleted(0);

        projectMapper.insert(project);

        contentCacheVersion.bump();

        operationLogRecorder.record(OperationAction.CREATE_PROJECT, AuditTarget.PROJECT,
                project.getId(), "项目=" + name);

        log.info("新建项目: id={}, name={}", project.getId(), name);
        return project.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void update(Long id, ProjectForm form) {
        Project exist = requireProject(id);

        String name = normalizeName(form);
        String url = normalizeUrl(form.getUrl(), URL_MAX_LENGTH, "在线地址");
        String repo = normalizeUrl(form.getRepo(), URL_MAX_LENGTH, "仓库地址");

        // ⚠️ 编辑同样要校验：把 url 清空、repo 也空着提交，
        //    就会留下一个"点不出任何东西"的项目卡片
        requireAtLeastOneAddress(url, repo);

        // 逐字段显式 SET：传 null 会真的写成 NULL（updateById 会跳过 null 字段，
        // 于是"清空封面 / 清空仓库地址"永远不生效，用户以为界面卡了）
        projectMapper.update(null, new LambdaUpdateWrapper<Project>()
                .eq(Project::getId, id)
                .set(Project::getName, name)
                .set(Project::getDescription,
                        normalizeOptional(form.getDescription(), DESCRIPTION_MAX_LENGTH, "项目简介"))
                .set(Project::getUrl, url)
                .set(Project::getRepo, repo)
                .set(Project::getCover, normalizeOptional(form.getCover(), URL_MAX_LENGTH, "封面图地址"))
                .set(Project::getTech, normalizeOptional(form.getTech(), TECH_MAX_LENGTH, "技术栈"))
                .set(Project::getSort, form.getSort() == null ? exist.getSort() : form.getSort())
                .set(Project::getStatus, form.getStatus() == null
                        ? exist.getStatus() : requireValidStatus(form.getStatus())));

        contentCacheVersion.bump();

        String detail = name.equals(exist.getName())
                ? "项目=" + name
                : "项目 " + exist.getName() + " → " + name;
        operationLogRecorder.record(OperationAction.UPDATE_PROJECT, AuditTarget.PROJECT, id, detail);

        log.info("编辑项目: id={}, {} -> {}", id, exist.getName(), name);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        Project exist = requireProject(id);

        projectMapper.deleteById(id);   // @TableLogic：UPDATE project SET deleted = 1

        contentCacheVersion.bump();

        // detail 里留项目名快照：删完之后前台、后台列表都看不到它，
        // 只有审计能回答"当时删掉的是哪个项目"
        operationLogRecorder.record(OperationAction.DELETE_PROJECT, AuditTarget.PROJECT, id,
                "项目=" + exist.getName());

        log.info("删除项目: id={}, name={}", id, exist.getName());
    }

    // =================================================================
    //  三、私有工具
    // =================================================================

    private String normalizeName(ProjectForm form) {
        String name = form.getName() == null ? "" : form.getName().trim();
        if (!StringUtils.hasText(name)) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "项目名称不能为空");
        }
        if (name.length() > NAME_MAX_LENGTH) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "项目名称最长 " + NAME_MAX_LENGTH + " 字");
        }
        return name;
    }

    /**
     * 可空 URL 的归一化：空白 → null，非空则必须是 http(s) 开头。
     *
     * 【为什么可以不填，但填了就必须合法】
     *   空 = "这个项目没有在线地址"，是正当状态；
     *   而"填了 ftp:// 或 javascript:"不是"另一种正当状态"，是填错了。
     *   两件事必须分开处理 —— 把非法值静默丢成 null，用户会以为"我明明填了"。
     *
     * 【@Pattern 已经拦过一遍，这里为什么再拦】
     *   Service 是所有调用路径的必经之地（定时任务、导入脚本、别的 Service 都绕过 DTO），
     *   README「接口安全约定」里"双重校验"说的就是这件事。
     */
    private String normalizeUrl(String value, int maxLength, String fieldName) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        String url = value.trim();
        if (url.length() > maxLength) {
            throw new BusinessException(ResultCode.PARAM_ERROR, fieldName + "最长 " + maxLength + " 字");
        }
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            throw new BusinessException(ResultCode.PARAM_ERROR, fieldName + "必须以 http:// 或 https:// 开头");
        }
        return url;
    }

    /**
     * ⚠️ 项目特有：在线地址与仓库地址至少要有一个。
     *
     * 【提示语里为什么把两个字段名都写出来】
     *   只说"地址不能为空"的话，用户看着表单里两个地址框会不知道说哪一个。
     */
    private void requireAtLeastOneAddress(String url, String repo) {
        if (url == null && repo == null) {
            throw new BusinessException(ResultCode.PARAM_ERROR,
                    "在线地址和仓库地址至少要填一个，否则项目卡片点不出任何东西");
        }
    }

    /** 可选文本字段归一化：空白 → null（前端清空输入框提交的是 ""，不是 null），并卡长度 */
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
        if (status != Project.STATUS_HIDDEN && status != Project.STATUS_VISIBLE) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "状态只能是 0(隐藏) 或 1(显示)");
        }
        return status;
    }

    private Project requireProject(Long id) {
        Project project = id == null ? null : projectMapper.selectById(id);
        if (project == null) {
            throw new BusinessException(ResultCode.PROJECT_NOT_FOUND);
        }
        return project;
    }

    /** 实体 -> VO */
    private ProjectVO toVO(Project project) {
        ProjectVO vo = new ProjectVO();
        vo.setId(project.getId());
        vo.setName(project.getName());
        vo.setDescription(project.getDescription());
        vo.setUrl(project.getUrl());
        vo.setRepo(project.getRepo());
        vo.setCover(project.getCover());
        vo.setTech(project.getTech());
        vo.setSort(project.getSort());
        vo.setStatus(project.getStatus());
        vo.setCreateTime(project.getCreateTime());
        return vo;
    }
}
