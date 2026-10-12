package com.yigalaxy.yiguixingtu.comment.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.yigalaxy.yiguixingtu.article.entity.Article;
import com.yigalaxy.yiguixingtu.article.mapper.ArticleMapper;
import com.yigalaxy.yiguixingtu.auth.LoginUser;
import com.yigalaxy.yiguixingtu.audit.AuditTarget;
import com.yigalaxy.yiguixingtu.audit.OperationAction;
import com.yigalaxy.yiguixingtu.audit.OperationLogRecorder;
import com.yigalaxy.yiguixingtu.comment.dto.AdminCommentVO;
import com.yigalaxy.yiguixingtu.comment.dto.CommentForm;
import com.yigalaxy.yiguixingtu.comment.dto.CommentQuery;
import com.yigalaxy.yiguixingtu.comment.dto.CommentVO;
import com.yigalaxy.yiguixingtu.comment.entity.Comment;
import com.yigalaxy.yiguixingtu.comment.mapper.CommentMapper;
import com.yigalaxy.yiguixingtu.common.ResultCode;
import com.yigalaxy.yiguixingtu.common.exception.BusinessException;
import com.yigalaxy.yiguixingtu.common.web.ClientIp;
import com.yigalaxy.yiguixingtu.setting.service.SettingService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** 评论查询、发表与审核。前台只返回已通过的评论，后台列表批量查询文章标题。 */
@Slf4j
@Service
public class CommentServiceImpl implements CommentService {

    private static final int NICKNAME_MAX_LENGTH = 50;
    private static final int CONTENT_MAX_LENGTH = 1000;
    private static final int EMAIL_MAX_LENGTH = 100;

    private final CommentMapper commentMapper;
    private final ArticleMapper articleMapper;
    private final OperationLogRecorder operationLogRecorder;

    private final SettingService settingService;

    public CommentServiceImpl(CommentMapper commentMapper,
                             ArticleMapper articleMapper,
                             OperationLogRecorder operationLogRecorder,
                             SettingService settingService) {
        this.commentMapper = commentMapper;
        this.articleMapper = articleMapper;
        this.operationLogRecorder = operationLogRecorder;
        this.settingService = settingService;
    }

