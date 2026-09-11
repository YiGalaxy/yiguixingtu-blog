package com.yigalaxy.yiguixingtu.setting.service;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.yigalaxy.yiguixingtu.article.dto.ArticleQuery;
import com.yigalaxy.yiguixingtu.audit.AuditTarget;
import com.yigalaxy.yiguixingtu.audit.OperationAction;
import com.yigalaxy.yiguixingtu.audit.OperationLogRecorder;
import com.yigalaxy.yiguixingtu.common.ResultCode;
import com.yigalaxy.yiguixingtu.common.cache.ContentCacheVersion;
import com.yigalaxy.yiguixingtu.common.exception.BusinessException;
import com.yigalaxy.yiguixingtu.config.RedisConfig;
import com.yigalaxy.yiguixingtu.setting.dto.SettingForm;
import com.yigalaxy.yiguixingtu.setting.dto.SettingVO;
import com.yigalaxy.yiguixingtu.setting.entity.SiteSetting;
import com.yigalaxy.yiguixingtu.setting.mapper.SiteSettingMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * =====================================================================
 * 站点设置服务实现
 *
 * 【它的形状与 AboutServiceImpl 几乎一样，四处差异都是这一张表的性质决定的】
 *
 * ① 缺行兜底：【只有评论开关】给一个能用的值，其余留 null
 *    About 的兜底是"给 nickname 一个占位值、其余留 null"。这里比它更窄一层：
 *      · commentEnabled 给 true —— 它是【行为类】的：为 null 的后果不是
 *        "这块不显示"，而是前端不知道该不该渲染评论框（两边猜的方向还可能相反）。
 *        那是把"配置缺失"升级成了"功能损坏"
 *      · 其余（站点名 / 公告 / 两个备案号 / 版权 / 每页条数）留 null ——
 *        前端对 null 的语义本来就是"不渲染这一块 / 用自己的默认值"，
 *        正好是这时候该有的表现。
 *        ⚠️ 尤其【每页条数】故意不给后端默认值：那个 12 是前端的排版决策
 *        （3 列瀑布流 4 行），后端不该再写一份；而且它和 ArticleQuery 的
 *        DEFAULT_PAGE_SIZE(10) 语义不同，混在一起最容易被当成同一个数字。
 *        留 null 也是安全的 —— ArticleQuery 把 null 的 size 当成"没传"用默认值
 *
 * ② save 时"传了空串"要真的能清掉
 *    announcement / icpNumber / policeNumber / copyright 都是"随时可能被清空"的字段
 *    （比如备案号换了、公告下掉了）。所以更新走 LambdaUpdateWrapper 显式 SET，
 *    而不是 updateById（后者会跳过 null 字段，表现是"清空保存了但还显示着旧的"）。
 *
 * ③ 缓存与失效：和其余内容模块同一套
 *    key = 内容缓存版本号，保存后 bump 一次。
 *    【为什么它值得缓存】这份数据每一页都要读（页眉页脚、评论开关、首页分页），
 *    而它【只有管理员手工改的时候才变】—— 典型的"读多写极少"。
 *    它和友链 / 项目 / 收藏 / 关于 / 音乐共用 ContentCacheVersion 那一个计数器：
 *    代价只是"改一条友链会让设置缓存也重建一次"（一次多余的查库），
 *    换来的是一套失效逻辑而不是两套（共用的取舍见 ContentCacheVersion 的类注释）。
 *
 * ④ 它是唯一一个"字段各自独立"的内容模块
 *    其余模块都是一个"页面"：所有字段一起读、一起用。这里每个字段驱动界面上
 *    很不相同的一处（页脚、评论开关、分页大小），所以测试里对每个字段
 *    都单独有一条"它真的能改、能清空、能读到"的用例 —— 见 SiteSettingTest。
 * =====================================================================
 */
@Slf4j
@Service
public class SettingServiceImpl implements SettingService {

    /** 站点名长度上限，与 DTO 校验、数据库列长度三处一致 */
    private static final int SITE_NAME_MAX_LENGTH = 50;

    /** 公告长度上限（与 DTO 和列长度一致） */
    private static final int ANNOUNCEMENT_MAX_LENGTH = 500;

    /** 备案号长度上限（与 DTO 和列长度一致） */
    private static final int ICP_MAX_LENGTH = 50;

