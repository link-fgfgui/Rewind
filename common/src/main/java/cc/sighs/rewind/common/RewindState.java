package cc.sighs.rewind.common;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
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
 *       当前存档点状态的回滚基底；从不被写过的冷区块不占快照资源，回滚时直接放行走盘。
 *       基底带「基线世代」标记（见 {@link #advanceBaseline} / {@link #selectBaseline}），
 *       只服务同世代的回滚——跨存档点的旧基底一律忽略、回落快照解码，防止串数据。</li>
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
     *
     * <p>值里带「基线世代」：基底只对与它同世代的回滚有效（见 {@link #advanceBaseline} /
     * {@link #selectBaseline}）。跨存档点时旧基底内容已经过时，必须忽略回落解码，
     * 否则会把旧槽位的数据灌进新槽位。
     */
    private static final StorageOverlay frozenBases = new StorageOverlay();

    /**
     * 基线世代计数器：每次建点或回滚切槽位都换一个新值。
     *
     * <p>世代即「这份状态等于哪个存档点」——冻结基底按世代隔离，跨世代的基底不会被采纳。
     */
    private static final AtomicLong baselineGeneration = new AtomicLong();

    /** 当前世界状态对应的基线世代：冻结时打在基底上，灌装取用时比对。 */
    private static volatile long activeBaselineGeneration;

    /**
     * 本会话里各槽位建点时的基线世代：回滚时把世代切回目标槽位，让该槽位时代的基底重新可用。
     * 只活在内存里——重启后基底本来就没了，全部失配（回落快照解码）正好。
     */
    private static final Map<String, Long> slotBaselineGenerations = new ConcurrentHashMap<>();

    /** 冻结基底值：{@code tag} 是写前 NBT 拷贝，{@code generation} 是它所属的基线世代。 */
    private static final class FrozenBase {
        private final long generation;
        private final Object tag;

        private FrozenBase(long generation, Object tag) {
            this.generation = generation;
            this.tag = tag;
        }
    }

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
     * <p>连带清掉读时基线（上一基线的“未改动”假设已随新存档点失效）；冻结基底不清，
     * 由 {@link #advanceBaseline} 连基线世代一起前移。回滚完成后的清理由回滚收尾负责
     * （撤装时清账本，见 {@link #disarmStorageCover}）。
     */
    public static void clearStorageJournal() {
        storageJournal.clear();
        coldBaselines.clear();
    }

    /**
     * 建点后的基线前移：清账本 + 给新存档点发一个新基线世代，当前世界状态就站在这个世代上。
     *
     * <p>节点由「时间线的头」（正在覆盖的那个槽位）或任意新槽位承担，建完点世界就站在它上面。
     * 世代前移后，之前世代的冻结基底不会再被采纳（它们对应的是建点前那个存档点的状态），
     * 但仍留在内存里：回滚到那个旧槽位时 {@link #selectBaseline} 会把世代切回去、重新可用。
     * 内存开销与「各世代里被首次改写的冷区块数」成正比，停服由 {@link #onWorldUnloaded} 清空。
     */
    public static void advanceBaseline(String slot) {
        long generation = baselineGeneration.incrementAndGet();
        if (slot != null && !slot.isEmpty()) {
            slotBaselineGenerations.put(slot, generation);
        }
        activeBaselineGeneration = generation;
        clearStorageJournal();
    }

    /**
     * 回滚开始前把基线世代切到目标槽位：只有该槽位时代的冻结基底才允许被灌装采纳。
     *
     * <p>本会话里没有该槽位的建点记录（重启过，或槽位来自旧会话）时发一个全新世代——
     * 现有基底全部失配，回滚一律走快照块存储解码，宁慢勿串。
     */
    public static void selectBaseline(String slot) {
        Long generation = slot == null ? null : slotBaselineGenerations.get(slot);
        if (generation != null) {
            activeBaselineGeneration = generation;
        } else {
            invalidateBaseline();
        }
    }

    /**
     * 基线失配：当前世界状态不再等于任何已记录的存档点（典型：回滚半途失败）。
     *
     * <p>发一个全新世代让现有冻结基底全部作废——它们对应的旧状态已经对不上现实，
     * 之后回滚一律走快照解码，宁慢勿串。
     */
    public static void invalidateBaseline() {
        activeBaselineGeneration = baselineGeneration.incrementAndGet();
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
                Object existing = frozenBases.serve(kind, dimensionId, chunkPos);
                if (!(existing instanceof FrozenBase)
                        || ((FrozenBase) existing).generation != activeBaselineGeneration) {
                    // 每个坐标只留「当前世代」的那一份基底；旧世代的那份只在回滚到它的
                    // 存档点时才有意义，这里换掉不影响——那边本来就是同一份快照数据。
                    frozenBases.onWritten(kind, dimensionId, chunkPos,
                            new FrozenBase(activeBaselineGeneration, base));
                }
            }
            coldBaselines.discard(kind, dimensionId, chunkPos);
        }
        storageJournal.onWritten(kind, dimensionId, chunkPos, journalCopy);
    }

    /**
     * 写前冻结基底（回滚灌装优先用它；返回引用，调用方要 {@code copy()} 后再交出去）。
     *
     * <p>只有基底所属世代与当前基线世代一致时才返回：跨存档点的基底内容已经过时，
     * 返回 {@code null} 让调用方回落快照块存储解码。
     */
    public static Object frozenBase(StorageKind kind, String dimensionId, long chunkPos) {
        if (kind == null || dimensionId == null) {
            return null;
        }
        Object value = frozenBases.serve(kind, dimensionId, chunkPos);
        if (value instanceof FrozenBase && ((FrozenBase) value).generation == activeBaselineGeneration) {
            return ((FrozenBase) value).tag;
        }
        return null;
    }

    /** 当前世代有没有可用的冻结基底（见 {@link #frozenBase}）。 */
    public static boolean coversFrozenBase(StorageKind kind, String dimensionId, long chunkPos) {
        return frozenBase(kind, dimensionId, chunkPos) != null;
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
        slotBaselineGenerations.clear();
        clearUnloadGuards();
    }

    private RewindState() {
    }
}
