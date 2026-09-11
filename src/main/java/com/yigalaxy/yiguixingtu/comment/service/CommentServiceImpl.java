package com.yigalaxy.yiguixingtu.comment.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.yigalaxy.yiguixingtu.article.entity.Article;
import com.yigalaxy.yiguixingtu.article.mapper.ArticleMapper;
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
import com.yigalaxy.yiguixingtu.setting.service.SettingService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * =====================================================================
 * 评论服务实现
 *
 * 【这个类里有三个"看起来多余、其实必须"的决定】
 *
 * ① 前台的 status 写死成 1，而不是"默认值"
 *    pagePublished 里不读 query.status，直接 eq(status, 1)。
 *    和文章前台查询同样的道理：**安全规则不能是"默认值"**，
 *    默认值是可以被传参覆盖的，而"未审核的评论永远不对外可见"必须是绝对的。
 *
 * ② 入库前做 HTML 转义（只转义 &amp; &lt; &gt; " ' 这 5 个危险字符）
 *    评论是【用户输入】，而且是唯一一个任何人都能往库里写内容的入口。
 *    本项目的前端用 Vue 渲染（默认转义），所以"存原文 + 前端转义"其实也安全；
 *    但把转义做在入库这一层，等于给所有未来的渲染方（RSS、小程序、导出脚本）
 *    都上了保险 —— 谁忘了转义都不会出 XSS。
 *    代价写清楚：内容里原本的 &lt; &gt; &amp; 会被存成实体（&amp;lt; 等），
 *    所以【评论不支持 HTML/Markdown】—— 这对纯文本评论是合理的取舍；
 *    哪天要支持富文本，这一层必须重新设计（改白名单过滤，而不是简单转义）。
 *
 *    ⚠️ 这里【不用】Spring 的 HtmlUtils.htmlEscape —— 它在联调时被前端发现
 *    会把"有 HTML 具名实体的字符"也一起转掉（→ 变 &amp;rarr;、— 变 &amp;mdash;、
 *    … 变 &amp;hellip;），而前端渲染时会再转义一次，用户在页面上看到的就是
 *    字面的 "&amp;rarr;"。中文破折号"——"和省略号"……"都中招，属于一眼可见的 bug。
 *    详见 escapeAndFit 的注释。
 *
 * ③ 后台列表用"两次查询"代替 JOIN
 *    评论列表要显示文章标题，直觉是 JOIN article。这里没有这么做：
 *      · 分页 + JOIN 时，MyBatis-Plus 的 count 语句也要跟着改（容易算错）
 *      · 一页只有 10 条，把 article_id 收集起来一次性查标题（IN 查询）
 *        比 JOIN 更好读，也不会因为"评论被逻辑删除"之类的问题把主表行数放大
 *    这同样是在避免 N+1：10 条评论 = 2 次查询，而不是 11 次。
 * =====================================================================
 */
@Slf4j
@Service
public class CommentServiceImpl implements CommentService {

    /** 昵称上限，与 DTO 校验、数据库列长度保持一致 */
    private static final int NICKNAME_MAX_LENGTH = 50;

    /** 内容上限，与 DTO 校验、数据库列长度保持一致 */
    private static final int CONTENT_MAX_LENGTH = 1000;

    /** 邮箱上限，与 DTO 校验、数据库列长度保持一致 */
    private static final int EMAIL_MAX_LENGTH = 100;

    /** IP 列的长度上限（varchar(64)），超长的头要截断，不能让它把写入搞失败 */
    private static final int IP_MAX_LENGTH = 64;

    private final CommentMapper commentMapper;
    private final ArticleMapper articleMapper;
    private final OperationLogRecorder operationLogRecorder;

    /**
     * 站点设置服务：**只用它的一件东西** —— 评论总开关（见 create 的第 0 步）。
     *
     * 【为什么评论模块要依赖设置模块】那个开关的语义是"整站此刻收不收评论"，
     * 它属于站点设置；而"收了之后怎么存"属于这里。把这个开关复制一份到评论模块
     * （比如自己也存一个布尔）就等于同一件事有两个真相，迟早会不一致。
     * 反向没有依赖（设置模块不认识评论），所以不存在循环依赖。
     */
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

    // =================================================================
    //  一、前台
    // =================================================================