    /**
     * 公安网安备案号长度上限，与 {@link #ICP_MAX_LENGTH} 同为 50。
     * 【为什么单独一个常量、不直接复用 ICP_MAX_LENGTH】两者现在数值相同，
     * 但它们是【两列】：将来若某一边的列宽改了（比如公安号变得更长），
     * 复用同一个常量会让另一边的校验静默跟着漂 —— 而"校验宽于列宽"的后果是
     * 用户能存进去、MySQL 却截断或报错，属于最难查的一类偏差。
     * 这个数字与 DTO 上的 {@code @Size}、数据库列长度【三处一致】。
     */
    private static final int POLICE_MAX_LENGTH = 50;

    /** 版权文案长度上限（与 DTO 和列长度一致） */
    private static final int COPYRIGHT_MAX_LENGTH = 200;

    /** 那一行不存在时，评论开关给的默认值：开着（和"评论一直是开着的"这个现状一致） */
    private static final boolean DEFAULT_COMMENT_ENABLED = true;

    private final SiteSettingMapper settingMapper;

    /** 内容缓存版本号：保存后推进它，让前台立刻拿到新设置 */
    private final ContentCacheVersion contentCacheVersion;

    /** 操作审计：站点设置的每一次保存都要留痕（它能改的东西影响全站） */
    private final OperationLogRecorder operationLogRecorder;

    public SettingServiceImpl(SiteSettingMapper settingMapper,
                              ContentCacheVersion contentCacheVersion,
                              OperationLogRecorder operationLogRecorder) {
        this.settingMapper = settingMapper;
        this.contentCacheVersion = contentCacheVersion;
        this.operationLogRecorder = operationLogRecorder;
    }

    // =================================================================
    //  一、读（公开）
    // =================================================================

    /**
     * 取站点设置，带缓存。
     *
     * 【缓存 key 还是只有版本号】这份数据只有一份、没有参数，
     * 所以 key = 版本号就够（和 AboutServiceImpl.get、CategoryServiceImpl.listAll 一致）。
     */
    @Override
    @Cacheable(cacheNames = RedisConfig.CACHE_SITE_SETTING, key = "@contentCacheVersion.current()")
    public SettingVO get() {
        SiteSetting setting = settingMapper.selectById(SiteSetting.SINGLE_ROW_ID);
        if (setting == null) {
            // ⚠️ 这条日志很重要：它让"站点名怎么没了/评论怎么关了"在服务器日志里
            //    有一个明确答案，而不是让人从前端页面开始猜
            log.warn("站点设置那一行（id={}）在库里不存在，本次返回默认值；"
                    + "在后台点一次保存即可写回", SiteSetting.SINGLE_ROW_ID);
            return defaults();
        }
        return toVO(setting);
    }

    // =================================================================
    //  二、写（仅管理员）
    // =================================================================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void update(SettingForm form) {
        String siteName = normalizeSiteName(form);
        String announcement = normalizeOptional(form.getAnnouncement(), ANNOUNCEMENT_MAX_LENGTH, "公告");
        String icpNumber = normalizeOptional(form.getIcpNumber(), ICP_MAX_LENGTH, "备案号");
        // 公安备案号与 ICP 走【同一个】归一化方法（空白 → null、超长报错），
        // 只是长度常量与报错文案各用自己那份：两者是两列，混用常量会让
        // "哪一列被改宽了"这件事在校验上失去区分度（见 POLICE_MAX_LENGTH 的注释）
        String policeNumber = normalizeOptional(form.getPoliceNumber(), POLICE_MAX_LENGTH, "公安备案号");
        String copyright = normalizeOptional(form.getCopyright(), COPYRIGHT_MAX_LENGTH, "版权文案");
        int commentEnabled = normalizeCommentEnabled(form);
        int pageSize = normalizePageSize(form);

