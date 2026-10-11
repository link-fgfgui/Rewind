package cc.sighs.rewind.common.spi;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 覆盖层的 common 侧账本：同时实现 {@link StorageWriteSink}（记）与
 * {@link StorageReadSource}（查），存的是各版本交来的不透明 NBT 句柄。
 *
 * <p>线程安全：原版 `store` / `loadAsync` 都跑在 `IOWorker` 的 mailbox 上串行，
 * 但回滚引擎与快照线程也可能读写这里，所以内部是 `ConcurrentHashMap`，调用方不需要额外加锁。
 *
 * <p>生命周期已全程接线，实例都挂在 {@link cc.sighs.rewind.common.RewindState}：
 * 写侧各版本节点的 mixin 在 `IOWorker.store` / `SavedData` 写盘点经 `onWritten` 登记变更集；
 * 读侧在武装窗口内由 `serve` 短路喂出快照，未命中放行走原版；建点成功后
 * `clearStorageJournal` 清账本、以新存档点为基线重记；回滚收尾 `armStorageCover` /
 * `disarmStorageCover` 开关覆盖窗口，撤装连账本一起清。
 * 同一个类还充当冷区块读时基线（`coldBaselines`）与写前冻结基底（`frozenBases`）的容器。
 */
public final class StorageOverlay implements StorageWriteSink, StorageReadSource {
    /** 覆盖条目 key：种类 + 维度 + 区块，三者缺一不可（同坐标在不同维度/种类下是不同条目）。 */
    private static final class Key {
        private final StorageKind kind;
        private final String dimensionId;
        private final long chunkPos;

        private Key(StorageKind kind, String dimensionId, long chunkPos) {
            this.kind = kind;
            this.dimensionId = dimensionId;
            this.chunkPos = chunkPos;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof Key)) {
                return false;
            }
            Key that = (Key) other;
            return chunkPos == that.chunkPos
                    && kind == that.kind
                    && dimensionId.equals(that.dimensionId);
        }

        @Override
        public int hashCode() {
            int result = kind.hashCode();
            result = 31 * result + dimensionId.hashCode();
            result = 31 * result + Long.hashCode(chunkPos);
            return result;
        }
    }

    private final ConcurrentMap<Key, Object> covered = new ConcurrentHashMap<>();
    private final AtomicLong serveHits = new AtomicLong();

    /**
     * 删除标记：`store` 进来 `null` 数据（区块文件被删）时记这个，而不是跳过——
     * 变更集必须包含删除，否则将来 `collectTargets` 会漏掉删掉的区块。
     * `serve` 永远不会把它交出去（返回 null），只认「有条目」这件事。
     */
    private static final Object TOMBSTONE = new Object();

    @Override
    public void onWritten(StorageKind kind, String dimensionId, long chunkPos, Object tag) {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(dimensionId, "dimensionId");
        covered.put(new Key(kind, dimensionId, chunkPos), tag == null ? TOMBSTONE : tag);
    }

    /**
     * 只有缺席时才记（写前基线 / 回滚基底去重用）：已有一条（含删除标记）时返回 false，
     * 不覆盖旧值。调用方传进来的必须是该版本 NBT 的独立拷贝，common 只存引用。
     */
    public boolean putIfAbsent(StorageKind kind, String dimensionId, long chunkPos, Object tag) {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(dimensionId, "dimensionId");
        return covered.putIfAbsent(new Key(kind, dimensionId, chunkPos), tag == null ? TOMBSTONE : tag) == null;
    }

    /** 账本快照：给建点分组（按 region 文件）与回滚对账用，返回的是引用拷贝（调用方不得改 map）。 */
    public List<Entry> entries() {
        List<Entry> out = new ArrayList<>(covered.size());
        for (java.util.Map.Entry<Key, Object> e : covered.entrySet()) {
            Key key = e.getKey();
            Object tag = e.getValue();
            out.add(new Entry(key.kind, key.dimensionId, key.chunkPos, tag == TOMBSTONE ? null : tag));
        }
        return out;
    }

    /**
     * 一条账本记录：{@code tag} 为 null 表示删除标记（变更集照样包含它，
     * {@link #serve} 对它返回 null，调用方用 {@link #covers} 区分“删了”与“没记”）。
     */
    public static final class Entry {
        public final StorageKind kind;
        public final String dimensionId;
        public final long chunkPos;
        public final Object tag;

        Entry(StorageKind kind, String dimensionId, long chunkPos, Object tag) {
            this.kind = kind;
            this.dimensionId = dimensionId;
            this.chunkPos = chunkPos;
            this.tag = tag;
        }
    }

    @Override
    public Object serve(StorageKind kind, String dimensionId, long chunkPos) {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(dimensionId, "dimensionId");
        Object tag = covered.get(new Key(kind, dimensionId, chunkPos));
        if (tag == null || tag == TOMBSTONE) {
            return null;
        }
        serveHits.incrementAndGet();
        return tag;
    }

    /**
     * 有没有这条（含删除标记）：给将来 `collectTargets` 判断「自从基线之后动过」用，
     * 不关心具体数据。
     */
    public boolean covers(StorageKind kind, String dimensionId, long chunkPos) {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(dimensionId, "dimensionId");
        return covered.containsKey(new Key(kind, dimensionId, chunkPos));
    }

    /**
     * 丢弃一条覆盖（「下次写自动同步」/ 窗口关闭时调）。
     *
     * @return 之前有没有这条（用于断言与统计，不影响行为）
     */
    public boolean discard(StorageKind kind, String dimensionId, long chunkPos) {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(dimensionId, "dimensionId");
        return covered.remove(new Key(kind, dimensionId, chunkPos)) != null;
    }

    /** 当前覆盖条目数（主要给日志与测试看）。 */
    public int size() {
        return covered.size();
    }

    /** 累计命中次数（只计 serve 到数据的那次；影子模式下验证覆盖层是否真的被读到）。 */
    public long serveHits() {
        return serveHits.get();
    }

    /** 清空全部覆盖（窗口关闭 / 世界卸载时调），同时清零命中计数。 */
    public void clear() {
        covered.clear();
        serveHits.set(0);
    }
}
