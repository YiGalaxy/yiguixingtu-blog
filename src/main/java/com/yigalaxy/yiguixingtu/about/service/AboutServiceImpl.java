package com.yigalaxy.yiguixingtu.about.service;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.yigalaxy.yiguixingtu.about.dto.AboutForm;
import com.yigalaxy.yiguixingtu.about.dto.AboutVO;
import com.yigalaxy.yiguixingtu.about.entity.About;
import com.yigalaxy.yiguixingtu.about.mapper.AboutMapper;
import com.yigalaxy.yiguixingtu.audit.AuditTarget;
import com.yigalaxy.yiguixingtu.audit.OperationAction;
import com.yigalaxy.yiguixingtu.audit.OperationLogRecorder;
import com.yigalaxy.yiguixingtu.common.ResultCode;
import com.yigalaxy.yiguixingtu.common.cache.ContentCacheVersion;
import com.yigalaxy.yiguixingtu.common.exception.BusinessException;
import com.yigalaxy.yiguixingtu.config.RedisConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * =====================================================================
 * 关于页服务实现
 *
 * 【这个类里三处需要想清楚的地方】
 *
 * ① 读接口为什么【永远不返回 null、也不抛 404】
 *    那一行是 V10 迁移脚本插进去的（id = 1），正常运行下它一定在。
 *    但如果有人手工 DELETE 了它（或者将来某次数据迁移出了岔子），有两种写法：
 *      · 抛 404：前台的关于页变成错误页 —— 一个【只是没内容】的页面
 *        却表现得像"这个站坏了"，而且站长不看日志就不知道发生了什么
 *      · 返回一个"壳"：id 与 nickname 给默认值，其余为 null，
 *        页面照常打开、只是内容空着；同时打一条 WARN 让运维看得见
 *    这里选后者：公开页面的可用性 > "把数据缺失变成显式错误"。
 *    真正的修复入口是后台点一次保存 —— 见下面 update 的自愈逻辑。
 *
 * ② 保存时那一行不存在怎么办（update 的自愈）
 *    先按 id = 1 更新；受影响行数为 0 说明这一行不在了，
 *    于是【插一条回来】而不是静静什么都不做。
 *    · 为什么不能什么都不做：接口返回 200"保存成功"，但库里没有任何变化 ——
 *      这是最糟的一种失败（用户以为保存了）
 *    · 为什么不能报 404：用户的操作（点保存）本身完全正当，
 *      让他去"先新建一条"是这个接口根本没有的能力（没有 create），等于死路
 *    ⚠️ 这是对"单条记录只改不建"的一处有意放宽，所以在这里写清楚：
 *      它不新增任何接口、也不允许出现第二行（插入的 id 恒为 1），
 *      只是把"那一行被人删掉了"这个异常状态自动修正回来。
 *      测试里有一条用例专门跑这条路径（先物理删掉那一行，再保存）。
 *
 * ③ 缓存与失效
 *    和其他内容模块同一套：key = 内容缓存版本号，保存后 bump 一次。
 *    关于页的内容变化频率极低（可能几个月改一次），但它是首页/导航会读的公开数据，
 *    TTL 兜底（5 分钟）+ 写操作即时失效两件都要有。
 * =====================================================================
 */
@Slf4j
@Service
public class AboutServiceImpl implements AboutService {

    /** 昵称长度上限，与 DTO 校验、数据库列长度三处一致 */
    private static final int NICKNAME_MAX_LENGTH = 50;

    /** 头像地址长度上限（与列长度一致） */
    private static final int AVATAR_MAX_LENGTH = 255;

    /** 自我介绍长度上限（DTO 层与这里一致；数据库是 text，容量远大于它） */
    private static final int BIO_MAX_LENGTH = 5000;

    /** 邮箱长度上限（与列长度一致） */
    private static final int EMAIL_MAX_LENGTH = 100;

    /** GitHub 地址长度上限（与列长度一致） */
    private static final int GITHUB_MAX_LENGTH = 255;

