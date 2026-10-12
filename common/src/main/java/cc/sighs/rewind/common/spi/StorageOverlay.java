package cc.sighs.rewind.common.spi;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 覆盖层的 common 侧账本：同时实现 {@link StorageWriteSink}（记）与
 * {@link StorageReadSource}（查），存的是各版本交来的不透明 NBT 句柄。
 *
 * <p>键是「种类 + 维度 + 区块」三元组：同坐标在不同维度/种类下是不同条目。
 * 记/查/删除标记/命中计数的公共语义见 {@link NbtLedger}。
 *
 * <p>生命周期已全程接线，实例都挂在 {@link cc.sighs.rewind.common.RewindState}：
 * 写侧各版本节点的 mixin 在 `IOWorker.store` / `SavedData` 写盘点经 `onWritten` 登记变更集；
 * 读侧在武装窗口内由 `serve` 短路喂出快照，未命中放行走原版；建点成功后
 * `clearStorageJournal` 清账本、以新存档点为基线重记；回滚收尾 `armStorageCover` /
 * `disarmStorageCover` 开关覆盖窗口，撤装连账本一起清。
 */
public final class StorageOverlay extends NbtLedger<StorageOverlay.Key>
        implements StorageWriteSink, StorageReadSource {
    /** 覆盖条目 key：种类 + 维度 + 区块，三者缺一不可（同坐标在不同维度/种类下是不同条目）。 */
    static final class Key {
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

    @Override
    public void onWritten(StorageKind kind, String dimensionId, long chunkPos, Object tag) {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(dimensionId, "dimensionId");
        onWritten(new Key(kind, dimensionId, chunkPos), tag);
    }

    /** 账本快照：给建点分组（按 region 文件）与回滚对账用，返回的是引用拷贝（调用方不得改 map）。 */
    public List<Entry> entries() {
        List<Map.Entry<Key, Object>> snapshot = snapshot();
        List<Entry> out = new ArrayList<>(snapshot.size());
        for (Map.Entry<Key, Object> e : snapshot) {
            Key key = e.getKey();
            out.add(new Entry(key.kind, key.dimensionId, key.chunkPos, e.getValue()));
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
        return serve(new Key(kind, dimensionId, chunkPos));
    }

    public boolean covers(StorageKind kind, String dimensionId, long chunkPos) {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(dimensionId, "dimensionId");
        return covers(new Key(kind, dimensionId, chunkPos));
    }
}
