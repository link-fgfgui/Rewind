package cc.sighs.rewind.common;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import cc.sighs.rewind.common.spi.DataCover;
import cc.sighs.rewind.common.spi.StorageKind;
import cc.sighs.rewind.common.spi.StorageOverlay;

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
 *   <li>{@link #storageJournal()}：写拦截记下来的「建点之后写过的数据」（精确变更集）。
 *       由各版本节点的 mixin 在 `IOWorker.store` 经 {@link #recordChunkWrite} 登记
 *       （含首次写前冻结），建点成功后 {@link #clearStorageJournal()} 清掉、
 *       以新存档点为基线重记。原地回滚的 {@code collectTargets} 直接用它，
 *       不再做 {@code .mca} 头部 8 KiB 比对。</li>
 *   <li>{@link #coldBaselines()} / {@link #frozenBases()}：首次写前捕获
 *       （Copy-on-First-Write）。冷区块被读入内存时登记基线，首次被改写时冻结为
 *       该存档点的回滚基底；从未被写过的冷区块不占快照资源，回滚时直接放行走盘。</li>
 *   <li>{@link #dataCover()}：非区块 NBT 覆盖（存档数据 / 玩家数据）。回滚灌装时
 *       从槽位目录读入快照 {@code .dat}，读拦截在武装窗口内短路喂出。</li>
 *   <li>{@link #isStorageCoverArmed()}：覆盖层武装状态。回滚重载那一小段里置位，
 *       读拦截才短路走账本；平时读侧一律走原版（账本里即使有 journal 数据也不看）。
 *       武装前由回滚引擎把存档点数据灌进账本，撤装时连账本一起清掉、
 *       以回滚后的状态为新基线。</li>
 * </ul>
 */
public final class RewindState {
    private static volatile boolean discarding;

    /** {@code ChunkMap 实例 → 已排序的区块位置}。 */
    private static final Map<Object, long[]> unloadGuards = new HashMap<>();

    /** 写拦截的账本（单例；线程安全，见 {@link StorageOverlay}）。 */
    private static final StorageOverlay storageJournal = new StorageOverlay();

    /**
     * 冷区块读时基线（单例）：{@code loadAsync} 返回时登记“已加载但未改动”的初始引用，
     * {@code store} 首次写时消费（见 {@link #recordChunkWrite}）。
     * 从未被写过的冷区块完全不进快照，回滚时不在变更集内、读拦截直接放行走盘。
     */
    private static final StorageOverlay coldBaselines = new StorageOverlay();

    /**
     * 写前冻结基底（单例）：冷区块在当前存档点之后首次被改写时，把读时基线搬到这里，
     * 作为该存档点的回滚基底。回滚灌装（{@code populateCover}）优先用它，
     * 缺席才从快照块存储解码——二者都是快照数据，天然一致。
     */
    private static final StorageOverlay frozenBases = new StorageOverlay();

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
     *
     * <p>连带清掉读时基线（上一基线的“未改动”假设已随新存档点失效）；
     * 写前冻结基底按槽位归属保留在内存（它是旧槽位的回滚基底，持久化回填见建点流程），
     * 不随建点清空——内存开销与“自上次全量基线后改过的冷区块数”成正比。
     * 回滚完成后的清理由回滚收尾负责（撤装时清账本，见 {@link #disarmStorageCover}）。
     */
    public static void clearStorageJournal() {
        storageJournal.clear();
        coldBaselines.clear();
    }

    /**
     * 读时登记冷区块基线（Copy-on-First-Write 的前半段）。
     *
     * <p>调用方（各版本 {@code loadAsync} 返回包装）必须传独立拷贝；common 只存引用。
     * 已在变更集（建点后写过）或已登记过基线的坐标直接跳过，不覆盖。
     */
    public static void noteColdBaseline(StorageKind kind, String dimensionId, long chunkPos, Object tagCopy) {
        if (kind == null || dimensionId == null) {
            return;
        }
        if (storageJournal.covers(kind, dimensionId, chunkPos)
                || coldBaselines.covers(kind, dimensionId, chunkPos)) {
            return;
        }
        coldBaselines.putIfAbsent(kind, dimensionId, chunkPos, tagCopy);
    }

    /** 还需不需要登记该坐标的读时基线（读拦截包装里的快路径，避免无谓的 NBT 拷贝）。 */
    public static boolean needsColdBaseline(StorageKind kind, String dimensionId, long chunkPos) {
        if (kind == null || dimensionId == null) {
            return false;
        }
        return !storageJournal.covers(kind, dimensionId, chunkPos)
                && !coldBaselines.covers(kind, dimensionId, chunkPos);
    }

    /**
     * 记一次写盘（含首次写前冻结：Copy-on-First-Write 的后半段）。
     *
     * <p>该坐标自建点后第一次写、且有读时基线时，先把读时基线（写前数据）搬进冻结基底
     * （回滚基底），再记本次写入（写后数据）。调用方传进来的必须是写后 NBT 的独立拷贝；
     * 冻结搬的是读时存的那份引用（自登记后无人改过，复用引用安全）。
     *
     * <p>丢弃窗口（{@link #isDiscarding()}）内的写入是“卸掉的未来”，不记账、
     * 不冻结——回滚结束撤装时账本本来就要清，记了也白记，还会污染变更集。
     */
    public static void recordChunkWrite(StorageKind kind, String dimensionId, long chunkPos, Object journalCopy) {
        if (kind == null || dimensionId == null) {
            return;
        }
        if (isDiscarding()) {
            return;
        }
        if (!storageJournal.covers(kind, dimensionId, chunkPos)
                && coldBaselines.covers(kind, dimensionId, chunkPos)) {
            Object base = coldBaselines.serve(kind, dimensionId, chunkPos);
            // serve 对删除标记返回 null；基线是读到的数据，一定非空，这里只防一手
            if (base != null) {
                frozenBases.putIfAbsent(kind, dimensionId, chunkPos, base);
            }
            coldBaselines.discard(kind, dimensionId, chunkPos);
        }
        storageJournal.onWritten(kind, dimensionId, chunkPos, journalCopy);
    }

    /** 写前冻结基底（回滚灌装优先用它；返回引用，调用方要 {@code copy()} 后再交出去）。 */
    public static Object frozenBase(StorageKind kind, String dimensionId, long chunkPos) {
        if (kind == null || dimensionId == null) {
            return null;
        }
        return frozenBases.serve(kind, dimensionId, chunkPos);
    }

    public static boolean coversFrozenBase(StorageKind kind, String dimensionId, long chunkPos) {
        if (kind == null || dimensionId == null) {
            return false;
        }
        return frozenBases.covers(kind, dimensionId, chunkPos);
    }

    /** 读时基线账本（测试与回滚对账用；线上只经上述静态方法访问）。 */
    public static StorageOverlay coldBaselines() {
        return coldBaselines;
    }

    /** 冻结基底账本（测试与回滚灌装用）。 */
    public static StorageOverlay frozenBases() {
        return frozenBases;
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
     * <p>清掉写账本、读时基线与非区块覆盖；写前冻结基底保留（它是旧槽位的回滚基底，
     * 建点回填之前一直有效）。没武装时调用也安全（只清账本）。
     */
    public static void disarmStorageCover() {
        storageCoverArmed = false;
        storageJournal.clear();
        coldBaselines.clear();
        dataCover.clear();
    }

    /**
     * 世界卸载时调用：清掉全部跨世界不可复用的暂态（账本、基线、冻结基底、覆盖、重建名单）。
     *
     * <p>账本键是（种类，维度，坐标），不带世界身份——换世界不清，下个世界的同坐标回滚会吃到
     * 上个世界的旧数据。各版本在服务器停止/世界关闭处调一次（只调一次即可，幂等）。
     */
    public static void onWorldUnloaded() {
        disarmStorageCover();
        frozenBases.clear();
        clearUnloadGuards();
    }

    private RewindState() {
    }
}