        // 逐字段显式 SET：传 null（或空串）会真的写成 NULL。
        // 这一条在本模块尤其重要：公告 / 两个备案号 / 版权都是"随时可能要清掉"的字段，
        // 用 updateById 会因为"跳过 null 字段"而清不掉，界面上看起来像"保存没生效"
        int affected = settingMapper.update(null, new LambdaUpdateWrapper<SiteSetting>()
                .eq(SiteSetting::getId, SiteSetting.SINGLE_ROW_ID)
                .set(SiteSetting::getSiteName, siteName)
                .set(SiteSetting::getAnnouncement, announcement)
                .set(SiteSetting::getCommentEnabled, commentEnabled)
                .set(SiteSetting::getIcpNumber, icpNumber)
                // ⚠️ policeNumber 也【必须】在这条显式 SET 链里：
                //    它是页脚上一个"换主体就可能要清掉"的号，漏在这里的表现是
                //    "后台清空了、页脚还挂着旧备案号"，而接口返回 code 200
                .set(SiteSetting::getPoliceNumber, policeNumber)
                .set(SiteSetting::getCopyright, copyright)
                .set(SiteSetting::getPageSize, pageSize));

        // 受影响行数为 0 = 那一行不存在。
        // 【为什么"0 行"只可能是"找不到这一行"】siteName 经过校验必定有值，
        // 所以不可能是"值没变化所以没更新"这一类情况（MySQL 默认也会把
        // "新值与旧值相同"算作匹配到 0 行还是 1 行取决于 ROW_COUNT 语义，
        // 这里靠"必填字段一定有值 + 只有一行"两条一起排除掉误判）。
        if (affected == 0) {
            log.warn("站点设置那一行（id={}）不存在，正在写回一条", SiteSetting.SINGLE_ROW_ID);

            SiteSetting created = new SiteSetting();
            // id 显式给 1：这张表永远只有这一行（实体上用 IdType.INPUT，
            // 所以 id 由代码决定，不会变成自增出来的第二行）
            created.setId(SiteSetting.SINGLE_ROW_ID);
            created.setSiteName(siteName);
            created.setAnnouncement(announcement);
            created.setCommentEnabled(commentEnabled);
            created.setIcpNumber(icpNumber);
            // 自愈写回时同样要带上公安备案号：漏了的话，后台在"那一行不见了"
            // 之后第一次保存，页脚会少掉公安那一行，而站长提交的表单里明明填了
            created.setPoliceNumber(policeNumber);
            created.setCopyright(copyright);
            created.setPageSize(pageSize);
            settingMapper.insert(created);
        }

        contentCacheVersion.bump();

        // detail 里记站点名：它是这一组字段里最能指认"这是哪个站的设置"的那个。
        // 另外把评论开关也带上 —— 它是一个"会影响所有访客"的开关，
        // 事后翻审计时最需要一眼看到"那次保存有没有把它关掉"
        operationLogRecorder.record(OperationAction.UPDATE_SETTING, AuditTarget.SETTING,
                SiteSetting.SINGLE_ROW_ID,
                "站点设置=站点名 " + siteName + "，评论" + (commentEnabled == SiteSetting.COMMENT_ENABLED ? "开启" : "关闭"));

