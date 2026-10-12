package cc.sighs.rewind.snapshot;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ZPack 链式解析：沿时间树父子边从根向下重放（TODO2 §3.2）。
 *
 * <p>每个槽位的 {@code <slot>.zpack} 记录相对其 {@code parentSlot} 的差分。
 * 解析目标槽位 S 时：顺 parent 链向上收集到根，再自顶向下逐项 {@link ZPack#resolveEntry}
 * 重放。链上任何一环缺包即抛 {@link IOException}——「key 从没被写过」与「包丢了」
 * 必须可区分，绝不产出静默残缺的表（调用方按损坏回退旧路）。
 * 中间态经 LRU 缓存，避免深度 N 时间树产生 O(N) 解压雪崩；关键帧上限
 * {@link ZPack#KEYFRAME_CHAIN_LIMIT}（K≤20）保证最坏重放长度有界。
 *
 * <p>纯 JDK + common 依赖；输入输出均为未压缩 NBT 字节，不触 Minecraft 类型。
 */
public final class ZPackChain {
    /** 解压中间态 LRU 上限（槽位 key → raw）。 */
    private static final int CACHE_CAPACITY = 256;

    private final Path snapshotRoot;
    private final Map<String, byte[]> cache = new LinkedHashMap<String, byte[]>(CACHE_CAPACITY, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, byte[]> eldest) {
            return size() > CACHE_CAPACITY;
        }
    };
    /**
     * 已解包的 pack 缓存（同一次链操作内复用：建点时逐 key 算关键帧深度会反复走同一条父链）。
     *
     * <p>只缓存命中（缺席不缓存）；链对象按单次操作创建即弃（建点/回滚/GC 各自 new），
     * 包文件一旦落盘不可变，故无须失效逻辑。
     */
    private final Map<String, ZPack.Packed> packCache = new LinkedHashMap<>();

    public ZPackChain(Path snapshotRoot) {
        this.snapshotRoot = snapshotRoot;
    }

    /**
     * 解析目标槽位全部 Entry（key → 未压缩 NBT）。
     *
     * @param targetSlot 目标槽位
     * @param index      已加载索引（取 parent 链）
     */
    public Map<String, byte[]> resolveAll(String targetSlot, SnapshotIndex index) throws IOException {
        List<String> chain = chainToRoot(targetSlot, index);
        // chain: target -> ... -> root；反转为 root -> ... -> target
        List<ZPack.Packed> packs = new ArrayList<>();
        List<String> slots = new ArrayList<>();
        for (int i = chain.size() - 1; i >= 0; i--) {
            String slot = chain.get(i);
            ZPack.Packed packed = loadPack(slot);
            if (packed == null) {
                // 缺包一环：不能把「链里没有」当成「没写过」，显式按损坏处理
                throw new IOException("zpack chain hole: slot " + slot
                        + " has no pack while resolving " + targetSlot);
            }
            packs.add(packed);
            slots.add(slot);
        }
        Map<String, byte[]> current = new LinkedHashMap<>();
        for (int i = 0; i < packs.size(); i++) {
            String slot = slots.get(i);
            ZPack.Packed packed = packs.get(i);
            for (ZPack.Entry entry : packed.entries) {
                String key = entry.key();
                // LRU 命中直接复用已解析的同槽位同 key：中间祖先槽位同样可短路重放
                // （分支共享前缀链时，深层解析无需从根重新解压重算）
                byte[] cached = cached(slot, key);
                if (cached != null) {
                    current.put(key, cached);
                    continue;
                }
                byte[] base = current.get(key);
                byte[] raw;
                if (entry.mode == ZPack.MODE_FULL) {
                    raw = ZPack.resolveEntry(entry, null);
                } else {
                    if (base == null) {
                        throw new IOException("delta without base for " + key + " at slot " + slot);
                    }
                    raw = ZPack.resolveEntry(entry, base);
                }
                current.put(key, raw);
                putCache(slot, key, raw);
            }
        }
        return current;
    }

    /**
     * 计算自上一 FULL 以来的链深（用于建点时关键帧判定）。
     *
     * @param slot  新槽位的父槽位（从它向上数）
     * @param key   目标 key
     * @param index 索引
     * @return 连续 DELTA 深度（0 = 父槽位即 FULL 或无基底）
     */
    public int deltaDepth(String slot, String key, SnapshotIndex index) throws IOException {
        int depth = 0;
        String current = slot;
        int steps = 0;
        while (current != null && !current.isEmpty() && steps++ < SnapshotIndex.TIMELINE_WALK_LIMIT) {
            ZPack.Packed packed = loadPack(current);
            if (packed == null) {
                return depth;
            }
            ZPack.Entry entry = null;
            for (ZPack.Entry e : packed.entries) {
                if (e.key().equals(key)) {
                    entry = e;
                    break;
                }
            }
            if (entry == null) {
                return depth;
            }
            if (entry.mode == ZPack.MODE_FULL) {
                return depth;
            }
            depth++;
            SnapshotMeta meta = index.get(current);
            current = meta == null ? "" : meta.parentSlot;
        }
        return depth;
    }

    /** 是否应强制 FULL 关键帧（深度 ≥ K）。 */
    public static boolean needsKeyframe(int depth) {
        return depth >= ZPack.KEYFRAME_CHAIN_LIMIT;
    }

    // ---------------------------------------------------------- 内部

    private List<String> chainToRoot(String targetSlot, SnapshotIndex index) throws IOException {
        List<String> chain = new ArrayList<>();
        String current = targetSlot;
        int steps = 0;
        while (current != null && !current.isEmpty() && steps++ < SnapshotIndex.TIMELINE_WALK_LIMIT) {
            chain.add(current);
            SnapshotMeta meta = index.get(current);
            if (meta == null || meta.parentSlot == null || meta.parentSlot.isEmpty()) {
                break;
            }
            current = meta.parentSlot;
        }
        if (chain.isEmpty()) {
            throw new IOException("empty chain for slot " + targetSlot);
        }
        return chain;
    }

    private synchronized ZPack.Packed loadPack(String slot) throws IOException {
        ZPack.Packed cached = packCache.get(slot);
        if (cached != null) {
            return cached;
        }
        Path file = snapshotRoot.resolve(slot + SnapshotLayout.ZPACK_SUFFIX);
        if (!Files.isRegularFile(file)) {
            return null;
        }
        byte[] bytes = Files.readAllBytes(file);
        if (bytes.length == 0) {
            return null;
        }
        ZPack.Packed packed = ZPack.read(bytes);
        packCache.put(slot, packed);
        return packed;
    }

    private synchronized byte[] cached(String slot, String key) {
        return cache.get(slot + "\0" + key);
    }

    private synchronized void putCache(String slot, String key, byte[] raw) {
        cache.put(slot + "\0" + key, raw);
    }
}
