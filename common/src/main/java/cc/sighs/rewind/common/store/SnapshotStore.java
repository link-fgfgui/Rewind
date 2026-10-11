package cc.sighs.rewind.common.store;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import cc.sighs.rewind.common.RewindLog;
import cc.sighs.rewind.snapshot.SnapshotIndex;
import cc.sighs.rewind.snapshot.SnapshotLayout;
import cc.sighs.rewind.snapshot.SnapshotMeta;

/**
 * 槽位这条记录本身的增删改：删除、改名。
 *
 * <p>与 {@code cc.sighs.rewind.common.core.CheckpointWriter} 的分工：那边管「世界内容 ↔ 槽位」的搬运，
 * 这边管槽位记录。全是纯文件操作、不碰任何 Minecraft 类型，所以放在 common；任意线程可调，
 * 但**不要和存档点操作并发**——两边都会写 {@code index.properties}（调用前先看
 * {@code RewindApi.isBusy()}）。
 */
public final class SnapshotStore {
    private SnapshotStore() {
    }

    /**
     * 删除一个槽位（TODO2 Phase2 §3.3：纯软删除 Tombstone 墓碑机制）。
     *
     * <p>游戏内点删除仅在 {@code index.properties} 追加 {@code <slot>.tombstone=true}，
     * 毫秒级响应、0 延迟、0 链条计算、绝不断链：物理文件（槽位目录/zpack/清单/背包）
     * 全部保留，后续子节点的父子引用链完全不被破坏。UI 按墓碑过滤隐藏。
     * 真正的物理释放由离线 GC 整树重烘焙（{@code FullTreeRebakeWorker}）完成。
     *
     * <p>顺序是「先标记、再落盘」：崩在中间只是标记没写上，槽位仍可见，无数据丢失。
     *
     * @return 是否真的标记了东西（槽位存在且之前非墓碑）
     */
    public static boolean delete(Path worldRoot, String slot) throws IOException {
        Path indexFile = SnapshotLayout.indexFile(worldRoot);
        SnapshotIndex index = SnapshotIndex.load(indexFile);
        SnapshotMeta meta = index.get(slot);
        if (meta == null) {
            return false;
        }
        if (meta.tombstone) {
            return false;
        }
        meta.tombstone = true;
        index.put(meta, slot);
        // 头正好在这个槽位上：退回它的父节点（与硬删除一致，防头指向墓碑）
        if (slot.equals(index.getHead())) {
            String parent = meta.parentSlot;
            index.setHead(index.get(parent) == null ? "" : parent);
        }
        index.save(indexFile);
        RewindLog.LOGGER.info("Rewind: tombstoned checkpoint {}", slot);
        return true;
    }

    /**
     * 物理删除一个槽位（离线 GC / 兼容路径用）。
     *
     * <p>删掉索引条目、槽位目录、清单、块映射、背包快照与 zpack。
     * 游戏内删除不要调它（调 {@link #delete} 软删除）；GC 重烘焙切换成功后旧目录
     * 整体丢弃，不逐槽位调它。
     *
     * <p>顺序是「先摘索引条目、再删文件」：中途崩了留下的是没人引用的目录，而不是索引里指向一个
     * 已经不存在的目录的槽位。
     *
     * @return 是否真的删掉了东西
     */
    public static boolean deleteHard(Path worldRoot, String slot) throws IOException {
        Path indexFile = SnapshotLayout.indexFile(worldRoot);
        boolean removed = false;
        SnapshotIndex index = SnapshotIndex.load(indexFile);
        SnapshotMeta deleted = index.get(slot);
        if (index.remove(slot)) {
            // 时间线的「头」正好在这个槽位上：退回它的父节点（父节点也没了就退回「不知道」，
            // 下一个存档点会成为这条线的根）
            if (slot.equals(index.getHead())) {
                String parent = deleted == null ? "" : deleted.parentSlot;
                index.setHead(index.get(parent) == null ? "" : parent);
            }
            index.save(indexFile);
            removed = true;
        }
        removed |= deleteRecursively(SnapshotLayout.slotDir(worldRoot, slot));
        removed |= Files.deleteIfExists(SnapshotLayout.manifestFile(worldRoot, slot));
        removed |= Files.deleteIfExists(SnapshotLayout.blockMapFile(worldRoot, slot));
        removed |= Files.deleteIfExists(SnapshotLayout.inventoryFile(worldRoot, slot));
        removed |= Files.deleteIfExists(SnapshotLayout.zpackFile(worldRoot, slot));
        if (removed) {
            RewindLog.LOGGER.info("Rewind: deleted checkpoint {}", slot);
            // 槽位没了，它独有的那些 4 KiB 块也就没人引用了
            SnapshotBlockIo.collectGarbage(worldRoot);
        }
        return removed;
    }

    /**
     * 取消软删除（清墓碑标记，槽位重回 UI；物理文件本就没动）。
     *
     * @return 是否真的清掉了标记
     */
    public static boolean restore(Path worldRoot, String slot) throws IOException {
        Path indexFile = SnapshotLayout.indexFile(worldRoot);
        SnapshotIndex index = SnapshotIndex.load(indexFile);
        SnapshotMeta meta = index.get(slot);
        if (meta == null || !meta.tombstone) {
            return false;
        }
        meta.tombstone = false;
        index.put(meta, slot);
        index.save(indexFile);
        RewindLog.LOGGER.info("Rewind: restored checkpoint {}", slot);
        return true;
    }

    /**
     * 给槽位改名。
     *
     * <p>只改索引里的显示名：槽位 id 与磁盘目录都不动，所以改名不会让槽位丢失，也不会触发重拷。
     * 名字会被建点流程保留——覆盖同一个槽位不会把玩家的名字抹掉。
     *
     * @return 槽位是否存在
     */
    public static boolean rename(Path worldRoot, String slot, String displayName) throws IOException {        Path indexFile = SnapshotLayout.indexFile(worldRoot);
        SnapshotIndex index = SnapshotIndex.load(indexFile);
        SnapshotMeta meta = index.get(slot);
        if (meta == null) {
            return false;
        }
        meta.displayName = displayName == null ? "" : displayName.trim();
        index.put(meta, slot);
        index.save(indexFile);
        RewindLog.LOGGER.info("Rewind: renamed checkpoint {} to \"{}\"", slot, meta.displayName);
        return true;
    }

    private static boolean deleteRecursively(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) {
            return false;
        }
        try (Stream<Path> paths = Files.walk(dir)) {
            // walk 是前序：反过来遍历才能先删文件、后删目录
            for (Path path : paths.sorted(Comparator.reverseOrder()).collect(Collectors.toList())) {
                Files.deleteIfExists(path);
            }
        }
        return true;
    }
}