        log.info("保存站点设置: siteName={}, commentEnabled={}, pageSize={}",
                siteName, commentEnabled, pageSize);
    }

    // =================================================================
    //  三、私有工具
    // =================================================================

    /** 那一行缺失时的兜底值（理由见类注释 ①） */
    private SettingVO defaults() {
        SettingVO vo = new SettingVO();

        // ① 评论开关必须给一个能用的值：它是【行为类】的 ——
        //    为 null 的后果不是"这块不显示"，而是前端不知道该不该渲染评论框
        //    （而且两边猜的方向可能相反）。给"开启"与数据库列的默认值一致，
        //    也符合"评论一直是开着的"这个现状
        vo.setCommentEnabled(DEFAULT_COMMENT_ENABLED);

        // ② 其余一律留 null，前端的语义就是"不渲染这一块 / 用自己的默认值"：
        //    · 展示类（站点名 / 公告 / 两个备案号 / 版权）→ 不渲染那一块
        //    · 每页条数 → 前端用它自己的布局默认值（那个 12 = 3 列瀑布流 4 行，
        //      是前端的排版决策）。⚠️ 后端【故意】不在这里再写一个数字：
        //      两边各写一份才是最容易漂移的写法，而且 12 与 ArticleQuery 的
        //      DEFAULT_PAGE_SIZE(10) 还容易被误当成同一件事
        //    ⚠️ 每页条数留 null 也是安全的：即使有人把 null 直接传给
        //      /article/page?size=null，ArticleQuery 也会当成"没传 size"用默认值，
        //      不会变成一个坏请求
        return vo;
    }

    private String normalizeSiteName(SettingForm form) {
        String siteName = form.getSiteName() == null ? "" : form.getSiteName().trim();
        if (!StringUtils.hasText(siteName)) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "站点名不能为空");
        }
        if (siteName.length() > SITE_NAME_MAX_LENGTH) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "站点名最长 " + SITE_NAME_MAX_LENGTH + " 字");
        }
        return siteName;
    }

    /**
     * 可选文本归一化：空白 → null，并卡长度（与其它模块同一套）。
     * 【为什么空串要变成 null】前端清空输入框提交的是 ""，而数据库里"没有值"
     * 只有 NULL 一种表示；混着存会让以后每个查询都要同时考虑 "" 和 NULL，
     * 而且前端的"要不要渲染这一块"也会变成两个判断。
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

    /** 布尔 → 0/1（转换只发生在这一处，见 SiteSetting 的类注释） */
    private int normalizeCommentEnabled(SettingForm form) {
        Boolean enabled = form.getCommentEnabled();
        if (enabled == null) {
            // DTO 上已经是 @NotNull，这里是"绕过 DTO 直接调 Service"那条路径的第二道
            throw new BusinessException(ResultCode.PARAM_ERROR, "评论开关不能为空");
        }
        return enabled ? SiteSetting.COMMENT_ENABLED : SiteSetting.COMMENT_DISABLED;
    }

    /**
     * 每页条数的第二道校验。
     *
     * 【为什么这里必须有，哪怕 DTO 上已经写了 @Max】
     *   Service 是所有调用路径的必经之地（将来可能有数据导入脚本、
     *   或者别的 Service 直接调它）。而这条规则一旦被绕过，
     *   后果不是"报错"而是【静默不生效】：文章接口会把 size 夹到 50，
     *   站长看到的是"我设了 100，首页还是只列 50 篇"，没有任何提示。
     */
    private int normalizePageSize(SettingForm form) {
        Integer pageSize = form.getPageSize();
        if (pageSize == null) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "每页条数不能为空");
        }
        if (pageSize < 1 || pageSize > ArticleQuery.MAX_PAGE_SIZE) {
            throw new BusinessException(ResultCode.PARAM_ERROR,
                    "每页条数只能是 1 ~ " + ArticleQuery.MAX_PAGE_SIZE + "（后者是文章接口的分页上限）");
        }
        return pageSize;
    }

    /** 实体 -> VO */
    private SettingVO toVO(SiteSetting setting) {
        SettingVO vo = new SettingVO();
        vo.setSiteName(setting.getSiteName());
        vo.setAnnouncement(setting.getAnnouncement());
        vo.setCommentEnabled(toBooleanCommentEnabled(setting.getCommentEnabled()));
        vo.setIcpNumber(setting.getIcpNumber());
        // 公安备案号与 ICP 各自映射、互不作兜底：一个是 null 不该让另一个也变成 null
        // （它们是两套独立的备案，页脚那两行的显示与否各自判断）
        vo.setPoliceNumber(setting.getPoliceNumber());
        vo.setCopyright(setting.getCopyright());
        vo.setPageSize(setting.getPageSize());
        vo.setUpdateTime(setting.getUpdateTime());
        return vo;
    }

    /**
     * 0/1 -> 布尔（存 0/1 而接口给布尔的理由见 SiteSetting 的类注释）。
     *
     * 【为什么把 null 也当成"开启"】列是 {@code NOT NULL DEFAULT 1}，正常读不到 null；
     * 只有"有人手工改过表结构"或"实体在代码里被直接 new 出来"才可能出现。
     * 那时按开启处理与数据库默认值一致，而不会抛 NPE 让整个首页打不开 ——
     * 一个开关的默认方向不该决定站点能不能访问。
     */
    private boolean toBooleanCommentEnabled(Integer raw) {
        if (raw == null) {
            log.warn("站点设置里的 comment_enabled 读到了 null（列定义是 NOT NULL），本次按【开启】处理");
            return DEFAULT_COMMENT_ENABLED;
        }
        return raw != SiteSetting.COMMENT_DISABLED;
    }
}
