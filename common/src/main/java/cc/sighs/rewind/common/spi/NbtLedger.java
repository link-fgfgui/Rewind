package cc.sighs.rewind.common.spi;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 覆盖账本的公共骨架（{@link StorageOverlay} 与 {@link DataCover} 共用）：
 * 键 → 版本侧交来的不透明 NBT 句柄。
 *
 * <p>语义：{@code null} 入参记删除标记（变更集必须包含删除，{@link #serve} 对它返回
 * null），调用方用 {@link #covers} 区分「删了」与「没记」；存的是引用，拷贝由版本节点负责。
 * 线程安全（写监听在调用线程，读拦截在服务端/IO 线程，回滚引擎在服务端线程）。
 *
 * @param <K> 账本键类型（区块三元组 / 版本节点拼的字符串）
 */
public abstract class NbtLedger<K> {
    /**
     * 删除标记：`store` 进来 `null` 数据（区块文件被删）时记这个，而不是跳过——
     * 变更集必须包含删除，否则将来 `collectTargets` 会漏掉删掉的区块。
     */
    private static final Object TOMBSTONE = new Object();

    private final ConcurrentMap<K, Object> covered = new ConcurrentHashMap<>();
    private final AtomicLong serveHits = new AtomicLong();

    /** 记一条变更；{@code tag} 为 null 即删除标记。 */
    public void onWritten(K key, Object tag) {
        Objects.requireNonNull(key, "key");
        covered.put(key, tag == null ? TOMBSTONE : tag);
    }

    /**
     * 查一条数据。
     *
     * <p>删除标记与缺席统一返回 null（调用方用 {@link #covers} 区分）；只有 serve 到
     * 真数据才计入 {@link #serveHits()}。
     */
    public Object serve(K key) {
        Objects.requireNonNull(key, "key");
        Object tag = covered.get(key);
        if (tag == null || tag == TOMBSTONE) {
            return null;
        }
        serveHits.incrementAndGet();
        return tag;
    }

    /** 有没有这条（含删除标记）：判断「自从基线之后动过」用，不关心具体数据。 */
    public boolean covers(K key) {
        Objects.requireNonNull(key, "key");
        return covered.containsKey(key);
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

    /**
     * 账本快照遍历：键 → 值（删除标记还原成 null）。给建点分组、回滚对账与测试断言用，
     * 返回的是引用拷贝（调用方不得改键值对象）。
     */
    protected final List<Map.Entry<K, Object>> snapshot() {
        List<Map.Entry<K, Object>> out = new ArrayList<>(covered.size());
        for (Map.Entry<K, Object> e : covered.entrySet()) {
            Object tag = e.getValue();
            out.add(new java.util.AbstractMap.SimpleImmutableEntry<>(e.getKey(),
                    tag == TOMBSTONE ? null : tag));
        }
        return out;
    }
}
