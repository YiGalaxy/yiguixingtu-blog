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

/** 关于页服务。缺失的单例记录读取时返回占位数据，保存时会重建。 */
@Slf4j
@Service
public class AboutServiceImpl implements AboutService {

    private static final int NICKNAME_MAX_LENGTH = 50;

    private static final int AVATAR_MAX_LENGTH = 255;

    private static final int BIO_MAX_LENGTH = 5000;

    private static final int EMAIL_MAX_LENGTH = 100;

    private static final int GITHUB_MAX_LENGTH = 255;

    private static final int WECHAT_MAX_LENGTH = 50;

    private static final int QQ_MAX_LENGTH = 30;

    /** 单例记录缺失时的展示占位值，不会写入数据库。 */
    private static final String NICKNAME_FALLBACK = "站长";

    private final AboutMapper aboutMapper;

    private final ContentCacheVersion contentCacheVersion;

    private final OperationLogRecorder operationLogRecorder;

    public AboutServiceImpl(AboutMapper aboutMapper,
                            ContentCacheVersion contentCacheVersion,
                            OperationLogRecorder operationLogRecorder) {
        this.aboutMapper = aboutMapper;
        this.contentCacheVersion = contentCacheVersion;
        this.operationLogRecorder = operationLogRecorder;
    }

    @Override
    @Cacheable(cacheNames = RedisConfig.CACHE_ABOUT, key = "@contentCacheVersion.current()")
    public AboutVO get() {
        About about = aboutMapper.selectById(About.SINGLE_ROW_ID);

        if (about == null) {
            // 保持公开页面可访问，同时通过日志暴露数据缺失。
            log.warn("关于页那一行（id={}）在库里不存在，本次返回空壳；"
                    + "在后台点一次保存即可写回", About.SINGLE_ROW_ID);
            AboutVO shell = new AboutVO();
            shell.setId(About.SINGLE_ROW_ID);
            shell.setNickname(NICKNAME_FALLBACK);
            return shell;
        }

        return toVO(about);
    }

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

        // 显式 SET 保证 null 能清除可选字段；updateById 会跳过 null。
        int affected = aboutMapper.update(null, new LambdaUpdateWrapper<About>()
                .eq(About::getId, About.SINGLE_ROW_ID)
                .set(About::getNickname, nickname)
                .set(About::getAvatar, avatar)
                .set(About::getBio, bio)
                .set(About::getEmail, email)
                .set(About::getGithub, github)
                .set(About::getWechat, wechat)
                .set(About::getQq, qq));

        // 当前数据库将值未变化的更新计为受影响行；0 行表示单例记录缺失。
        if (affected == 0) {
            log.warn("关于页那一行（id={}）不存在，正在写回一条", About.SINGLE_ROW_ID);

            About created = new About();
            // 固定 ID，确保恢复的仍是唯一单例记录。
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

        // 昵称作为审计详情，便于区分关于页的修改记录。
        operationLogRecorder.record(OperationAction.UPDATE_ABOUT, AuditTarget.ABOUT,
                About.SINGLE_ROW_ID, "关于页=昵称 " + nickname);

        log.info("保存关于页: nickname={}", nickname);
    }

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

    /** 空白统一存为 null，避免查询同时处理空串和 null。 */
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

    /** Service 可能被绕过 DTO 直接调用，因此保留链接格式的基础校验。 */
    private void requireHttpOrInternalPath(String value, String fieldName) {
        if (!value.startsWith("http://") && !value.startsWith("https://") && !value.startsWith("/")) {
            throw new BusinessException(ResultCode.PARAM_ERROR,
                    fieldName + "必须是 http(s):// 开头的完整地址，或以 / 开头的站内路径");
        }
    }

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