    @Override
    public IPage<CommentVO> pagePublished(CommentQuery query) {
        if (query.getArticleId() == null) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "articleId 不能为空");
        }

        Page<Comment> page = buildPage(query);

        // 前台忽略 query.status，始终只显示已通过的评论。
        LambdaQueryWrapper<Comment> wrapper = new LambdaQueryWrapper<Comment>()
                .eq(Comment::getArticleId, query.getArticleId())
                .eq(Comment::getStatus, Comment.STATUS_APPROVED)
                .orderByAsc(Comment::getCreateTime)
                .orderByAsc(Comment::getId);

        IPage<Comment> commentPage = commentMapper.selectPage(page, wrapper);

        Page<CommentVO> voPage = new Page<>(commentPage.getCurrent(), commentPage.getSize(),
                commentPage.getTotal());
        voPage.setRecords(commentPage.getRecords().stream().map(this::toVO)
                .collect(Collectors.toList()));
        return voPage;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public CommentVO create(CommentForm form, HttpServletRequest request, LoginUser currentUser) {
        // 先检查站点开关，再查询文章，避免关闭期间暴露文章是否存在。
        Boolean commentEnabled = settingService.get().getCommentEnabled();
        if (commentEnabled != null && !commentEnabled) {
            throw new BusinessException(ResultCode.COMMENT_DISABLED);
        }

        Article article = articleMapper.selectById(form.getArticleId());
        if (article == null || article.getStatus() == null || article.getStatus() != 1) {
            throw new BusinessException(ResultCode.ARTICLE_NOT_FOUND);
        }

        Long userId = currentUser == null ? null : currentUser.getUser().getId();
        boolean isAdmin = currentUser != null && "ADMIN".equals(currentUser.getUser().getRole());

        String nickname;
        if (currentUser == null) {
            if (!StringUtils.hasText(form.getNickname())) {
                throw new BusinessException(ResultCode.PARAM_ERROR, "昵称不能为空");
            }
            nickname = form.getNickname();
        } else {
            // 登录用户的昵称来自账号，避免表单伪造身份。
            String accountNickname = currentUser.getUser().getNickname();
            nickname = StringUtils.hasText(accountNickname)
                    ? accountNickname
                    : currentUser.getUser().getUsername();
        }

        Comment comment = new Comment();
        comment.setArticleId(form.getArticleId());
        comment.setUserId(userId);
        comment.setNickname(escapeAndFit(nickname, NICKNAME_MAX_LENGTH));
        comment.setEmail(escapeAndFit(StringUtils.hasText(form.getEmail()) ? form.getEmail().trim() : null,
                EMAIL_MAX_LENGTH));
        comment.setContent(escapeAndFit(form.getContent(), CONTENT_MAX_LENGTH));
        comment.setStatus(isAdmin ? Comment.STATUS_APPROVED : Comment.STATUS_PENDING);
        comment.setIp(ClientIp.of(request));
        // 插入后立即返回 VO，需要在插入前赋值创建时间。
        comment.setCreateTime(LocalDateTime.now());

        commentMapper.insert(comment);

        log.info("新增评论: id={}, articleId={}, nickname={}, userId={}, status={}",
                comment.getId(), comment.getArticleId(), comment.getNickname(),
                comment.getUserId(), comment.getStatus());

        return toVO(comment);
    }

    @Override
    public IPage<AdminCommentVO> pageForAdmin(CommentQuery query) {
        Page<Comment> page = buildPage(query);

        LambdaQueryWrapper<Comment> wrapper = new LambdaQueryWrapper<Comment>()
                .eq(query.getArticleId() != null, Comment::getArticleId, query.getArticleId())
                .eq(query.getStatus() != null, Comment::getStatus, query.getStatus())
                .orderByDesc(Comment::getCreateTime)
                .orderByDesc(Comment::getId);

        IPage<Comment> commentPage = commentMapper.selectPage(page, wrapper);

        // 每页批量查询标题，避免逐条查询文章。
        Map<Long, String> titleMap = titleMapOf(commentPage.getRecords());

        Page<AdminCommentVO> voPage = new Page<>(commentPage.getCurrent(), commentPage.getSize(),
                commentPage.getTotal());
        voPage.setRecords(commentPage.getRecords().stream()
                .map(c -> toAdminVO(c, titleMap.get(c.getArticleId())))
                .collect(Collectors.toList()));
        return voPage;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateStatus(Long id, Integer status) {
        if (status == null || (status != Comment.STATUS_APPROVED && status != Comment.STATUS_REJECTED)) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "状态只能是 1(通过) 或 2(拒绝)");
        }

        Comment exist = commentMapper.selectById(id);
        if (exist == null) {
            throw new BusinessException(ResultCode.COMMENT_NOT_FOUND);
        }

        Comment update = new Comment();
        update.setId(id);
        update.setStatus(status);
        commentMapper.updateById(update);

        String detail = "评论#" + id + " " + statusName(exist.getStatus()) + " → " + statusName(status);
        operationLogRecorder.record(OperationAction.UPDATE_COMMENT_STATUS, AuditTarget.COMMENT, id, detail);

        log.info("审核评论: id={}, {} -> {}", id, statusName(exist.getStatus()), statusName(status));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        Comment exist = commentMapper.selectById(id);
        if (exist == null) {
            throw new BusinessException(ResultCode.COMMENT_NOT_FOUND);
        }

        commentMapper.deleteById(id);

        // 审计保留内容片段，便于追溯已删除的评论。
        String snapshot = exist.getContent() == null ? "" : exist.getContent();
        if (snapshot.length() > 100) {
            snapshot = snapshot.substring(0, 100) + "…";
        }
        operationLogRecorder.record(OperationAction.DELETE_COMMENT, AuditTarget.COMMENT, id,
                "昵称=" + exist.getNickname() + "，内容=" + snapshot);

        log.info("删除评论: id={}, nickname={}", id, exist.getNickname());
    }

    private Page<Comment> buildPage(CommentQuery query) {
        long pageNo = (query.getPage() == null || query.getPage() < 1) ? 1L : query.getPage();
        long pageSize = (query.getSize() == null || query.getSize() < 1)
                ? CommentQuery.DEFAULT_PAGE_SIZE
                : Math.min(query.getSize(), CommentQuery.MAX_PAGE_SIZE);
        return new Page<>(pageNo, pageSize);
    }

    private Map<Long, String> titleMapOf(List<Comment> comments) {
        List<Long> articleIds = comments.stream()
                .map(Comment::getArticleId)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .collect(Collectors.toList());
        if (articleIds.isEmpty()) {
            return Collections.emptyMap();
        }
        return articleMapper.selectList(new LambdaQueryWrapper<Article>()
                        .select(Article::getId, Article::getTitle)
                        .in(Article::getId, articleIds))
                .stream()
                .collect(Collectors.toMap(Article::getId, Article::getTitle, (a, b) -> a));
    }

    /**
     * 仅转义 HTML 结构字符，保留普通标点；转义后截断以遵守列长度。
     * Spring HtmlUtils 会把箭头等普通字符变成具名实体，不适用于这里。
     */
    private String escapeAndFit(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        String escaped = trimmed
                .replace("&", "&amp;")     // 必须先转义 &，避免二次转义新生成的实体。
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
        return truncateAtEntityBoundary(escaped, maxLength);
    }

    /** 截断时丢弃末尾不完整的 HTML 实体。 */
    private String truncateAtEntityBoundary(String value, int maxLength) {
        if (value.length() <= maxLength) {
            return value;
        }
        String cut = value.substring(0, maxLength);
        int lastAmp = cut.lastIndexOf('&');
        if (lastAmp >= 0 && cut.indexOf(';', lastAmp) < 0) {
            return cut.substring(0, lastAmp);
        }
        return cut;
    }

    private String statusName(Integer status) {
        if (status == null) {
            return "未知";
        }
        return switch (status) {
            case Comment.STATUS_PENDING -> "待审核";
            case Comment.STATUS_APPROVED -> "已通过";
            case Comment.STATUS_REJECTED -> "已拒绝";
            default -> "未知";
        };
    }

    /** 前台不返回 email、ip 等私有字段。 */
    private CommentVO toVO(Comment comment) {
        CommentVO vo = new CommentVO();
        vo.setId(comment.getId());
        vo.setArticleId(comment.getArticleId());
        vo.setNickname(comment.getNickname());
        vo.setContent(comment.getContent());
        vo.setStatus(comment.getStatus());
        vo.setCreateTime(comment.getCreateTime());
        return vo;
    }

    private AdminCommentVO toAdminVO(Comment comment, String articleTitle) {
        AdminCommentVO vo = new AdminCommentVO();
        vo.setId(comment.getId());
        vo.setArticleId(comment.getArticleId());
        vo.setArticleTitle(articleTitle);
        vo.setNickname(comment.getNickname());
        vo.setUserId(comment.getUserId());
        vo.setEmail(comment.getEmail());
        vo.setContent(comment.getContent());
        vo.setStatus(comment.getStatus());
        vo.setIp(comment.getIp());
        vo.setCreateTime(comment.getCreateTime());
        return vo;
    }
}
