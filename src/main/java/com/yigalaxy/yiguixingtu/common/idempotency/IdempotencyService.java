package com.yigalaxy.yiguixingtu.common.idempotency;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * =====================================================================
 * 接口幂等 —— 同一个提交请求被重复发来时，只让它生效一次
 *
 * ============ 它解决的是什么问题（一个非常具体的场景）============
 *
 * 在后台写完文章点「发布」，网络卡了两秒没反应，你又点了一下。
 * 浏览器于是发出【两个一模一样】的创建请求，后端老老实实执行两次 ——
 * 数据库里出现了两篇一模一样的文章，你得手动回去删一篇。
 *
 * 这几种情况都会触发：
 *   · 用户连点（手抖、以为没点上）
 *   · 请求超时后用户刷新页面重新提交
 *   · 前端重试逻辑在网络抖动时重发
 *
 * 「幂等」这个词听着吓人，其实就是【电梯按钮】：按一下和按十下，电梯只来一次。
 * 一个幂等的创建接口，同一个请求发 N 次，结果和发 1 次完全一样。
 *
 * ============ 用的是什么做法：幂等键（Idempotency-Key）============
 *
 * 这是业界最主流的做法（Stripe、Square 的支付接口都是这么做的）：
 *   ① 前端每次「提交动作」生成一个随机编号（UUID），放在请求头 Idempotency-Key 里
 *      —— 注意是每次【提交动作】生成一个，不是每次页面加载生成一个；
 *         用户点两次「发布」如果共用一个编号，第二次就会被正确地拦掉；
 *         而用户改了内容重新发布，会是一个新编号，正常创建。
 *   ② 后端拿这个编号去 Redis 占位：
 *        · 占上了 → 说明是新请求，正常执行
 *        · 没占上、且 Redis 里已经存着结果 → 说明这个请求处理过了，
 *          直接把上次的结果返回给它，【不再执行第二次】
 *        · 没占上、但还没存结果 → 说明另一个同样的请求正在处理中，
 *          告诉调用方"别急，正在处理"
 *
 * ============ 为什么不用另外两种常见做法 ============
 *
 *   · 自制 @Idempotent 注解 + AOP 切面：
 *     看着很"高级"，但要自己处理环绕通知、参数序列化、注解解析、
 *     切面顺序（还得保证它在事务外层）—— 这些坑一个都不少，
 *     而收益只是"调用方少写一行"。用主流做法更省事也更可靠。
 *   · 数据库唯一索引兜底：
 *     它需要一条【业务唯一性规则】（比如"同一作者不能有同名文章"），
 *     而这条规则本身就是错的 —— 用户完全可能真想写两篇同名的
 *     （比如"周报 01"改成"周记"，或者两篇不同系列的"前言"）。
 *     用业务约束去实现技术目的，会误伤正常场景。
 *     唯一索引真正该用的地方是"数据本身不能重复"，不是"请求不能重复"。
 *
 * ============ Redis 挂了怎么办：这里选择"放行" ============
 *   幂等是一层【保护】，不是业务规则本身。Redis 不可用时：
 *     · 选择放行（本项目的做法）→ 极端情况下可能重复创建一篇文章，用户自己删掉即可
 *     · 选择拒绝 → 用户彻底发不了文章，因为一个缓存组件挂了
 *   对一个个人博客来说，前者明显更合理。
 *   ⚠️ 但如果是"扣款、下单"这类场景，就该反过来选择拒绝 ——
 *   因为那时"重复执行"的代价远大于"暂时不能用"。
 *   这个选择取决于业务，没有通用答案，所以在这里写清楚为什么。
 *
 * ============ 用到的技术栈 ============
 *   · {@code SET key value NX EX seconds} —— Redis 的原子"占位"命令：
 *     NX 表示"只在 key 不存在时才设置"，EX 是过期时间。
 *     这两件事必须是【一条命令】完成的原子操作，
 *     分成"先查再写"两步的话，两个并发请求可能同时查到"不存在"然后都写进去，
 *     等于没拦。Spring Data Redis 里对应 {@code setIfAbsent(key, value, Duration)}。
 *   · {@code GET} —— 取上次的处理结果。
 *   · {@code DEL} —— 执行失败时把占位撤掉，让用户能重试。
 *     ⚠️ 不撤的话，一次失败会让这个幂等键在 TTL 内永远返回"正在处理"。
 * =====================================================================
 */
@Slf4j
@Component
public class IdempotencyService {

    /**
     * key 前缀。带上业务名（这里由调用方通过 scope 传入）是为了：
     * 不同接口可以用同一个幂等键而不互相干扰。
     */
    private static final String KEY_PREFIX = "idem:";

    /** 占位期间写入的值。真正的结果会覆盖它 */
    private static final String IN_PROGRESS = "__processing__";

    /**
     * 结果保留多久。
     *
     * 【24 小时是怎么定的】
     *   要覆盖"用户点了一次、隔一会儿又点了一次"的整个时间窗。
     *   几分钟太短（用户去看个日志再回来重试就失效了），
     *   永久保留又会让 Redis 无限增长（每个创建动作都留一条）。
     *   一天既够用，量也可控：就算一天创建 1000 篇文章，
     *   也只多 1000 个很小的 key。
     */
    private static final Duration RESULT_TTL = Duration.ofHours(24);

