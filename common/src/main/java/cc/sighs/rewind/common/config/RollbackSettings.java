package cc.sighs.rewind.common.config;

/**
 * 原地回滚的行为开关（服务端侧）。默认值、语义在这里，值也存在这里；各 target 的配置类只负责
 * 把它读写进自己的配置文件（NeoForge 的 {@code ModConfigSpec} / Fabric 的 properties）。
 */
public final class RollbackSettings {
    /**
     * 回溯时要不要处理玩家的「配方书」。
     *
     * <p>默认 {@code false}：**完全不碰配方书**——{@code player.load} 之前会把 NBT 里的
     * {@code recipeBook} 摘掉（{@code ServerRecipeBook.fromNbt} 要按名字逐条查配方表，大整合包里
     * 这一下可能几百毫秒），也不给客户端重发那份包；服务端与客户端两边都停在回溯前的状态。
     *
     * <p>开着才走原来那套：{@code player.load} 把配方书读回来，再用
     * {@code sendInitialRecipeBook} 把整份重发给客户端。
     */
    public static final boolean DEFAULT_SYNC_RECIPE_BOOK = false;

    /**
     * 回溯时要不要「只同步读回玩家视野内的受影响区块」。
     *
     * <p><b>已废弃 / 当前恒被忽略</b>：零磁盘稀疏拦截架构下，覆盖层在回滚收尾撤装时清空，
     * 延迟加载的区块会错过覆盖、穿透读到盘上的未来数据，所以回滚引擎一律**全部同步读回**
     * （见各版本 {@code InPlaceRollback.loadChunks}，不再读本开关）。保留这个键只为
     * 兼容旧配置文件，改它没有任何效果；将来若给延迟加载补上覆盖层驻留，再让它重新生效。
     *
     * <p>默认 {@code true}（历史语义）：开启时不在任何玩家视野（服务端视距 + 1 个区块）里的
     * 受影响区块不在这里同步读，交给原版流水线按还原后的 ticket 异步读回来；
     * 关闭则所有受影响区块都在回滚这一帧里同步读回来。
     */
    public static final boolean DEFAULT_SYNC_CHUNKS_NEAR_PLAYER = true;

    /**
     * 读档冷却时长（秒）：一次成功回溯之后，要等这么久才能再读一次档。默认 {@code 0} = 不冷却。
     *
     * <p>冷却只挡「新的回溯」——建点（F7 / 界面上的覆盖）不受影响。起点是**回溯成功那一刻**，
     * 记在存档点索引里（{@code SnapshotIndex.lastRollbackAt}），所以退出游戏重进也绕不过去；
     * 换个存档目录则各算各的。
     */
    public static final int DEFAULT_COOLDOWN_SECONDS = 0;

    /** 读档冷却的可调范围（秒）；{@link #MIN_COOLDOWN_SECONDS} = 0 就是关闭冷却。 */
    public static final int MIN_COOLDOWN_SECONDS = 0;
    public static final int MAX_COOLDOWN_SECONDS = 3600;

    /**
     * 死亡后要不要自动回溯到「时间线上最近的那个节点」——时间线的头，也就是世界当前站着的那个存档点
     * （{@code RewindApi.currentSlot}）。默认 {@code false}：死亡保持原版行为。
     *
     * <p>开着时由客户端在自己死亡的那一下触发一次回溯；时间树上那个槽位会标红提示。
     */
    public static final boolean DEFAULT_ROLLBACK_ON_DEATH = false;

    /**
     * 回溯要不要走「原地回滚」（世界不关、客户端不重登，直接在活着的集成服务器里倒回去）。默认 {@code true}。
     *
     * <p>原地回滚快，但它**只回滚磁盘上的文件与内存里那部分被显式重建的状态**：进程里其它对象图
     * （第三方模组自己缓存的解码数据、静态单例……）不会跟着回到过去。所以只要装了这类模组，
     * 就可能出现「文件回滚了、游戏里看起来没有」。关掉它就退回「关世界 → 覆盖 → 重开」那条路：
     * 服务端对象全部重建、模组跟着世界重载走一遍，语义最干净，代价是慢（几秒 vs 几百毫秒）。
     *
     * <p>判定一个模组会不会中招见 {@code docs/AGENT.md} 的「原地回滚」一节。
     */
    public static final boolean DEFAULT_IN_PLACE_ROLLBACK = true;

