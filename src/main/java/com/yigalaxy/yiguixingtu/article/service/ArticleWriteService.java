package com.yigalaxy.yiguixingtu.article.service;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.yigalaxy.yiguixingtu.article.cache.ArticleCacheVersion;
import com.yigalaxy.yiguixingtu.article.dto.ArticleForm;
import com.yigalaxy.yiguixingtu.article.entity.Article;
import com.yigalaxy.yiguixingtu.article.entity.ArticleAttachment;
import com.yigalaxy.yiguixingtu.article.mapper.ArticleMapper;
import com.yigalaxy.yiguixingtu.audit.AuditTarget;
import com.yigalaxy.yiguixingtu.audit.OperationAction;
import com.yigalaxy.yiguixingtu.audit.OperationLogRecorder;
import com.yigalaxy.yiguixingtu.category.mapper.CategoryMapper;
import com.yigalaxy.yiguixingtu.common.ResultCode;
import com.yigalaxy.yiguixingtu.common.exception.BusinessException;
import com.yigalaxy.yiguixingtu.tag.service.TagService;
import com.yigalaxy.yiguixingtu.upload.UploadReferenceChecker;
import com.yigalaxy.yiguixingtu.upload.UploadedFileCleaner;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Slf4j
@Service
public class ArticleWriteService {

    private final ArticleMapper articleMapper;
    private final CategoryMapper categoryMapper;
    private final ArticleCacheVersion articleCacheVersion;
    private final ArticleAttachmentService articleAttachmentService;
    private final UploadedFileCleaner uploadedFileCleaner;
    private final UploadReferenceChecker uploadReferenceChecker;
    private final OperationLogRecorder operationLogRecorder;
    private final TagService tagService;

    public ArticleWriteService(ArticleMapper articleMapper,
                               CategoryMapper categoryMapper,
                               ArticleCacheVersion articleCacheVersion,
                               ArticleAttachmentService articleAttachmentService,
                               UploadedFileCleaner uploadedFileCleaner,
                               UploadReferenceChecker uploadReferenceChecker,
                               OperationLogRecorder operationLogRecorder,
                               TagService tagService) {
        this.articleMapper = articleMapper;
        this.categoryMapper = categoryMapper;
        this.articleCacheVersion = articleCacheVersion;
        this.articleAttachmentService = articleAttachmentService;
        this.uploadedFileCleaner = uploadedFileCleaner;
        this.uploadReferenceChecker = uploadReferenceChecker;
        this.operationLogRecorder = operationLogRecorder;
        this.tagService = tagService;
    }

    @Transactional(rollbackFor = Exception.class)
    public Long create(ArticleForm form, Long authorId) {

        checkCategory(form.getCategoryId());

        var attachments = articleAttachmentService.validate(form.getAttachments());

        Article article = new Article();
        article.setTitle(form.getTitle().trim());
        article.setContent(form.getContent());
        article.setSummary(buildSummary(form.getSummary(), form.getContent()));
        article.setCover(form.getCover());
        article.setCategoryId(form.getCategoryId());
        article.setStatus(form.getStatus() == null ? 0 : form.getStatus());
        article.setIsTop(form.getIsTop() == null ? 0 : form.getIsTop());
        article.setViewCount(0);
        article.setAuthorId(authorId);

        articleMapper.insert(article);

        tagService.replaceArticleTags(article.getId(), form.getTagIds());

        articleAttachmentService.replace(article.getId(), attachments);

        articleCacheVersion.bump();

        operationLogRecorder.record(OperationAction.CREATE_ARTICLE, AuditTarget.ARTICLE,
                article.getId(), "标题=" + article.getTitle());

        return article.getId();
    }