    /**
     * 占位（还没出结果）最多保留多久。
     *
     * 【为什么它必须比结果 TTL 短得多】
     *   它对应的是"一个请求正在处理中"这个状态，正常情况下只存在几十毫秒。
     *   如果给 24 小时，那么一旦某次执行【中途进程被杀】（没走到释放或写结果），
     *   这个键就会在 24 小时内一直返回"正在处理"，
     *   用户重试永远失败，而且看起来毫无原因。
     *   60 秒足够覆盖任何正常的创建耗时（实测创建一篇文章在 10ms 级），
     *   超时后键自动消失，用户重试就能正常走通。
     */
    private static final Duration PROCESSING_TTL = Duration.ofSeconds(60);

    private final StringRedisTemplate redis;

    public IdempotencyService(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /**
     * 尝试占位。
     *
     * @param scope 业务名（比如 "article:create"），用来隔离不同接口的幂等键
     * @param key   调用方传来的幂等键（前端生成的 UUID）
     * @return 占位结果，见 {@link Claim}
     */
    public Claim claim(String scope, String key) {
        String redisKey = redisKey(scope, key);
        try {
            // setIfAbsent 就是 SET NX：只有 key 不存在时才写入并返回 true。
            // 原子性由 Redis 保证，两个并发请求里只有一个能拿到 true。
            Boolean acquired = redis.opsForValue().setIfAbsent(redisKey, IN_PROGRESS, PROCESSING_TTL);
            if (Boolean.TRUE.equals(acquired)) {
                return Claim.firstTime();
            }

            // 没占上：要么是重复请求（已经有结果），要么是另一个请求正在处理
            String value = redis.opsForValue().get(redisKey);
            if (value == null) {
                // 极端情况：占位刚好在这一瞬间过期了。当作"没处理过"放行 ——
                // 宁可多一次保护失效，也不要让用户卡在一个莫名其妙的状态上。
                // 先把占位补上，避免两个请求同时走到这里又都放行。
                Boolean retryAcquired = redis.opsForValue()
                        .setIfAbsent(redisKey, IN_PROGRESS, PROCESSING_TTL);
                return Boolean.TRUE.equals(retryAcquired) ? Claim.firstTime() : Claim.inProgress();
            }

            return IN_PROGRESS.equals(value) ? Claim.inProgress() : Claim.alreadyDone(value);

        } catch (Exception e) {
            // 【为什么 Redis 出错要放行而不是报错】
            //   见类注释里的取舍说明：幂等是保护层，不该因为它自己挂了
            //   就让用户彻底发不出文章。放行的代价是极端情况下可能重复创建一篇，
            //   用户删掉即可；拒绝的代价是主功能不可用。
            log.warn("幂等占位失败，本次按「未处理过」放行（Redis 不可用时不影响主流程）: {}", e.getMessage());
            return Claim.firstTime();
        }
    }

    /**
     * 执行成功后记下结果，之后同样的幂等键再来就直接返回它。
     *
     * 【为什么要把结果存下来，而不是只存一个"处理过了"的标记】
     *   因为调用方需要知道"上次创建出来的东西 id 是多少"。
     *   只存标记的话，重复请求只能返回"你已经创建过了"，
     *   前端还得再去列表里找是哪一篇 —— 那是把问题丢给了调用方。
     */
    public void complete(String scope, String key, String result) {
        try {
            redis.opsForValue().set(redisKey(scope, key), result, RESULT_TTL);
        } catch (Exception e) {
            // 这里失败只是"下次重复请求拦不住"，不影响本次已经成功的创建
            log.warn("幂等结果写入失败（本次创建已成功，仅影响后续重复请求的拦截）: {}", e.getMessage());
        }
    }

    /**
     * 执行失败时释放占位，让用户可以重试。
     *
     * 【为什么必须调用它】
     *   不释放的话，这个幂等键在占位 TTL（60 秒）内会一直返回"正在处理"，
     *   用户看到的是一个没有任何原因、也无法自行解决的失败。
     *   而"失败就该能重试"是最基本的预期。
     */
    public void release(String scope, String key) {
        try {
            redis.delete(redisKey(scope, key));
        } catch (Exception e) {
            log.warn("幂等占位释放失败（该键会在 {} 后自动过期）: {}", PROCESSING_TTL, e.getMessage());
        }
    }

    private String redisKey(String scope, String key) {
        return KEY_PREFIX + scope + ":" + key;
    }

    /**
     * 占位结果。
     *
     * 【为什么用 record 而不是返回一个 Map 或两个方法】
     *   三种状态必须是【互斥且一次拿到】的：
     *     拿到执行权 / 已经处理过（并带上次的结果）/ 正在处理中。
     *   拆成两个方法（先问能不能执行、再问结果是什么）会出现
     *   "问的时候没人处理，答的时候已经有人处理了"的竞态。
     *   record 是不可变值对象，天然适合这种"一次查询返回一个确定快照"的场景。
     */
    public record Claim(boolean acquired, String previousResult, boolean processing) {

        // ⚠️ 这几个静态工厂方法的【名字不能叫 acquired()】：
        //    record 的访问器就叫 acquired()，再写一个同名静态方法
        //    会和访问器冲突（编译期报"记录中的存取方法无效"）。
        //    第一版就是这么写的，编译报错才发现。所以叫 firstTime()。
        static Claim firstTime() {
            return new Claim(true, null, false);
        }

        static Claim alreadyDone(String previousResult) {
            return new Claim(false, previousResult, false);
        }

        static Claim inProgress() {
            return new Claim(false, null, true);
        }
    }
}
