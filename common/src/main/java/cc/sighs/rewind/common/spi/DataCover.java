package cc.sighs.rewind.common.spi;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 非区块 NBT 的覆盖账本：存档数据（SavedData {@code .dat}）与玩家数据共用。
 *
 * <p>键是版本节点拼的字符串（见各版本 {@code DataJournalMixins}）：
 * <ul>
 *   <li>存档数据：{@code "savedata|" + 数据文件绝对路径}（如
 *       {@code savedata|/saves/world/DIM-1/data/raids.dat}）。用绝对路径而不用
 *       “维度 + id”，是因为 26.1 的落盘布局是 {@code data/<ns>/<path>.dat}、
 *       维度目录还分大小写，用路径最不会错位。</li>
 *   <li>玩家数据：{@code "player|" + uuid 字符串}。</li>
 *   <li>level.dat 写监听：{@code "leveldat"}（只记不查，回滚走内存 WorldData）。</li>
 * </ul>
 *
 * <p>语义与 {@link StorageOverlay} 对齐：{@code null} 入参记删除标记（含删除的变更集），
 * {@code serve} 对删除标记与缺席统一返回 null，调用方用 {@link #covers} 区分；
 * 存的是版本 NBT 的不透明句柄，common 只存引用，拷贝由版本节点负责。
 * 线程安全（写监听在调用线程，读拦截在服务端/IO 线程，回滚引擎在服务端线程）。
 */
public final class DataCover {
    private final ConcurrentMap<String, Object> covered = new ConcurrentHashMap<>();
    private final AtomicLong serveHits = new AtomicLong();

    private static final Object TOMBSTONE = new Object();

    public void onWritten(String key, Object tag) {
        Objects.requireNonNull(key, "key");
        covered.put(key, tag == null ? TOMBSTONE : tag);
    }

    public boolean putIfAbsent(String key, Object tag) {
        Objects.requireNonNull(key, "key");
        return covered.putIfAbsent(key, tag == null ? TOMBSTONE : tag) == null;
    }

    public Object serve(String key) {
        Objects.requireNonNull(key, "key");
        Object tag = covered.get(key);
        if (tag == null || tag == TOMBSTONE) {
            return null;
        }
        serveHits.incrementAndGet();
        return tag;
    }

    public boolean covers(String key) {
        Objects.requireNonNull(key, "key");
        return covered.containsKey(key);
    }

    public boolean discard(String key) {
        Objects.requireNonNull(key, "key");
        return covered.remove(key) != null;
    }

    public int size() {
        return covered.size();
    }

    public long serveHits() {
        return serveHits.get();
    }

    public void clear() {
        covered.clear();
        serveHits.set(0);
    }

    public List<Map.Entry<String, Object>> entries() {
        List<Map.Entry<String, Object>> out = new ArrayList<>(covered.size());
        for (Map.Entry<String, Object> e : covered.entrySet()) {
            Object tag = e.getValue();
            final String key = e.getKey();
            final Object value = tag == TOMBSTONE ? null : tag;
            out.add(new java.util.AbstractMap.SimpleImmutableEntry<>(key, value));
        }
        return out;
    }
}
