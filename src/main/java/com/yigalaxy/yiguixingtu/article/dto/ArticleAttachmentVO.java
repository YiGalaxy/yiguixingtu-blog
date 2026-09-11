package com.yigalaxy.yiguixingtu.article.dto;

import com.yigalaxy.yiguixingtu.article.entity.ArticleAttachment;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 文章附件（对外返回）。字段与前端约定的一模一样：{@code {name, url, size}}。
 *
 * 【为什么只给这三个字段，不给 id / articleId / createTime】
 *   接口是给【渲染一个下载列表】用的：名字给用户看、地址给浏览器下载、
 *   大小给用户判断"值不值得下"。id 与 articleId 是库里的内部结构
 *   （前端手上的 attachments 数组本来就是"这篇文章的"，
 *   不需要每一条都再带着自己的 articleId），createTime 对用户没有意义
 *   （附件的顺序就是提交顺序，不按时间排）。
 *   ⚠️ 反过来也要守住：这个 VO 里【不该】出现任何内部字段，
 *   一旦加了，"前端直接拿它回传给保存接口"就会变成两种形状混用。
 *
 * 【为什么 url 是完整地址而不是对象 key】
 *   与 cover / music.url 一致：前端把它直接放进 <a href>，
 *   不需要知道"base-url 是什么、/uploads/ 前缀是什么"。
 *   地址的拼接只发生在后端一处（LocalFileStorage.store）。
 */
@Data
@Schema(description = "文章附件")
public class ArticleAttachmentVO {

    @Schema(description = "附件显示名", example = "毕业论文.pdf")
    private String name;

    @Schema(description = "附件下载地址", example = "http://localhost:8082/uploads/attachment/2026/09/xxx.pdf")
    private String url;

    @Schema(description = "文件大小（字节）", example = "1258291")
    private Long size;

    /**
     * 实体列表 → VO 列表（供文章详情的两处转换共用：前台缓存读、后台直读）。
     *
     * 【为什么把这段转换放在 VO 上，而不是各写一遍】
     *   详情接口有两处 toVO：{@code ArticleServiceImpl.toVO}（后台）与
     *   {@code PublishedArticleCache.toVO}（前台，带缓存）。
     *   附件若两边各写一遍，迟早出现"前台有附件、后台没有"这种不一致 ——
     *   而这类不一致的表现是"编辑界面看不见附件，于是保存时把它清空了"
     *   （整体替换语义下，看不见 = 提交空数组 = 删掉全部附件）——
     *   那是一次静默的数据丢失。所以转换只留一份：实体知道自己对应哪个 VO。
     *
     * 【空列表返回空 ArrayList 而不是 null】
     *   与 ArticleVO.tags 同一个考虑：前端可以直接 v-for，不用先判空。
     *
     * @param attachments 实体列表（可为 null）
     * @return 非 null 的 VO 列表
     */
    public static List<ArticleAttachmentVO> fromEntities(List<ArticleAttachment> attachments) {
        if (attachments == null || attachments.isEmpty()) {
            return new ArrayList<>();
        }
        List<ArticleAttachmentVO> list = new ArrayList<>(attachments.size());
        for (ArticleAttachment entity : attachments) {
            ArticleAttachmentVO vo = new ArticleAttachmentVO();
            vo.setName(entity.getName());
            vo.setUrl(entity.getUrl());
            vo.setSize(entity.getSize());
            list.add(vo);
        }
        return list;
    }
}