    /** 微信长度上限（与列长度一致） */
    private static final int WECHAT_MAX_LENGTH = 50;

    /** QQ 长度上限（与列长度一致） */
    private static final int QQ_MAX_LENGTH = 30;

    /**
     * 那一行不存在时，读接口返回的昵称占位值。
     * 【为什么不返回 null】前端拿到 null 会渲染出"空白的名字"，
     * 而一个明确的占位值至少让人看出"这里本来有内容"。
     * 注意它【不会被写进数据库】—— 只有后台保存时才落库。
     */
    private static final String NICKNAME_FALLBACK = "站长";

    private final AboutMapper aboutMapper;

    /** 内容缓存版本号：保存后推进它，让前台的关于页立刻更新 */
    private final ContentCacheVersion contentCacheVersion;

    /** 操作审计：关于页的保存也要留痕（谁在什么时候改了关于页） */
    private final OperationLogRecorder operationLogRecorder;

    public AboutServiceImpl(AboutMapper aboutMapper,
                            ContentCacheVersion contentCacheVersion,
                            OperationLogRecorder operationLogRecorder) {
        this.aboutMapper = aboutMapper;
        this.contentCacheVersion = contentCacheVersion;
        this.operationLogRecorder = operationLogRecorder;
    }

    // =================================================================
    //  一、读（公开）
    // =================================================================

    /**
     * 取关于页信息，带缓存。
     *
     * 【缓存 key：还是只有版本号】这份数据没有参数、只有一份，
     * 所以 key = 版本号就够（和 CategoryServiceImpl.listAll 的写法一致）。
     */
    @Override
    @Cacheable(cacheNames = RedisConfig.CACHE_ABOUT, key = "@contentCacheVersion.current()")
    public AboutVO get() {
        About about = aboutMapper.selectById(About.SINGLE_ROW_ID);

        if (about == null) {
            // 这一行不在了：返回一个"壳"而不是抛异常（理由见类注释 ①）。
            // ⚠️ 这条日志很重要：它让"关于页为什么是空的"在服务器日志里有个明确答案，
            //    而不是让人从"前端页面怎么没内容"开始猜
            log.warn("关于页那一行（id={}）在库里不存在，本次返回空壳；"
                    + "在后台点一次保存即可写回", About.SINGLE_ROW_ID);
            AboutVO shell = new AboutVO();
            shell.setId(About.SINGLE_ROW_ID);
            shell.setNickname(NICKNAME_FALLBACK);
            return shell;
        }

        return toVO(about);
    }

    // =================================================================
    //  二、写（仅管理员）
    // =================================================================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void update(AboutForm form) {
        String nickname = normalizeNickname(form);

        String avatar = normalizeOptional(form.getAvatar(), AVATAR_MAX_LENGTH, "头像地址");
        if (avatar != null) {
            requireHttpOrInternalPath(avatar, "头像地址");
        }
        String bio = normalizeOptional(form.getBio(), BIO_MAX_LENGTH, "自我介绍");
        String email = normalizeOptional(form.getEmail(), EMAIL_MAX_LENGTH, "邮箱");
        String github = normalizeOptional(form.getGithub(), GITHUB_MAX_LENGTH, "GitHub 地址");
        if (github != null) {
            requireHttpOrInternalPath(github, "GitHub 地址");
        }
        String wechat = normalizeOptional(form.getWechat(), WECHAT_MAX_LENGTH, "微信");
        String qq = normalizeOptional(form.getQq(), QQ_MAX_LENGTH, "QQ");

        // 逐字段显式 SET：传 null（或空串）会真的写成 NULL。
        // 关于页这一点尤其重要 —— 站长想把邮箱 / GitHub 从页面上拿掉时，
        // 用 updateById 会因为"跳过 null 字段"而清不掉，界面上看起来像"保存没生效"
        int affected = aboutMapper.update(null, new LambdaUpdateWrapper<About>()
                .eq(About::getId, About.SINGLE_ROW_ID)
                .set(About::getNickname, nickname)
                .set(About::getAvatar, avatar)
                .set(About::getBio, bio)
                .set(About::getEmail, email)
                .set(About::getGithub, github)
                .set(About::getWechat, wechat)
                .set(About::getQq, qq));

