package cc.sighs.rewind.common;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import cc.sighs.rewind.common.spi.DataCover;
import cc.sighs.rewind.common.spi.RewindPlatforms;
import cc.sighs.rewind.common.spi.StorageKind;
import cc.sighs.rewind.common.spi.StorageOverlay;
import cc.sighs.rewind.snapshot.SnapshotSelfHealing;

/**
 * 跨模块共享的全局状态：回滚窗口标记、「原地回滚本轮自己接管的区块」名单，以及存储拦截记下来的写集。
 *
 * <p>这几样东西原先是 target 侧 {@code Rewind} 的静态字段，但它们没有任何 Minecraft 依赖，
 * 而且 target 的 Mixin 与 common 的回滚编排都要读写，所以搬到 common。
 *
 * <ul>
 *   <li>{@link #isDiscarding()}：置位期间世界正准备被快照覆盖，任何「写下去也会被丢掉」的落盘都应该跳过
 *       （Mixin 检查它）。只在服务器线程 / 客户端线程的同一处设置与清除。</li>
 *   <li>{@link #beginUnloadGuard} / {@link #isRecreationBlocked}：原地回滚把区块从内存里丢掉之后，
 *       ticket 系统仍然认为它们「该加载」，邻居传播会把 {@code ChunkMap.updateChunkScheduling} 又叫回来、
 *       重建 holder 并钉住 worldgen 引用计数。这段时间里这些位置只允许降级 / 卸载，不允许重建。
 *       区块映射用不透明的 {@code Object} 当键，所以这里不需要任何 Minecraft 类型。只有服务端线程读写它。</li>
 *   <li>{@link #storageJournal()}：写拦截记下来的「建点之后写过的区块数据」（精确变更集）。
 *       由各版本节点的 mixin 在 `IOWorker.store` 经 {@link #recordChunkWrite} 登记，
 *       建点成功后 {@link #clearStorageJournal()} 清掉、以新存档点为基线重记。
 *       原地回滚的 {@code collectTargets} 直接用它（账本不可信的维度回退 {@code .mca} 头部比对）。</li>
 *   <li>{@link #dataCover()}：非区块 NBT 覆盖（存档数据 / 玩家数据）。回滚灌装时从 zpack
 *       解码装配（决策 2：全 ZPack 内存化，不读槽位松散 .dat），读拦截在武装窗口内短路喂出。</li>
 *   <li>{@link #isStorageCoverArmed()}：覆盖层武装状态。回滚重载那一小段里置位，
 *       读拦截才短路走账本；平时读侧一律走原版（账本里即使有 journal 数据也不看）。
 *       武装前由回滚引擎把存档点数据灌进账本，撤装时连账本一起清掉、
 *       以回滚后的状态为新基线。</li>
 * </ul>
 *
 * <p><b>已退役</b>：TODO1 的 COW（Copy-on-First-Write）写前冻结基底（{@code frozenBases} /
 * {@code coldBaselines} 及基线世代隔离）按合并决策 1 废除——回滚灌装统一走
 * 「zpack 快路 → 块存储兜底」双轨制，内存里不再保留跨槽位基底。
 */
public final class RewindState {
    private static volatile boolean discarding;

    /**
     * 多人在线回滚期标记（TODO4 §1.3）：覆盖**整个回滚期**（含开始的准备阶段），比
     * {@link #isDiscarding()} 长——后者只覆盖「世界正在被快照覆盖」的窗口。
     *
     * <p>置位期间：
     * <ul>
     *   <li>各版本的网络 Mixin 丢弃到达的移动 / 交互 / 放置等操作包（连接与心跳放行）；</li>
     *   <li>共享的冻结 Mixin 取消 {@code ServerLevel.tick}——世界模拟停住，连接与玩家列表照常 tick，
     *       于是 keep-alive 不受影响；</li>
     *   <li>客户端的过渡期界面豁免逻辑放行原生的加载屏（见 {@link #isNativeReloadPending()}）。</li>
     * </ul>
     *
     * <p>失败路径必须清（调度器统一在收尾时清）。
     */
    private static volatile boolean multiplayerRollbacking;

    /**
     * 原生重载标记：回滚成功后正在用原版 Respawn 流程让客户端重载世界。
     *
     * <p>客户端侧的 {@code RewindScreens} 在过渡期间会拦掉原版加载屏，这一小段要开豁免——
     * 原生重载靠的就是 {@code ReceivingLevelScreen} 兜底。
     */
    private static volatile boolean nativeReloadPending;

    /** {@code ChunkMap 实例 → 已排序的区块位置}。 */
    private static final Map<Object, long[]> unloadGuards = new HashMap<>();

    /** 写拦截的账本（单例；线程安全，见 {@link StorageOverlay}）。 */
    private static final StorageOverlay storageJournal = new StorageOverlay();

    /** 非区块 NBT 覆盖（存档数据 / 玩家数据，键见 {@link DataCover}）。 */
    private static final DataCover dataCover = new DataCover();

    /** 覆盖层武装状态（见 {@link #isStorageCoverArmed()}）。 */
    private static volatile boolean storageCoverArmed;

    public static void beginDiscard() {
        discarding = true;
    }

    public static void endDiscard() {
        discarding = false;
    }

    public static boolean isDiscarding() {
        return discarding;
    }

