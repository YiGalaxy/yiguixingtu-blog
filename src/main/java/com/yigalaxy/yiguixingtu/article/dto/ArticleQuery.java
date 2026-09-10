package com.yigalaxy.yiguixingtu.article.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import org.springframework.util.StringUtils;

/**
 * 文章分页查询条件（前台后台共用）。
 *
 * 【它是怎么被塞进来的】
 *   前端 GET /article/page?page=1&size=10&keyword=xxx 里的参数，
 *   由 Spring MVC 按【字段名】自动绑定到这个对象的同名属性上
 *   （靠 setter 注入，所以类上的 Lombok 的 {@code @Data} 是必需的）。
 *   用一个对象接收而不是在方法上写一堆 {@code @RequestParam}，
 *   好处是以后加查询条件不用改 Controller 方法签名。
 */
@Data
@Schema(description = "文章分页查询条件")
public class ArticleQuery {

    /**
     * 一页最多多少条 —— 业务规则，首页信息流一次最多展示 50 条。
     *
     * 【为什么要把它提成常量，而不是继续写在 Service 里】
     *   这个值有两个地方必须【完全一致】：
     *     ① ArticleServiceImpl.doPage() 里真正夹取 size 的地方
     *     ② 下面 toCacheKey() 里把 size 归一化成"实际生效值"的地方
     *   如果两边各写一个 50，哪天有人只改了一处，缓存 key 就会和真实查询对不上：
     *   归一化用 50、实际查询用 30，则 size=40 的请求会被登记成"key 里写 50"，
     *   而它实际只取了 30 条 —— 之后 size=50 的请求会命中这条 key、
     *   拿到一个只有 30 条的结果。这种 bug 极难查，所以两个地方共用同一个常量。
     */
    public static final long MAX_PAGE_SIZE = 50L;

    /** 没传 size 时用多少条 */
    public static final long DEFAULT_PAGE_SIZE = 10L;

    @Schema(description = "页码，从1开始", example = "1")
    private Long page = 1L;

    @Schema(description = "每页条数", example = "10")
    private Long size = 10L;

    @Schema(description = "关键词：模糊匹配 标题 或 摘要")
    private String keyword;

    @Schema(description = "分类ID筛选")
    private Long categoryId;

    /**
     * 【注意】这个字段只有后台接口认。
     * 前台调 /article/page 时，Service 会写死 status=1，
     * 前端就算传 status=0 想偷看草稿，也会被无视。
     */
    @Schema(description = "状态筛选：0草稿 1已发布（仅后台生效）")
    private Integer status;

    @Schema(description = "排序字段：id / title / status / viewCount / isTop / createTime / updateTime",
            example = "createTime")
    private String sortField;

    @Schema(description = "排序方向：asc 升序 / desc 降序", example = "desc")
    private String sortOrder;

    /**
     * 生成缓存 key（只给【前台已发布列表】用）。
     *
     * ============ 这个方法解决的三个问题 ============
     *
     * 【问题一：默认参数与显式参数必须命中同一条缓存】
     *   不传 page/size 时它们会被兜底成 1 和 10，而显式传 page=1&size=10
     *   得到的是【完全一样的结果】。如果 key 直接用原始值，
     *   `null` 和 `1` 会变成两条不同的 key、同一份数据存两遍（浪费内存），
     *   更麻烦的是"同一份数据有两个来源"，将来排查不一致时会很困惑。
     *   所以这里先做兜底，再拼 key —— 让语义相同的请求必然落到同一条 key 上。
     *
     * 【问题二：挡住了"改 size 绕过缓存"这条路】
     *   真正查库时 size 会被夹到 MAX_PAGE_SIZE（50）。假如 key 里记的是
     *   用户传的原始值，那么 ?size=51、?size=52、?size=99 ...
     *   每一个都是新的 key、每一次都未命中、每一次都真的去查库 ——
     *   缓存等于形同虚设，而且顺手把 Redis 塞满垃圾 key。
     *   所以 key 里用的是【夹取之后的值】：这些请求全都命中同一条缓存。
     *
     * 【问题三：null 与空串、带空格的关键词】
     *   搜索时用的是 LIKE '%关键词%'，`null`、`""`、`"  "` 三种输入
     *   在 SQL 层都会因为 StringUtils.hasText 判断为"不加条件"，
     *   结果完全一致。所以 key 里统一归一化成 "all"。
     *   关键词也要 trim，否则 "abc" 和 "abc " 会变成两条 key。
     *
     * ============ 一个已知的取舍 ============
     *   关键词是【用户可控】的，而且直接拼进了 key。理论上有人可以用
     *   超长的关键词（或不断变化的随机词）造出海量不同的 key。
     *   本项目的应对有两层：
     *     ① 列表缓存对"空结果"只给很短的 TTL（见 RedisConfig 的 TtlFunction），
     *        随机词搜索大多查不到东西，这些垃圾 key 会很快自己消失；
     *     ② 关键词最终会被用在 LIKE '%...%' 上，超长关键词本身没有任何查询价值。
     *   如果以后要更严格，可以改成对关键词做一次摘要（比如取 hash）再拼 key。
     *
     * @return 形如 {@code 1:10:all:all:default:default} 的稳定字符串
     */
    public String toCacheKey() {
        // ---- 页码兜底：与 ArticleServiceImpl.doPage() 的判断保持一致 ----
        long effectivePage = (page == null || page < 1) ? 1L : page;

        // ---- 每页条数兜底 + 夹取：这两步必须与 doPage() 完全一致 ----
        long effectiveSize = (size == null || size < 1) ? DEFAULT_PAGE_SIZE : size;
        if (effectiveSize > MAX_PAGE_SIZE) {
            effectiveSize = MAX_PAGE_SIZE;
        }

        // ---- 文本条件归一化：空 / 空白都当成"不筛选" ----
        String kw = StringUtils.hasText(keyword) ? keyword.trim() : "all";
        String sort = StringUtils.hasText(sortField) ? sortField : "default";
        String order = StringUtils.hasText(sortOrder) ? sortOrder : "default";

        return effectivePage
                + ":" + effectiveSize
                + ":" + (categoryId == null ? "all" : categoryId)
                + ":" + kw
                + ":" + sort
                + ":" + order;
    }
}