    @Transactional(rollbackFor = Exception.class)
    public void update(Long id, ArticleForm form) {

        Article exist = articleMapper.selectById(id);
        if (exist == null) {
            throw new BusinessException(ResultCode.ARTICLE_NOT_FOUND);
        }

        checkCategory(form.getCategoryId());

        var attachments = articleAttachmentService.validate(form.getAttachments());

        articleMapper.update(null, new LambdaUpdateWrapper<Article>()
                .eq(Article::getId, id)
                .set(Article::getTitle, form.getTitle().trim())
                .set(Article::getSummary, buildSummary(form.getSummary(), form.getContent()))
                .set(Article::getContent, form.getContent())
                .set(Article::getCover, form.getCover())
                .set(Article::getCategoryId, form.getCategoryId())
                .set(Article::getStatus, form.getStatus() == null ? exist.getStatus() : form.getStatus())
                .set(Article::getIsTop, form.getIsTop() == null ? exist.getIsTop() : form.getIsTop()));

        tagService.replaceArticleTags(id, form.getTagIds());

        articleAttachmentService.replace(id, attachments);

        articleAttachmentService.cleanupDroppedImages(exist, form, id);

        articleCacheVersion.bump();

        operationLogRecorder.record(OperationAction.UPDATE_ARTICLE, AuditTarget.ARTICLE, id,
                "标题=" + form.getTitle().trim());
    }

    @Transactional(rollbackFor = Exception.class)
    public void updateStatus(Long id, Integer status) {

        if (status == null || (status != 0 && status != 1)) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "状态只能是 0(草稿) 或 1(已发布)");
        }

        Article exist = articleMapper.selectById(id);
        if (exist == null) {
            throw new BusinessException(ResultCode.ARTICLE_NOT_FOUND);
        }

        articleMapper.update(null, new LambdaUpdateWrapper<Article>()
                .eq(Article::getId, id)
                .set(Article::getStatus, status));

        articleCacheVersion.bump();

        operationLogRecorder.record(OperationAction.UPDATE_ARTICLE_STATUS, AuditTarget.ARTICLE, id,
                status == 1 ? "发布" : "下架");
    }

    @Transactional(rollbackFor = Exception.class)
    public void remove(Long id) {
        Article exist = articleMapper.selectById(id);
        if (exist == null) {
            throw new BusinessException(ResultCode.ARTICLE_NOT_FOUND);
        }

        List<ArticleAttachment> attachments = articleAttachmentService.listByArticleId(id);

        Set<String> candidateKeys = new LinkedHashSet<>();
        candidateKeys.addAll(uploadedFileCleaner.extractObjectKeys(exist.getContent()));
        String coverKey = uploadedFileCleaner.toObjectKey(exist.getCover());
        if (coverKey != null) {
            candidateKeys.add(coverKey);
        }
        for (ArticleAttachment attachment : attachments) {
            String key = uploadedFileCleaner.toObjectKey(attachment.getUrl());
            if (key != null) {
                candidateKeys.add(key);
            }
        }

        List<String> removableKeys = new ArrayList<>();
        for (String key : candidateKeys) {
            if (!uploadReferenceChecker.isReferencedBesidesArticle(key, id)) {
                removableKeys.add(key);
            } else {
                log.info("文件仍被其它文章引用，跳过删除: key={}, 被删文章id={}", key, id);
            }
        }

        articleAttachmentService.deleteByArticleId(id);

        articleMapper.deleteById(id);

        tagService.clearArticleTags(id);

        articleCacheVersion.bump();

        operationLogRecorder.record(OperationAction.DELETE_ARTICLE, AuditTarget.ARTICLE, id,
                "标题=" + exist.getTitle() + attachmentAuditSuffix(attachments));

        uploadedFileCleaner.deleteAfterCommit(removableKeys);
    }

    private String attachmentAuditSuffix(List<ArticleAttachment> attachments) {
        return attachments.isEmpty() ? "" : "，附件数=" + attachments.size();
    }

    private void checkCategory(Long categoryId) {
        if (categoryId == null) {
            return;
        }
        if (categoryMapper.selectById(categoryId) == null) {
            throw new BusinessException(ResultCode.CATEGORY_NOT_FOUND);
        }
    }

    private String buildSummary(String summary, String content) {
        if (StringUtils.hasText(summary)) {
            return summary.trim();
        }
        if (!StringUtils.hasText(content)) {
            return null;
        }

        String plain = content
                .replaceAll("```[\\s\\S]*?```", " ")
                .replaceAll("[#>*`\\[\\]()!-]", " ")
                .replaceAll("\\s+", " ")
                .trim();

        return plain.length() <= 120 ? plain : plain.substring(0, 120) + "...";
    }
}
