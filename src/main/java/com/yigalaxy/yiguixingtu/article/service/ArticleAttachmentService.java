package com.yigalaxy.yiguixingtu.article.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.yigalaxy.yiguixingtu.article.dto.ArticleAttachmentForm;
import com.yigalaxy.yiguixingtu.article.dto.ArticleForm;
import com.yigalaxy.yiguixingtu.article.entity.Article;
import com.yigalaxy.yiguixingtu.article.entity.ArticleAttachment;
import com.yigalaxy.yiguixingtu.article.mapper.ArticleAttachmentMapper;
import com.yigalaxy.yiguixingtu.common.ResultCode;
import com.yigalaxy.yiguixingtu.common.exception.BusinessException;
import com.yigalaxy.yiguixingtu.upload.UploadProperties;
import com.yigalaxy.yiguixingtu.upload.UploadReferenceChecker;
import com.yigalaxy.yiguixingtu.upload.UploadedFileCleaner;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Slf4j
@Service
public class ArticleAttachmentService {

    private static final int MAX_ATTACHMENT_COUNT = 20;

    private final ArticleAttachmentMapper attachmentMapper;
    private final UploadedFileCleaner fileCleaner;
    private final UploadProperties uploadProperties;
    private final UploadReferenceChecker referenceChecker;

    public ArticleAttachmentService(ArticleAttachmentMapper attachmentMapper,
                                    UploadedFileCleaner fileCleaner,
                                    UploadProperties uploadProperties,
                                    UploadReferenceChecker referenceChecker) {
        this.attachmentMapper = attachmentMapper;
        this.fileCleaner = fileCleaner;
        this.uploadProperties = uploadProperties;
        this.referenceChecker = referenceChecker;
    }

    public List<ArticleAttachmentForm> validate(List<ArticleAttachmentForm> attachments) {
        if (attachments == null || attachments.isEmpty()) {
            return Collections.emptyList();
        }
        if (attachments.size() > MAX_ATTACHMENT_COUNT) {
            throw new BusinessException(ResultCode.PARAM_ERROR,
                    "附件最多 " + MAX_ATTACHMENT_COUNT + " 个，当前 " + attachments.size() + " 个");
        }

        long maxSize = uploadProperties.getAttachmentMaxSize().toBytes();
        for (int i = 0; i < attachments.size(); i++) {
            ArticleAttachmentForm item = attachments.get(i);
            if (item == null) {
                throw invalid(i, "附件不合法");
            }
            if (!StringUtils.hasText(item.getName()) || item.getName().trim().length() > 100) {
                throw invalid(i, "附件的名称不能为空且最长 100 字");
            }
            if (item.getSize() == null || item.getSize() < 0) {
                throw invalid(i, "附件的大小不合法");
            }
            if (item.getSize() > maxSize) {
                throw invalid(i, "附件超过大小上限（"
                        + uploadProperties.getAttachmentMaxSize().toMegabytes() + "MB）");
            }
            if (fileCleaner.toObjectKey(item.getUrl()) == null) {
                throw invalid(i, "附件的地址不是本项目的上传地址，请先通过上传接口获取");
            }
        }
        return attachments;
    }

    private BusinessException invalid(int index, String reason) {
        return new BusinessException(ResultCode.PARAM_ERROR, "第 " + (index + 1) + " 个" + reason);
    }

    public List<ArticleAttachment> listByArticleId(Long articleId) {
        return attachmentMapper.selectList(new LambdaQueryWrapper<ArticleAttachment>()
                .eq(ArticleAttachment::getArticleId, articleId)
                .orderByAsc(ArticleAttachment::getId));
    }

    public void deleteByArticleId(Long articleId) {
        attachmentMapper.delete(new LambdaQueryWrapper<ArticleAttachment>()
                .eq(ArticleAttachment::getArticleId, articleId));
    }

    public void replace(Long articleId, List<ArticleAttachmentForm> attachments) {
        List<ArticleAttachment> oldAttachments = listByArticleId(articleId);
        Set<String> oldKeys = new LinkedHashSet<>();
        for (ArticleAttachment old : oldAttachments) {
            String key = fileCleaner.toObjectKey(old.getUrl());
            if (key != null) {
                oldKeys.add(key);
            }
        }

        deleteByArticleId(articleId);
        for (ArticleAttachmentForm item : attachments) {
            ArticleAttachment entity = new ArticleAttachment();
            entity.setArticleId(articleId);
            entity.setName(item.getName().trim());
            entity.setUrl(item.getUrl());
            entity.setSize(item.getSize());
            attachmentMapper.insert(entity);
        }

        Set<String> newKeys = new LinkedHashSet<>();
        for (ArticleAttachmentForm item : attachments) {
            String key = fileCleaner.toObjectKey(item.getUrl());
            if (key != null) {
                newKeys.add(key);
            }
        }

        List<String> removableKeys = new ArrayList<>();
        for (String oldKey : oldKeys) {
            if (!newKeys.contains(oldKey) && !referenceChecker.isReferenced(oldKey)) {
                removableKeys.add(oldKey);
            } else if (!newKeys.contains(oldKey)) {
                log.info("被移除的附件仍被引用，跳过删除: key={}, 文章id={}", oldKey, articleId);
            }
        }
        fileCleaner.deleteAfterCommit(removableKeys);
    }

    public void cleanupDroppedImages(Article old, ArticleForm form, Long articleId) {
        Set<String> oldKeys = new LinkedHashSet<>(fileCleaner.extractObjectKeys(old.getContent()));
        String oldCoverKey = fileCleaner.toObjectKey(old.getCover());
        if (oldCoverKey != null) {
            oldKeys.add(oldCoverKey);
        }
        if (oldKeys.isEmpty()) {
            return;
        }

        Set<String> newKeys = new LinkedHashSet<>(fileCleaner.extractObjectKeys(form.getContent()));
        String newCoverKey = fileCleaner.toObjectKey(form.getCover());
        if (newCoverKey != null) {
            newKeys.add(newCoverKey);
        }

        List<String> removableKeys = new ArrayList<>();
        for (String oldKey : oldKeys) {
            if (newKeys.contains(oldKey)) {
                continue;
            }
            if (referenceChecker.isReferenced(oldKey)) {
                log.info("编辑时被移除的图片仍被引用，跳过删除: key={}, 文章id={}", oldKey, articleId);
                continue;
            }
            removableKeys.add(oldKey);
        }
        fileCleaner.deleteAfterCommit(removableKeys);
    }
}