    /** 进入多人在线回滚期（见 {@link #isMultiplayerRollbacking()}）。 */
    public static void beginMultiplayerRollback() {
        multiplayerRollbacking = true;
    }

    /** 退出多人在线回滚期；失败 / 超时路径也必须调。 */
    public static void endMultiplayerRollback() {
        multiplayerRollbacking = false;
    }

    public static boolean isMultiplayerRollbacking() {
        return multiplayerRollbacking;
    }

    /** 开始原生客户端重载（见 {@link #isNativeReloadPending()}）。 */
    public static void beginNativeReload() {
        nativeReloadPending = true;
    }

    public static void endNativeReload() {
        nativeReloadPending = false;
    }

    public static boolean isNativeReloadPending() {
        return nativeReloadPending;
    }

    /**
     * 登记一批「本轮被回滚接管」的区块。
     *
     * @param positions 区块位置（{@code ChunkPos.toLong()}），**必须按升序排好**——查询走二分查找
     */
    public static void beginUnloadGuard(Object chunkMap, long[] positions) {
        unloadGuards.put(chunkMap, positions);
    }

    public static void clearUnloadGuards() {
        unloadGuards.clear();
    }

    public static boolean isRecreationBlocked(Object chunkMap, long pos) {
        long[] positions = unloadGuards.get(chunkMap);
        return positions != null && Arrays.binarySearch(positions, pos) >= 0;
    }

    /**
     * 写拦截的账本：各版本 mixin 在 `IOWorker.store` 登记，读侧（将来）按
     * `(StorageKind, dimensionId, chunkPos)` 查。
     */
    public static StorageOverlay storageJournal() {
        return storageJournal;
    }

    /**
     * 清掉写拦截的账本：建点成功后以新存档点为基线重记。
     */
    public static void clearStorageJournal() {
        storageJournal.clear();
    }

    /**
     * 记一次写盘（写后数据）。
     *
     * <p>调用方传进来的必须是写后 NBT 的独立拷贝。丢弃窗口（{@link #isDiscarding()}）内的
     * 写入是“卸掉的未来”，不记账——回滚结束撤装时账本本来就要清，记了也白记，还会污染变更集。
     */
    public static void recordChunkWrite(StorageKind kind, String dimensionId, long chunkPos, Object journalCopy) {
        if (kind == null || dimensionId == null) {
            return;
        }
        if (isDiscarding()) {
            return;
        }
        storageJournal.onWritten(kind, dimensionId, chunkPos, journalCopy);
    }

    /** 非区块 NBT 覆盖账本（存档数据 / 玩家数据）。 */
    public static DataCover dataCover() {
        return dataCover;
    }

    /**
     * 覆盖层是不是武装着：只有回滚重载那一小段是 true，读拦截才短路。
     *
     * <p>平时（包括正常游玩与建点）永远 false，读侧走原版——账本里的 journal 数据再新也不看，
     * 避免把「未来」当成备份端上来。
     */
    public static boolean isStorageCoverArmed() {
        return storageCoverArmed;
    }

    /**
     * 武装覆盖层：回滚引擎先把存档点数据灌进账本（`onWritten` 复用），再调这个。
     *
     * <p>调用方是版本侧回滚引擎的灌装步骤（`populateCover` 之后）；平时不要调。
     */
    public static void armStorageCover() {
        storageCoverArmed = true;
    }

    /**
     * 撤装覆盖层并清账本：回滚完成 = 新基线，之后重记。
     *
     * <p>清掉写账本与非区块覆盖；没武装时调用也安全（只清账本）。
     */
    public static void disarmStorageCover() {
        storageCoverArmed = false;
        storageJournal.clear();
        dataCover.clear();
    }

    /**
     * 世界卸载时调用：清掉全部跨世界不可复用的暂态（账本、覆盖、重建名单）。
     *
     * <p>账本键是（种类，维度，坐标），不带世界身份——换世界不清，下个世界的同坐标回滚会吃到
     * 上个世界的旧数据。各版本在服务器停止/世界关闭处调一次（只调一次即可，幂等）。
     */
    public static void onWorldUnloaded() {
        disarmStorageCover();
        clearUnloadGuards();
        endMultiplayerRollback();
        endNativeReload();
        endDiscard();
    }

    /**
     * 服务器启动、世界就绪时调用：执行离线 GC 崩溃窗口的启动自愈（TODO2 §4.2）。
     *
     * <p>只认“世界未加载时没人写盘”的那一刻：`WorldRepairScreen` 入口（世界未进）调一次，
     * 这里（服务端线程，关卡读完、首个 tick 之前）再调一次——直接进世界不经过修复屏时，
     * 靠的就是这一次。无事时只是几次 `isDirectory`，建点/回滚前早已结束，不存在竞态。
     * 专用服务器没有修复屏，这次是它唯一的自愈机会。
     *
     * @param server 平台自己的服务器对象（原样转交给平台取世界根）
     */
    public static void onServerStarted(Object server) {
        try {
            Path worldRoot = RewindPlatforms.require().worldRoot(server);
            SnapshotSelfHealing.Result healed = SnapshotSelfHealing.healQuietly(worldRoot);
            if (healed.touched()) {
                RewindLog.LOGGER.info("Rewind: startup self-healing: {}", healed);
            }
        } catch (Throwable t) {
            RewindLog.LOGGER.warn("Rewind: startup self-healing failed", t);
        }
    }

    private RewindState() {
    }
}
