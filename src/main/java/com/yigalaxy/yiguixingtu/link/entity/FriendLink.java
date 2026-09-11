package com.yigalaxy.yiguixingtu.link.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 友链实体，对应数据库表 friend_link。
 *
 * 【它和 Category / Tag 最本质的区别：它不属于任何别的东西】
 *   分类是文章的从属（article.category_id 指向它），标签靠 article_tag 关联到文章 ——
 *   它们都会"因为文章变了而变"。友链是一份【独立的站点级内容】：
 *   没有任何表引用它，它也不引用任何表。
 *   这决定了两件事：
 *     · 删除时不需要检查"还有谁在用"（不像分类要数文章）
 *     · 它的缓存失效和文章的缓存版本号【无关】，用 ContentCacheVersion 那一个计数器
 *       （理由写在 common/cache/ContentCacheVersion 的类注释里）
 *
 * 【status 只有两个取值，语义是"显不显示"】
 *   0 隐藏 —— 录进来了但不展示（比如对方站点正在维护、或者友链暂时下线）
 *   1 显示 —— 前台可见（新建时的默认值）
 *   为什么默认是显示、而评论默认待审核，见 V7 迁移脚本里的对比说明。
 *
 * 【为什么用 @TableLogic 而不是物理删除】
 *   friend_link 上没有任何唯一索引，所以"逻辑删除 + 唯一索引"那个坑不存在
 *   （判断标准见 README「数据库表」一节），可以安心保留"删错了能恢复"。
 */
@Data
@Schema(description = "友情链接")
@TableName("friend_link")
public class FriendLink implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 状态：隐藏（前台查不到） */
    public static final int STATUS_HIDDEN = 0;

    /** 状态：显示（前台可见，也是新建时的默认值） */
    public static final int STATUS_VISIBLE = 1;

    @Schema(description = "友链ID", example = "1")
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 站点名称，最长 50（与 DTO 校验、数据库列长度三处一致） */
    @Schema(description = "站点名称", example = "某某的博客")
    private String name;

    /** 站点地址：http(s) 开头的完整地址（格式由 DTO 上的 @Pattern 白名单保证） */
    @Schema(description = "站点地址", example = "https://example.com")
    private String url;

    /** 头像/站点图标地址，可为空；既可以是上传接口返回的绝对地址，也可以是 / 开头的站内路径 */
    @Schema(description = "头像地址")
    private String avatar;

    @Schema(description = "一句话介绍")
    private String description;

    @Schema(description = "排序值，越小越靠前", example = "1")
    private Integer sort;

    @Schema(description = "状态：0隐藏 1显示")
    private Integer status;

    @Schema(description = "创建时间")
    private LocalDateTime createTime;

    @Schema(description = "更新时间")
    private LocalDateTime updateTime;

    /**
     * 逻辑删除标记。
     *
     * 【@TableLogic 做了什么】加上它之后，MyBatis-Plus 会自动把
     *   · BaseMapper 的删除 → UPDATE ... SET deleted = 1
     *   · BaseMapper 的查询 → 自动追加 WHERE deleted = 0
     * ⚠️ 但它【只管 BaseMapper 生成的那几条 SQL】，手写语句它管不到 ——
     * 所以本项目里凡是有手写 SQL 的 Mapper（CategoryMapper、TagMapper）都必须自己写
     * deleted = 0。这里没有任何手写语句，所以不存在这个风险。
     */
    @Schema(description = "逻辑删除：0未删 1已删", hidden = true)
    @TableLogic
    private Integer deleted;
}
