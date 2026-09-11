package com.yigalaxy.yiguixingtu.audit;

/**
 * 操作类型枚举。
 *
 * 【为什么要用枚举而不是随手写字符串】
 *   审计数据是要被【查询和统计】的（"最近一周有几次改角色"），
 *   如果 action 是散落各处的字符串字面量，拼错一个字母就会出现一个
 *   永远不会被统计到的分类，而且不会有任何报错。
 *   枚举把取值收在一处，编译器还能帮你检查拼写。
 *
 * 【为什么存到数据库时用 name() 而不是 ordinal()】
 *   ordinal 是枚举的定义顺序，往中间插一个新值就会让所有历史数据错位。
 *   name 是字符串，插值不影响已有数据。
 *
 * 【命名规则】动作_对象，例如 UPDATE_ARTICLE、DELETE_USER。
 *   这样按前缀分组统计（"所有 UPDATE_*"）也很方便。
 */
public enum OperationAction {

    /** 新建文章 */
    CREATE_ARTICLE("新建文章"),

    /** 编辑文章 */
    UPDATE_ARTICLE("编辑文章"),

    /** 发布 / 下架文章 */
    UPDATE_ARTICLE_STATUS("发布或下架文章"),

    /** 删除文章 */
    DELETE_ARTICLE("删除文章"),

    /** 启用 / 禁用用户 */
    UPDATE_USER_STATUS("启用或禁用用户"),

    /** 修改用户角色 */
    UPDATE_USER_ROLE("修改用户角色"),

    /** 重置用户密码 */
    RESET_USER_PASSWORD("重置用户密码"),

    /** 删除用户 */
    DELETE_USER("删除用户"),

    /** 新建标签 */
    CREATE_TAG("新建标签"),

    /** 编辑标签（改名 / 改排序） */
    UPDATE_TAG("编辑标签"),

    /** 删除标签 */
    DELETE_TAG("删除标签"),

    /** 审核评论（通过 / 拒绝） */
    UPDATE_COMMENT_STATUS("审核评论"),

    /** 删除评论 */
    DELETE_COMMENT("删除评论"),

    /** 新建分类 */
    CREATE_CATEGORY("新建分类"),

    /** 编辑分类 */
    UPDATE_CATEGORY("编辑分类"),

    /** 删除分类 */
    DELETE_CATEGORY("删除分类"),

    /**
     * 新建友链。
     *
     * 【这一组为什么是"内容类"而不是"文章类"】
     *   友链 / 项目 / 收藏 / 关于（F5 的四个内容模块）都是【站点级静态内容】：
     *   管理员本人维护、没有别的表引用它们、也不需要审核。
     *   它们各自的增删改都记一笔，命名沿用 {@code 动作_对象} 的既有规则。
     */
    CREATE_LINK("新建友链"),

    /** 编辑友链 */
    UPDATE_LINK("编辑友链"),

    /** 删除友链 */
    DELETE_LINK("删除友链"),

    /** 新建项目 */
    CREATE_PROJECT("新建项目"),

    /** 编辑项目 */
    UPDATE_PROJECT("编辑项目"),

    /** 删除项目 */
    DELETE_PROJECT("删除项目"),

    /** 新建收藏 */
    CREATE_FAVORITE("新建收藏"),

    /** 编辑收藏 */
    UPDATE_FAVORITE("编辑收藏"),

    /** 删除收藏 */
    DELETE_FAVORITE("删除收藏"),

    /**
     * 保存关于页信息。
     *
     * 【为什么只有 UPDATE 没有 CREATE / DELETE】
     *   关于页是全站唯一一份单条数据：那一行由迁移脚本插好、
     *   也不会被删除（不要了就清空字段）。所以这一族动作只有一个 ——
     *   枚举里的取值和真实存在的动作一一对应，不留"将来可能用到"的空位。
     */
    UPDATE_ABOUT("保存关于页信息"),

    /**
     * 新建音乐。
     *
     * 【它属于"内容类"，与友链 / 项目 / 收藏 同一族】
     *   音乐页的曲目也是【站点级静态内容】：管理员本人维护、没有别的表引用它、
     *   不需要审核 —— 唯一多出来的东西是"音频文件要先传到 uploads/music/ 下"，
     *   但那件事的产物只是一个地址，写进这张表之后就和其它内容模块完全同形。
     *   所以命名沿用 {@code 动作_对象} 的既有规则，不多造一套。
     */
    CREATE_MUSIC("新建音乐"),

    /** 编辑音乐（曲名 / 歌手 / 音频地址 / 封面 / 歌词 / 排序 / 显示状态） */
    UPDATE_MUSIC("编辑音乐"),

    /**
     * 删除音乐。
     *
     * 【⚠️ 它记的是"删掉了哪首歌"；文件删没删是另一件事，不能从这条记录推断】
     *   delete 做的是逻辑删除（见 MusicServiceImpl.delete 与 Music 实体的注释），
     *   而磁盘上的 mp3 只在【确认没有别处引用】之后才会被清掉
     *   （外链地址更是一个字节都不碰）。也就是说这条审计与"文件还在不在"
     *   没有一一对应关系：事后按它追"当时删的是哪一首"是准的，
     *   但要确认文件是否已清理，得看那一次的服务端日志
     *   （MusicServiceImpl.delete 会打印 objectKey 与"跳过/提交后删除"）。
     */
    DELETE_MUSIC("删除音乐"),

    /**
     * 保存站点设置。
     *
     * 【为什么只有 UPDATE，没有 CREATE / DELETE】与 UPDATE_ABOUT 同一条理由：
     *   站点设置是全站第二张"只有一行"的表，那一行由 V12 迁移脚本插好、
     *   也不会被删除（不要了就改回默认值）。
     *
     * 【它为什么比别的动作更值得留痕】这一个动作能改的东西【影响全站】：
     *   站点名（页眉页脚与服务端渲染的标题）、公告（首页）、评论总开关
     *   （所有访客能不能评论）、页脚版权与备案号（合规信息）、每页条数。
     *   所以 Service 里记审计时除站点名之外还带了评论开关的状态 ——
     *   事后翻账最需要一眼看到"那次保存有没有把评论关掉"。
     */
    UPDATE_SETTING("保存站点设置");

    /** 中文说明，便于直接展示与排查（不用在前端再维护一份翻译） */
    private final String description;

    OperationAction(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }
}
