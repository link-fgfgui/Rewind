package cc.sighs.rewind.snapshot;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import cc.sighs.rewind.common.spi.RewindPlatform;
import cc.sighs.rewind.common.spi.StorageKind;
import cc.sighs.rewind.common.spi.StorageOverlay;

/**
 * 建点/回滚两端的 zpack 桥（TODO2 Phase2 §3.1/§3.2，common 侧）。
 *
 * <p>写（建点）：把本次变更集（写账本条目 + 槽位内 NBT 松散文件）编成相对父槽位的
 * {@code <slot>.zpack}。附加式写入：失败只记日志，块存储仍是权威源，回滚自动退回旧路。
 *
 * <p>读（回滚）：{@link #tryResolve} 一次解出目标槽位的全量 key→raw 表。
 * 命中即快路；未命中（链上有洞、老槽位无包、建点时编不出）一律回退块存储旧路——
 * 调用方不得把“zpack 里没有”当成删除。
 *
 * <p>raw 规定：未压缩 NBT 字节（{@code NbtIo.write} 直出）。空数组表示删除标记
 * （账本 null tag 对应），调用方喂覆盖层时转回 null。
 *
 * <p>Java 8 兼容、无 Minecraft 依赖：NBT 编解码经 {@link RewindPlatform}，
 * {@code .dat} 的 gunzip 用纯 JDK。
 */
public final class ZPackCheckpoint {
    private ZPackCheckpoint() {
    }

    /** 一次写包的结果：条目数与载荷字节（建点日志与上机验收对账用）。 */
    public static final class Written {
        public final int entries;
        public final long payloadBytes;

        Written(int entries, long payloadBytes) {
            this.entries = entries;
            this.payloadBytes = payloadBytes;
        }
    }

    /** 写账本种类 → zpack 目标类型。 */
    public static int kindToType(StorageKind kind) {
        if (kind == null) {
            return ZPack.TYPE_CHUNK;
        }
        switch (kind) {
            case ENTITY:
                return ZPack.TYPE_ENTITY;
            case POI:
                return ZPack.TYPE_POI;
            case CHUNK:
            default:
                return ZPack.TYPE_CHUNK;
        }
    }

    /** zpack 目标类型 → 写账本种类（与 {@link #kindToType} 互逆，未知类型按 CHUNK）。 */
    public static StorageKind typeToKind(int targetType) {
        if (targetType == ZPack.TYPE_ENTITY) {
            return StorageKind.ENTITY;
        }
        if (targetType == ZPack.TYPE_POI) {
            return StorageKind.POI;
        }
        return StorageKind.CHUNK;
    }

    /**
     * 槽位内松散文件里哪些是 NBT（才进 zpack 的 DATA_FILE 项）。
     *
     * <p>只认原版写盘形态：{@code level.dat[.old]}、玩家数据、各维度 {@code data/*.dat}。
     * advancement/stats 的 JSON、icon.png、datapacks 等一律不进。
     */
    public static boolean isNbtDataFile(String relativePath) {
        if (relativePath == null) {
            return false;
        }
        String path = SnapshotLayout.normalize(relativePath);
        if (path.equals("level.dat") || path.equals("level.dat_old")) {
            return true;
        }
        if (!path.endsWith(".dat")) {
            return false;
        }
        if (path.startsWith("playerdata/") || path.startsWith("players/data/")) {
            return true;
        }
        return path.startsWith("data/") || path.contains("/data/");
    }

