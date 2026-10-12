package com.yigalaxy.yiguixingtu.article.service;

import com.yigalaxy.yiguixingtu.article.cache.ArticleCacheVersion;
import com.yigalaxy.yiguixingtu.article.dto.ArticleForm;
import com.yigalaxy.yiguixingtu.article.entity.Article;
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
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ArticleWriteServiceTest {

    private final ArticleMapper articleMapper = mock(ArticleMapper.class);
    private final CategoryMapper categoryMapper = mock(CategoryMapper.class);
    private final ArticleCacheVersion cacheVersion = mock(ArticleCacheVersion.class);
    private final ArticleAttachmentService attachments = mock(ArticleAttachmentService.class);
    private final UploadedFileCleaner fileCleaner = mock(UploadedFileCleaner.class);
    private final UploadReferenceChecker referenceChecker = mock(UploadReferenceChecker.class);
    private final OperationLogRecorder audit = mock(OperationLogRecorder.class);
    private final TagService tags = mock(TagService.class);
    private final ArticleWriteService service = new ArticleWriteService(
            articleMapper, categoryMapper, cacheVersion, attachments,
            fileCleaner, referenceChecker, audit, tags);

    @Test
    void invalidStatusDoesNotWriteOrInvalidateCache() {
        assertThrows(BusinessException.class, () -> service.updateStatus(7L, 2));

        verifyNoInteractions(articleMapper, cacheVersion, audit);
    }

    @Test
    void invalidAttachmentsAreRejectedBeforeInsert() {
        ArticleForm form = new ArticleForm();
        form.setAttachments(List.of());
        when(attachments.validate(List.of()))
                .thenThrow(new BusinessException(ResultCode.PARAM_ERROR));

        assertThrows(BusinessException.class, () -> service.create(form, 1L));

        verifyNoInteractions(articleMapper, cacheVersion, audit, tags);
    }

    @Test
    void removeChecksReferencesBeforeDeletingAndSchedulesFilesLast() {
        Article article = new Article();
        article.setTitle("旧文章");
        article.setCover("cover-url");
        when(articleMapper.selectById(7L)).thenReturn(article);
        when(attachments.listByArticleId(7L)).thenReturn(List.of());
        when(fileCleaner.extractObjectKeys(null)).thenReturn(Set.of());
        when(fileCleaner.toObjectKey("cover-url")).thenReturn("cover-key");

        service.remove(7L);

        InOrder order = inOrder(referenceChecker, attachments, articleMapper,
                tags, cacheVersion, audit, fileCleaner);
        order.verify(referenceChecker).isReferencedBesidesArticle("cover-key", 7L);
        order.verify(attachments).deleteByArticleId(7L);
        order.verify(articleMapper).deleteById(7L);
        order.verify(tags).clearArticleTags(7L);
        order.verify(cacheVersion).bump();
        order.verify(audit).record(OperationAction.DELETE_ARTICLE, AuditTarget.ARTICLE,
                7L, "标题=旧文章");
        order.verify(fileCleaner).deleteAfterCommit(List.of("cover-key"));
    }
}