    /**
     * 建点/回溯跳过非 NBT/MCA 文件（`advancements/`、`stats/`、`icon.png`；`.mcc` 算 MCA 一边）。
     *
     * <p>默认 {@code false}：走原路径，这些文件和其他文件一样进存档点、跟着回滚。
     * 开着则存档点操作（建点、回滚、离线修复、列举）统一看不到它们，磁盘上的原文件原地保留、不碰；
     * 原版游戏自己的读写照常运行。`playerdata/` 的 NBT、`datapacks/`、`serverconfig/` 不在范围内，照常备份。
     */
    public static final boolean DEFAULT_SKIP_NON_NBT_MCA = false;

    /**
     * zpack 建点写包用的 zstd 等级。默认 3：兼顾建点吞吐与补丁体积。
     *
     * <p>等级对建点耗时是超线性的（22 比 3 慢一个数量级以上），对体积的收益在 ≥7 之后迅速衰减——
     * 调高之前先用真实存档实测。
     */
    public static final int DEFAULT_ZSTD_LEVEL = 3;
    public static final int MIN_ZSTD_LEVEL = 1;
    public static final int MAX_ZSTD_LEVEL = 22;

    /**
     * DELTA 路径要不要开长距离匹配（zstd {@code --long}）：旧文件与新文件的匹配距离很远时
     * （大范围搬移）能显著提升压缩率。默认关。
     */
    public static final boolean DEFAULT_ZSTD_LONG = false;

    /**
     * 长距离匹配的窗口（log2 字节）。默认 27 = 128 MiB。
     *
     * <p>上限取 27 而不是 zstd 协议上限 31：窗口写进帧头，解压端必须有 ≥ 该值的解压窗口上限，
     * 而 zstd-jni-1.5.7-11 没有公开的「解压端窗口上限」设置口（只有要 DCtx 指针的 native 形式，
     * 指针拿不到），>27 的帧在别的 zstd 默认解压端上读不回来。27 对 NBT 差分已绰绰有余；
     * 开太大还会抬高回滚解压的内存占用（玩家在等）。
     */
    public static final int DEFAULT_ZSTD_LONG_WINDOW_LOG = 27;
    public static final int MIN_ZSTD_LONG_WINDOW_LOG = 10;
    public static final int MAX_ZSTD_LONG_WINDOW_LOG = 27;

    private static volatile boolean syncRecipeBook = DEFAULT_SYNC_RECIPE_BOOK;
    private static volatile boolean syncChunksNearPlayer = DEFAULT_SYNC_CHUNKS_NEAR_PLAYER;
    private static volatile int cooldownSeconds = DEFAULT_COOLDOWN_SECONDS;
    private static volatile boolean rollbackOnDeath = DEFAULT_ROLLBACK_ON_DEATH;
    private static volatile boolean inPlaceRollback = DEFAULT_IN_PLACE_ROLLBACK;
    private static volatile boolean skipNonNbtMca = DEFAULT_SKIP_NON_NBT_MCA;
    private static volatile int zstdLevel = DEFAULT_ZSTD_LEVEL;
    private static volatile boolean zstdLong = DEFAULT_ZSTD_LONG;
    private static volatile int zstdLongWindowLog = DEFAULT_ZSTD_LONG_WINDOW_LOG;

    private RollbackSettings() {
    }

    /** 回溯时要不要处理配方书。 */
    public static boolean syncRecipeBook() {
        return syncRecipeBook;
    }

    /**
     * 回溯时是不是只同步读回玩家视野内的受影响区块。
     *
     * <p>已废弃：回滚引擎为了保证覆盖层命中，一律全部同步读回，不再读这个值
     * （见 {@link #DEFAULT_SYNC_CHUNKS_NEAR_PLAYER}）。
     */
    public static boolean syncChunksNearPlayer() {
        return syncChunksNearPlayer;
    }

    /** 读档冷却时长（秒）；0 表示不冷却。 */
    public static int cooldownSeconds() {
        return cooldownSeconds;
    }

    /** 读档冷却时长（毫秒）。 */
    public static long cooldownMillis() {
        return cooldownSeconds() * 1000L;
    }

    /** 死亡后要不要自动回溯到时间线上最近的那个节点。 */
    public static boolean rollbackOnDeath() {
        return rollbackOnDeath;
    }

    /** 回溯是不是走原地回滚（关掉就退回「关世界 → 覆盖 → 重开」）。 */
    public static boolean inPlaceRollback() {
        return inPlaceRollback;
    }

    /** 建点/回溯要不要跳过非 NBT/MCA 文件。 */
    public static boolean skipNonNbtMca() {
        return skipNonNbtMca;
    }

    /** zpack 建点写包用的 zstd 等级（1-22）。 */
    public static int zstdLevel() {
        return zstdLevel;
    }