        // 受影响行数为 0 = 那一行不存在（normalizeNickname 已经保证 nickname 有值，
        // 所以"0 行"只可能是"找不到这一行"，不可能是"值没变化所以没更新"——
        // 有些数据库在"新值与旧值相同"时不返回 0，这一点也一并说明）
        if (affected == 0) {
            log.warn("关于页那一行（id={}）不存在，正在写回一条", About.SINGLE_ROW_ID);

            About created = new About();
            // id 显式给 1：这张表永远只有这一行（实体上用 IdType.INPUT，
            // 所以 id 由代码决定，不会变成自增出来的第二行）
            created.setId(About.SINGLE_ROW_ID);
            created.setNickname(nickname);
            created.setAvatar(avatar);
            created.setBio(bio);
            created.setEmail(email);
            created.setGithub(github);
            created.setWechat(wechat);
            created.setQq(qq);
            aboutMapper.insert(created);
        }

        contentCacheVersion.bump();

        // detail 里记昵称：关于页没有"标题"这类标识字段，昵称是最能指认它的东西。
        // 只记"谁在什么时候保存了关于页"的话，事后完全看不出改了什么
        operationLogRecorder.record(OperationAction.UPDATE_ABOUT, AuditTarget.ABOUT,
                About.SINGLE_ROW_ID, "关于页=昵称 " + nickname);

        log.info("保存关于页: nickname={}", nickname);
    }

    // =================================================================
    //  三、私有工具
    // =================================================================

    private String normalizeNickname(AboutForm form) {
        String nickname = form.getNickname() == null ? "" : form.getNickname().trim();
        if (!StringUtils.hasText(nickname)) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "昵称不能为空");
        }
        if (nickname.length() > NICKNAME_MAX_LENGTH) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "昵称最长 " + NICKNAME_MAX_LENGTH + " 字");
        }
        return nickname;
    }

    /**
     * 可选文本字段归一化：空白 → null，并卡长度。
     * 【空串 → null】前端清空输入框提交的是 ""，而数据库里"没有值"只有 NULL 一种表示；
     * 混着存会让以后每个查询都要同时考虑 "" 和 NULL（IS NULL OR = ''）。
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

    /**
     * 图片/链接类字段的白名单校验（第二道，第一道在 DTO 的 @Pattern 上）。
     *
     * 【为什么 Service 里还要判一次】Service 是所有调用路径的必经之地
     * （定时任务 / 数据导入脚本 / 别的 Service 都绕过 DTO）。
     * README「接口安全约定」里的"双重校验"说的就是这件事。
     *
     * 【两个字段共用这一个方法会不会太宽】不会：
     *   avatar 允许 / 开头的站内路径，github 只允许外链 —— 这个差异留在 DTO 的
     *   两个 @Pattern 上（IMAGE_URL vs EXTERNAL_URL），
     *   这里的第二道只兜"是不是 http(s) 或站内路径"这条共同底线。
     */
    private void requireHttpOrInternalPath(String value, String fieldName) {
        if (!value.startsWith("http://") && !value.startsWith("https://") && !value.startsWith("/")) {
            throw new BusinessException(ResultCode.PARAM_ERROR,
                    fieldName + "必须是 http(s):// 开头的完整地址，或以 / 开头的站内路径");
        }
    }

    /** 实体 -> VO */
    private AboutVO toVO(About about) {
        AboutVO vo = new AboutVO();
        vo.setId(about.getId());
        vo.setNickname(about.getNickname());
        vo.setAvatar(about.getAvatar());
        vo.setBio(about.getBio());
        vo.setEmail(about.getEmail());
        vo.setGithub(about.getGithub());
        vo.setWechat(about.getWechat());
        vo.setQq(about.getQq());
        vo.setUpdateTime(about.getUpdateTime());
        return vo;
    }
}
