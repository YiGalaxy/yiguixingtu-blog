package com.yigalaxy.yiguixingtu.article.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 文章附件实体，对应数据库表 article_attachment（见 V14 迁移脚本）。
 *
 * 【它在业务上的位置】
 *   一篇文章挂的"可下载文件"（PDF / 压缩包 / Office 文档 / 文本 / 音视频），
 *   一条记录 = 一个文件。一篇文章可以有 0~20 条（数量上限见 ArticleForm.attachments 的校验）。
 *
 * 【⚠️ 这张表刻意【没有】@TableLogic（逻辑删除）—— 与 article / comment 不一样】
 *   理由与 article_tag 相同（见 V5 迁移脚本），而且这里更直接：
 *   附件的保存语义是【整体替换】（前端提交什么，库里就是什么）。
 *   如果被"移除"的那些附件只是被标成 deleted = 1，那么
 *   "这篇文章此刻有哪些附件"就必须处处带上 deleted = 0 才成立 ——
 *   漏一处就会把已经移除的附件又算进来（比如"删文章时按 article_id 查附件"
 *   这种手写 SQL，或者将来有人按 url 反查引用关系）。
 *   而附件没有"删错了要恢复"的分量：重新上传一次即可，
 *   恢复一行反而会指向一个可能已经被清理掉的物理文件。
 *
 * 【同样刻意的两点】
 *   · 没有 update_time：一行从插入起就不会被改（替换 = 删旧行 + 插新行），
 *     与 article_tag 同一条理由
 *   · 没有 deleted 字段、因此也没有"逻辑删除 + 唯一索引"那个坑要绕
 *     （这张表本来也没有任何唯一索引）
 *
 * 【字段与列的对应】靠 MyBatis-Plus 的默认约定（下划线 ↔ 驼峰），
 *   与其它实体完全一致，不需要 @TableField。
 */
@Data
@Schema(description = "文章附件")
@TableName("article_attachment")
public class ArticleAttachment implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "附件ID", example = "1")
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 所属文章 id。
     *
     * 【为什么它有索引（见 V14）】按文章查附件、删文章时按文章删附件行，
     * 两个方向都靠 idx_article。
     *
     * 【为什么不用外键约束】与全项目一致（见 V5 末尾）：
     * article 用的是逻辑删除（删文章只把 deleted 置 1），
     * 外键的 ON DELETE CASCADE 根本不会触发 —— 一个"看着会级联、
     * 实际永远不动手"的外键比没有外键危险。级联逻辑写在
     * ArticleServiceImpl.remove 里，只有一处。
     */
    @Schema(description = "所属文章ID")
    private Long articleId;

    /**
     * 附件的显示名（上传时的原始文件名，已去掉路径、截断到 100 字）。
     * 由 UploadService.sanitizeName 清洗后返回给前端，前端再原样提交回来。
     * ⚠️ 它只是展示文本，不参与任何路径拼接（磁盘上的真名是 UUID）。
     */
    @Schema(description = "附件显示名", example = "毕业论文.pdf")
    private String name;

    /**
     * 附件地址，形如 {@code http://localhost:8082/uploads/attachment/2026/09/{uuid}.pdf}。
     * 保存文章时会校验它必须落在本项目的上传地址前缀之内
     * （见 ArticleServiceImpl.validateAttachments），防止外部地址被塞进来。
     */
    @Schema(description = "附件地址", example = "http://localhost:8082/uploads/attachment/2026/09/xxx.pdf")
    private String url;

    /** 文件大小（字节）。上限由 app.upload.attachment-max-size 控制（默认 100MB） */
    @Schema(description = "文件大小（字节）", example = "1258291")
    private Long size;

    /** 创建时间（= 附件随文章保存的时间）。数据库有默认值，插入时不必赋值 */
    @Schema(description = "创建时间")
    private LocalDateTime createTime;
}