    /** DELTA 路径要不要开长距离匹配。 */
    public static boolean zstdLong() {
        return zstdLong;
    }

    /** 长距离匹配的窗口（log2 字节，10-27）。 */
    public static int zstdLongWindowLog() {
        return zstdLongWindowLog;
    }

    /** 配置加载 / 重载时把值抄进来。 */
    public static void apply(boolean recipeBook, boolean chunksNearPlayer, int cooldownSeconds, boolean rollbackOnDeath,
            boolean inPlaceRollback, boolean skipNonNbtMca) {
        syncRecipeBook = recipeBook;
        syncChunksNearPlayer = chunksNearPlayer;
        setCooldownSeconds(cooldownSeconds);
        RollbackSettings.rollbackOnDeath = rollbackOnDeath;
        RollbackSettings.inPlaceRollback = inPlaceRollback;
        RollbackSettings.skipNonNbtMca = skipNonNbtMca;
    }

    /** 配置加载 / 重载时把 zstd 三档抄进来（值各自夹到范围内）。 */
    public static void applyZstd(int level, boolean longEnabled, int longWindowLog) {
        setZstdLevel(level);
        zstdLong = longEnabled;
        setZstdLongWindowLog(longWindowLog);
    }

    /** 改配方书开关（只改内存；落盘由平台配置层负责）。 */
    public static void setSyncRecipeBook(boolean value) {
        syncRecipeBook = value;
    }

    /** 改「只同步读视野内区块」开关（只改内存；落盘由平台配置层负责）。 */
    public static void setSyncChunksNearPlayer(boolean value) {
        syncChunksNearPlayer = value;
    }

    /** 改「死亡后自动回溯」开关（只改内存；落盘由平台配置层负责）。 */
    public static void setRollbackOnDeath(boolean value) {
        rollbackOnDeath = value;
    }

    /** 改「原地回滚」开关（只改内存；落盘由平台配置层负责）。 */
    public static void setInPlaceRollback(boolean value) {
        inPlaceRollback = value;
    }

    /** 改「跳过非 NBT/MCA」开关（只改内存；落盘由平台配置层负责）。 */
    public static void setSkipNonNbtMca(boolean value) {
        skipNonNbtMca = value;
    }

    /**
     * 改读档冷却时长（只改内存；落盘由平台配置层负责）。
     * 值会被夹到 {@link #MIN_COOLDOWN_SECONDS} - {@link #MAX_COOLDOWN_SECONDS}。
     */
    public static void setCooldownSeconds(int value) {
        cooldownSeconds = clampCooldownSeconds(value);
    }

    /** 把冷却秒数夹到 {@link #MIN_COOLDOWN_SECONDS} - {@link #MAX_COOLDOWN_SECONDS}。 */
    public static int clampCooldownSeconds(int value) {
        if (value < MIN_COOLDOWN_SECONDS) {
            return MIN_COOLDOWN_SECONDS;
        }
        return Math.min(value, MAX_COOLDOWN_SECONDS);
    }

    /** 改 zstd 等级（只改内存；落盘由平台配置层负责）。值夹到 {@link #MIN_ZSTD_LEVEL} - {@link #MAX_ZSTD_LEVEL}。 */
    public static void setZstdLevel(int value) {
        zstdLevel = clampZstdLevel(value);
    }

    /** 把 zstd 等级夹到 {@link #MIN_ZSTD_LEVEL} - {@link #MAX_ZSTD_LEVEL}。 */
    public static int clampZstdLevel(int value) {
        if (value < MIN_ZSTD_LEVEL) {
            return MIN_ZSTD_LEVEL;
        }
        return Math.min(value, MAX_ZSTD_LEVEL);
    }

    /** 改长距离匹配窗口（只改内存；落盘由平台配置层负责）。值夹到 10 - {@value #MAX_ZSTD_LONG_WINDOW_LOG}。 */
    public static void setZstdLongWindowLog(int value) {
        zstdLongWindowLog = clampZstdLongWindowLog(value);
    }

    /** 把长距离匹配窗口夹到 {@link #MIN_ZSTD_LONG_WINDOW_LOG} - {@link #MAX_ZSTD_LONG_WINDOW_LOG}。 */
    public static int clampZstdLongWindowLog(int value) {
        if (value < MIN_ZSTD_LONG_WINDOW_LOG) {
            return MIN_ZSTD_LONG_WINDOW_LOG;
        }
        return Math.min(value, MAX_ZSTD_LONG_WINDOW_LOG);
    }
}