    @Override
    public IPage<CommentVO> pagePublished(CommentQuery query) {
        // 【为什么要强制传 articleId】这个接口的语义是"某篇文章的评论"。
        //   不校验的话，articleId 为空就会返回【全站所有已通过评论】——
        //   数据本身是公开的、不算泄漏，但一个"评论列表"接口能当"全站评论流"用，
        //   显然不是它该有的能力（分页一页 50 条，也容易被当成爬取入口）。
        //   明确报错比"返回一个看起来正常但语义不对的结果"要好。
        if (query.getArticleId() == null) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "articleId 不能为空");
        }

        Page<Comment> page = buildPage(query);

        // 【安全核心】状态写死成"已通过"。
        // 不读 query.getStatus()，所以前端传 status=0 也看不到待审核的评论。
        // 这和 ArticleServiceImpl.pagePublished 里写死 status=1 是同一套做法：
        // 这类规则一旦写成"默认值"，就总有被参数绕过的可能。
        LambdaQueryWrapper<Comment> wrapper = new LambdaQueryWrapper<Comment>()
                .eq(Comment::getArticleId, query.getArticleId())
                .eq(Comment::getStatus, Comment.STATUS_APPROVED)
                // 时间正序：评论区是对话，最早的在上面对着读最自然
                // （后台反而是倒序 —— 管理员关心的是最新的待审核，见下面那条注释）
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
    public CommentVO create(CommentForm form, HttpServletRequest request) {

        // 0. 站点级评论总开关：关掉之后【后端】拒绝新评论。
        //
        // 【为什么必须有这一层】前端在开关关掉时会把表单换成一句"评论已关闭"，
        // 但那只藏了界面 —— 任何人直接 POST /comment 就绕过去了，那个开关也就成了假开关。
        // 本项目对这类"可见性 / 可写性"规则的一贯做法是【写在 Service 里】，
        // 而不是由前端决定（对照 pagePublished 里把 status 写死成 1 而不是读参数）。
        //
        // 【为什么放在所有校验的最前面】开关关掉时，连"这篇文章存不存在"都不必告诉调用方：
        // 关站期间任何 POST 都应当得到同一个回答，而不是"评论已关闭"和"文章不存在"两种。
        //
        // 【为什么读的是 get()（带 Redis 缓存）】站点设置是"每次页面渲染都要读"的数据，
        // 所以它走缓存；而后台关掉开关时会推进缓存版本号，于是这里读到的
        // 立刻就是关闭之后的状态 —— 两处是同一份数据，不存在"关了但接口还收"的窗口。
        //
        // 【null 为什么放行】VO 里这个字段正常不会是 null（缺行时兜底成 true）；
        // 万一真读到 null，按"开着"处理 —— 与前端归一化的取向一致：
        // 配置读不到时保持现状，而不是把评论功能关掉。
        Boolean commentEnabled = settingService.get().getCommentEnabled();
        if (commentEnabled != null && !commentEnabled) {
            throw new BusinessException(ResultCode.COMMENT_DISABLED);
        }

        // 1. 文章必须存在，否则评论会挂到一篇不存在的文章上（那种数据永远查不出来）
        Article article = articleMapper.selectById(form.getArticleId());
        if (article == null) {
            throw new BusinessException(ResultCode.ARTICLE_NOT_FOUND);
        }
        // 2. 草稿不能评论：它对外根本不存在（前台访问草稿是 404），
        //    能对它发表评论只说明调用方拿到了不该拿到的 id
        if (article.getStatus() == null || article.getStatus() != 1) {
            throw new BusinessException(ResultCode.ARTICLE_NOT_FOUND);
        }

        Comment comment = new Comment();
        comment.setArticleId(form.getArticleId());
        comment.setNickname(escapeAndFit(form.getNickname(), NICKNAME_MAX_LENGTH));
        comment.setEmail(escapeAndFit(StringUtils.hasText(form.getEmail()) ? form.getEmail().trim() : null,
                EMAIL_MAX_LENGTH));
        comment.setContent(escapeAndFit(form.getContent(), CONTENT_MAX_LENGTH));
        // 【默认待审核】这是防垃圾评论的第一道也是最主要的一道闸：
        // 提交成功不等于"别人能看见"，必须等管理员点通过。
        comment.setStatus(Comment.STATUS_PENDING);
        comment.setIp(currentIp(request));
        // 【为什么要显式赋值，而不是靠列上的 DEFAULT CURRENT_TIMESTAMP】
        //   默认值确实会写进数据库，但 MyBatis-Plus 插入之后【不会把库生成的值回填到对象】——
        //   于是 createTime 在实体里还是 null，下面 toVO 返回给前端的就是
        //   "一条没有时间的评论"。联调时被前端发现（它只好显示成"刚刚"），
        //   这里改成由应用显式给时间，和 OperationLogListener 里的做法一致。
        comment.setCreateTime(LocalDateTime.now());

        commentMapper.insert(comment);

        log.info("新增评论: id={}, articleId={}, nickname={}", comment.getId(), comment.getArticleId(),
                comment.getNickname());

        // 【这里为什么不记操作审计】审计是给"管理动作"留痕的
        // （谁改了文章、谁封了账号）。评论本身就是内容，而且数量会持续增长 ——
        // 把每条公开评论都写进审计表，只会让审计表变成一个日志垃圾场，
        // 真正需要追溯的管理动作反而被淹没。评论的记录就在 comment 表里，
        // 而"审核 / 删除"这两个管理动作【有】审计（见下面两个方法）。
        return toVO(comment);
    }

    // =================================================================
    //  二、后台
    // =================================================================

    @Override
    public IPage<AdminCommentVO> pageForAdmin(CommentQuery query) {
        Page<Comment> page = buildPage(query);

        LambdaQueryWrapper<Comment> wrapper = new LambdaQueryWrapper<Comment>()
                .eq(query.getArticleId() != null, Comment::getArticleId, query.getArticleId())
                // 后台才允许按状态筛：管理员要处理的就是"待审核"那一批
                .eq(query.getStatus() != null, Comment::getStatus, query.getStatus())
                // 后台倒序：管理员关心的是最新提交的，而不是最早的
                .orderByDesc(Comment::getCreateTime)
                .orderByDesc(Comment::getId);

        IPage<Comment> commentPage = commentMapper.selectPage(page, wrapper);

        // 文章标题：把这一页的 article_id 收集起来一次查完（避免 N+1，见类注释 ③）
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

        // detail 里记下"从什么状态改成什么"：只有结果值的话，
        // 事后看不出这是一次"通过"还是"把已通过的又拒了"
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

        // @TableLogic 会把它变成 UPDATE comment SET deleted = 1 WHERE id = ?
        commentMapper.deleteById(id);

        // detail 里留下内容快照：评论被逻辑删除后，按 id 已经查不到内容了，
        // 而"删掉的到底是一句什么话"往往是事后最需要回答的问题
        // （比如用户来问"我的评论怎么没了"）
        String snapshot = exist.getContent() == null ? "" : exist.getContent();
        if (snapshot.length() > 100) {
            snapshot = snapshot.substring(0, 100) + "…";
        }
        operationLogRecorder.record(OperationAction.DELETE_COMMENT, AuditTarget.COMMENT, id,
                "昵称=" + exist.getNickname() + "，内容=" + snapshot);

        log.info("删除评论: id={}, nickname={}", id, exist.getNickname());
    }

    // =================================================================
    //  私有工具
    // =================================================================

    /**
     * 分页参数兜底。
     * 【为什么要夹取】和文章、用户列表同样的理由：
     * 不夹的话一个 ?size=999999 就能把整张评论表捞进内存。
     * 上限取 CommentQuery.MAX_PAGE_SIZE（50），与文章列表同一个量级。
     */
    private Page<Comment> buildPage(CommentQuery query) {
        long pageNo = (query.getPage() == null || query.getPage() < 1) ? 1L : query.getPage();
        long pageSize = (query.getSize() == null || query.getSize() < 1)
                ? CommentQuery.DEFAULT_PAGE_SIZE
                : Math.min(query.getSize(), CommentQuery.MAX_PAGE_SIZE);
        return new Page<>(pageNo, pageSize);
    }

    /** 一页评论涉及的文章标题（一次 IN 查询） */
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
     * 取来源 IP。
     *
     * 【为什么优先信 X-Forwarded-For】和审计模块里的做法一致：
     * 线上请求先过 Nginx，getRemoteAddr() 拿到的是 Nginx 的地址（127.0.0.1），
     * 记它没有任何意义。这个头本身可以被伪造，但本项目后端只绑回环地址、
     * 只能经由宿主机的 Nginx 进来，而 Nginx 会覆盖这个头 —— 伪造的请求到不了这里。
     */
    private String currentIp(HttpServletRequest request) {
        if (request == null) {
            return null;
        }
        String forwarded = request.getHeader("X-Forwarded-For");
        String ip = StringUtils.hasText(forwarded)
                ? forwarded.split(",")[0].trim()
                : request.getRemoteAddr();
        if (ip == null) {
            return null;
        }
        return ip.length() > IP_MAX_LENGTH ? ip.substring(0, IP_MAX_LENGTH) : ip;
    }

    /**
     * 归一化一个用户输入字段：去首尾空格 → HTML 转义 → 卡到列长度以内。
     *
     * 【⚠️ 这里为什么不用 Spring 的 HtmlUtils.htmlEscape（踩过一次，改成自己写）】
     *   第一版用的就是 {@code HtmlUtils.htmlEscape(value)}，看着很"标准"，但它在联调时
     *   被前端发现了一个很直观的问题：**它会把所有"有 HTML 4.0 具名实体"的字符都转成实体**，
     *   不只是危险字符。实测：
     *       中文 → 箭头      入库变成   中文 &rarr; 箭头
     *       —   → &mdash;      …  → &hellip;      © → &copy;      ® → &reg;
     *   而前端渲染评论时用的是 Vue 的插值（`{{ }}`，会再转义一次），
     *   所以用户在页面上看到的就是字面的 "&rarr;"、"&mdash;" ——
     *   对中文博客来说这是很常见的输入（中文破折号"——"、省略号"……"都中招）。
     *
     *   它另一个更隐蔽的坑是：默认按 ISO-8859-1 判断"能不能表示"，
     *   理论上会把非拉丁字符转成数字实体（本项目实测中文没被转，但这是"碰巧没中"，
     *   而不是有保证的）。
     *
     * 【所以改成只转义真正危险的 5 个 ASCII 字符】
     *   {@code & < > " '} 就是 OWASP 建议的 HTML 文本/属性上下文转义集合，
     *   既够用（这 5 个是唯一能改变 HTML 结构的字符），又不动任何别的字符。
     *   这不是"自研轮子"：转义表只有 5 行，而现成的库在这里做多了、且做多了的部分有害。
     *
     * 【& 必须第一个替换】否则会把后面替换出来的实体（&amp;lt;）再转一次，变成 &amp;amp;lt;
     *
     * 【为什么要"先转义再截断"】转义会让字符串变长（& 从 1 个字符变成 5 个）。
     *   先按字符数截断、再转义的话，极端输入（比如 1000 个 &）转义后能到 5000 字符，
     *   直接超过列长度报错 —— 用户看到的是 500，而原因只是一条"符号特别多"的评论。
     *   所以这里反过来：先转义，再把结果截到列长度以内。
     *   ⚠️ 截断要避免把实体切成两半（"&am"），否则页面上会多出一串乱码；
     *      下面 truncateAtEntityBoundary 专门处理这件事。
     */
    private String escapeAndFit(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        String escaped = trimmed
                .replace("&", "&amp;")     // 必须第一个（见上面的说明）
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
        return truncateAtEntityBoundary(escaped, maxLength);
    }

    /**
     * 按长度截断，但不在实体的中间下刀。
     *
     * 【为什么需要这个】截断可能正好落在 "&amp;" 中间，于是存下来的片段是 "&am" ——
     * 它在页面上就是字面的 "&am"，看着像乱码。这里往回找最后一个完整的实体边界：
     * 如果截断位置之前最后一个 '&' 之后还没有 ';'，就把那个 '&' 之前作为截断点。
     */
    private String truncateAtEntityBoundary(String value, int maxLength) {
        if (value.length() <= maxLength) {
            return value;
        }
        String cut = value.substring(0, maxLength);
        int lastAmp = cut.lastIndexOf('&');
        if (lastAmp >= 0 && cut.indexOf(';', lastAmp) < 0) {
            // 尾部那个 & 开头的实体被切断了，退回到它前面
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

    /** 实体 -> 前台 VO（注意：不带 email / ip） */
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

    /** 实体 -> 后台 VO（带 email / ip / 文章标题） */
    private AdminCommentVO toAdminVO(Comment comment, String articleTitle) {
        AdminCommentVO vo = new AdminCommentVO();
        vo.setId(comment.getId());
        vo.setArticleId(comment.getArticleId());
        vo.setArticleTitle(articleTitle);
        vo.setNickname(comment.getNickname());
        vo.setEmail(comment.getEmail());
        vo.setContent(comment.getContent());
        vo.setStatus(comment.getStatus());
        vo.setIp(comment.getIp());
        vo.setCreateTime(comment.getCreateTime());
        return vo;
    }
}