    /**
     * 建点写包（附加式：抛异常由调用方吞掉记日志）。
     *
     * @param worldRoot  存档目录
     * @param slot       本槽位
     * @param parentSlot 父槽位（空=根）
     * @param journal    本次变更集（写账本快照，tag 为 null 即删除标记）
     * @param slotFiles  本槽位松散文件相对路径（mirror 结果，筛 NBT 后入 DATA_FILE 项）
     * @param slotDir    本槽位目录（读松散文件用）
     * @param platform   NBT 编解码（raw 字节层）
     * @return 写入条目数与载荷字节
     */
    public static Written writeSlotZpack(Path worldRoot, String slot, String parentSlot,
            List<StorageOverlay.Entry> journal, List<String> slotFiles, Path slotDir,
            RewindPlatform platform) throws IOException {
        if (platform == null) {
            throw new IOException("no platform for zpack encode");
        }
        SnapshotIndex index = SnapshotIndex.load(SnapshotLayout.indexFile(worldRoot));
        // 父槽位全量 raw（无父/父无包/链损坏 → 空表，本包全 FULL，天然兜底）
        Map<String, byte[]> parentRaw = new HashMap<>();
        ZPackChain chain = new ZPackChain(SnapshotLayout.snapshotRoot(worldRoot));
        if (parentSlot != null && !parentSlot.isEmpty()) {
            try {
                parentRaw = chain.resolveAll(parentSlot, index);
            } catch (Throwable t) {
                parentRaw = new HashMap<>();
            }
        }
        ZPack.Writer writer = new ZPack.Writer();
        if (journal != null) {
            List<StorageOverlay.Entry> ordered = new ArrayList<>(journal);
            Collections.sort(ordered, (a, b) -> {
                int c = a.dimensionId.compareTo(b.dimensionId);
                if (c != 0) {
                    return c;
                }
                return Long.compare(a.chunkPos, b.chunkPos);
            });
            for (StorageOverlay.Entry e : ordered) {
                int type = kindToType(e.kind);
                String key = ZPack.keyOf(type, e.dimensionId, e.chunkPos);
                byte[] raw;
                if (e.tag == null) {
                    raw = new byte[0];
                } else {
                    raw = platform.encodeRawNbt(e.tag);
                    if (raw == null) {
                        continue;
                    }
                }
                byte[] dict = parentRaw.get(key);
                boolean forceFull = dict == null || needsKeyframe(chain, parentSlot, key, index);
                writer.add(ZPack.buildEntry(type, e.dimensionId, e.chunkPos,
                        raw, dict, forceFull, ZstdDelta.DEFAULT_LEVEL));
            }
        }
        if (slotFiles != null && slotDir != null) {
            List<String> ordered = new ArrayList<>(slotFiles);
            Collections.sort(ordered);
            for (String relative : ordered) {
                if (!isNbtDataFile(relative)) {
                    continue;
                }
                Path file = slotDir.resolve(relative);
                if (!Files.isRegularFile(file)) {
                    continue;
                }
                byte[] raw = gunzipFile(file);
                if (raw == null) {
                    continue;
                }
                String key = ZPack.keyOf(ZPack.TYPE_DATA_FILE, relative, 0L);
                byte[] dict = parentRaw.get(key);
                boolean forceFull = dict == null || needsKeyframe(chain, parentSlot, key, index);
                writer.add(ZPack.buildEntry(ZPack.TYPE_DATA_FILE, relative, 0L,
                        raw, dict, forceFull, ZstdDelta.DEFAULT_LEVEL));
            }
        }
        byte[] bytes = writer.toBytes();
        Path target = SnapshotLayout.zpackFile(worldRoot, slot);
        Path parent = target.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path tmp = target.resolveSibling(target.getFileName().toString() + ".rewind-tmp");
        try {
            Files.write(tmp, bytes);
            try {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicUnsupported) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException | RuntimeException e) {
            Files.deleteIfExists(tmp);
            throw e;
        }
        return new Written(writer.size(), writer.payloadBytes());
    }

    /**
     * 回滚读表：解出目标槽位全量 key→raw（空数组=删除标记）。
     *
     * @return 全量表；该槽位无 zpack 时返回 null（调用方走块存储旧路）
     * @throws IOException 链损坏（调用方同样回退旧路，不得当删除）
     */
    public static Map<String, byte[]> tryResolve(Path worldRoot, String slot) throws IOException {
        Path file = SnapshotLayout.zpackFile(worldRoot, slot);
        if (!Files.isRegularFile(file)) {
            return null;
        }
        SnapshotIndex index = SnapshotIndex.load(SnapshotLayout.indexFile(worldRoot));
        ZPackChain chain = new ZPackChain(SnapshotLayout.snapshotRoot(worldRoot));
        Map<String, byte[]> resolved = chain.resolveAll(slot, index);
        return Collections.unmodifiableMap(new LinkedHashMap<>(resolved));
    }

    /** 自上一 FULL 起的连续 DELTA 深度（读不到按 0 处理，外层再按 dict==null 兜底）。 */
    private static boolean needsKeyframe(ZPackChain chain, String parentSlot, String key,
            SnapshotIndex index) {
        if (parentSlot == null || parentSlot.isEmpty()) {
            return true;
        }
        try {
            return ZPackChain.needsKeyframe(chain.deltaDepth(parentSlot, key, index));
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 读 {@code .dat} 并 gunzip 出未压缩 NBT；非 gzip 或读失败返回 null（跳过该文件）。
     */
    static byte[] gunzipFile(Path file) {
        byte[] compressed;
        try {
            compressed = Files.readAllBytes(file);
        } catch (IOException e) {
            return null;
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(compressed.length * 2, 1024));
        GZIPInputStream in = null;
        try {
            in = new GZIPInputStream(new ByteArrayInputStream(compressed));
            byte[] buf = new byte[8192];
            int read;
            while ((read = in.read(buf)) >= 0) {
                out.write(buf, 0, read);
            }
            return out.toByteArray();
        } catch (IOException e) {
            return null;
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (IOException ignored) {
                    // ignore
                }
            }
        }
    }
}
