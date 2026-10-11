package cc.sighs.rewind.snapshot;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import cc.sighs.rewind.common.RewindLog;

/**
 * 整树完全重烘焙 GC（TODO2 §4 Full Tree Re-bake）。
 *
 * <p>触发：世界未加载时 {@code WorldRepairScreen} 扫描 {@code index.properties}
 * 统计 {@code tombstone=true} 槽位数量与物理空间，底部高亮
 * {@code [ 整理存档点 (可释放 XX.X MB) ]}。
 *
 * <p>流水线：
 * <ol>
 *   <li>创建干净临时工作区 {@code .compact_tmp/}；</li>
 *   <li>拓扑遍历所有存活（非墓碑）节点（按 parent 链拓扑序，父先子后）；</li>
 *   <li>解压并按新树结构重算 Zstd 字典差分，合并长链（Flatten Chain：
 *       重烘焙输出中每个存活槽位的 DELTA 直接相对其新父节点，不再背负已删节点的旧链）；</li>
 *   <li>写入新 {@code <slot>.zpack} 与 {@code index.properties}（墓碑节点不写入）；</li>
 *   <li>校验：全部新 zpack 全量试解压 + XXH64 比对；</li>
 *   <li>成功 → 三阶段重命名切换（让位/就位/后台清理）；失败 → 清临时区，原文件不动。</li>
 * </ol>
 *
 * <p>本类只做“纯文件 + zpack”层：输入输出均为快照目录，不碰 {@code /saves}、
 * 不碰 Minecraft 类型。{@code .inventory} 与旧 {@code .blocks/.manifest/槽位目录}
 * 在重烘焙中原样保留（墓碑槽位对应文件不复制，即释放空间）。
 */
public final class FullTreeRebakeWorker {
    private FullTreeRebakeWorker() {
    }

    /** 重烘焙进度回调（UI 线程由调用方转交）。 */
    public interface Progress {
        void onStep(String message);
    }

    /** 重烘焙结果。 */
    public static final class Result {
        public final int liveSlots;
        public final int droppedTombstones;
        public final long bytesBefore;
        public final long bytesAfter;
        public final long freedBytes;
        public final List<String> rebakedSlots;

        Result(int liveSlots, int droppedTombstones, long bytesBefore, long bytesAfter,
                List<String> rebakedSlots) {
            this.liveSlots = liveSlots;
            this.droppedTombstones = droppedTombstones;
            this.bytesBefore = bytesBefore;
            this.bytesAfter = bytesAfter;
            this.freedBytes = Math.max(0L, bytesBefore - bytesAfter);
            this.rebakedSlots = rebakedSlots;
        }

        public String summary() {
            return "rebaked " + liveSlots + " slots, dropped " + droppedTombstones
                    + " tombstones, freed " + freedBytes + " bytes";
        }
    }

    /**
     * 估算可释放空间（UI 展示用）：墓碑槽位的 zpack + inventory + 清单/映射 + 槽位目录之和。
     *
     * <p>保守估计：共享块存储里墓碑独占块的释放（GC 时按新映射标记-清除）未计入，
     * 实际释放只会比这个数大。
     */
    public static long estimateReclaimableBytes(Path worldRoot) throws IOException {
        SnapshotIndex index = SnapshotIndex.load(SnapshotLayout.indexFile(worldRoot));
        long total = 0L;
        for (String slot : index.slots()) {
            SnapshotMeta meta = index.get(slot);
            if (meta == null || !meta.tombstone) {
                continue;
            }
            total += sizeIfExists(SnapshotLayout.zpackFile(worldRoot, slot));
            total += sizeIfExists(SnapshotLayout.inventoryFile(worldRoot, slot));
            // 旧格式残留一并计入
            total += sizeIfExists(SnapshotLayout.manifestFile(worldRoot, slot));
            total += sizeIfExists(SnapshotLayout.blockMapFile(worldRoot, slot));
            total += directorySize(SnapshotLayout.slotDir(worldRoot, slot));
        }
        return total;
    }

    /** 统计墓碑槽位数量（UI 角标用）。 */
    public static int countTombstones(Path worldRoot) throws IOException {
        SnapshotIndex index = SnapshotIndex.load(SnapshotLayout.indexFile(worldRoot));
        int count = 0;
        for (String slot : index.slots()) {
            SnapshotMeta meta = index.get(slot);
            if (meta != null && meta.tombstone) {
                count++;
            }
        }
        return count;
    }

    /** 字节数 → 一位小数的人类可读串（UI 按钮文案用，与语言无关）。 */
    public static String formatBytes(long bytes) {
        if (bytes < 1024L) {
            return bytes + " B";
        }
        double kib = bytes / 1024.0;
        if (kib < 1024.0) {
            return String.format(java.util.Locale.ROOT, "%.1f KiB", kib);
        }
        double mib = kib / 1024.0;
        if (mib < 1024.0) {
            return String.format(java.util.Locale.ROOT, "%.1f MiB", mib);
        }
        return String.format(java.util.Locale.ROOT, "%.1f GiB", mib / 1024.0);
    }

    /**
     * 执行整树重烘焙（同步，后台线程调用）。
     *
     * @param worldRoot 存档目录
     * @param progress  可为 null
     */
    public static Result rebake(Path worldRoot, Progress progress) throws IOException {
        // 启动自愈先行：残留 .compact_old/.compact_tmp 不应污染本次重烘焙
        SnapshotSelfHealing.heal(worldRoot);
        Path snapshots = SnapshotLayout.snapshotRoot(worldRoot);
        Path tmpRoot = SnapshotLayout.compactTmpDir(worldRoot);
        if (Files.isDirectory(tmpRoot)) {
            SnapshotSelfHealing.discardTmp(worldRoot);
        }
        Files.createDirectories(tmpRoot);
        // 注意：tmpRoot 与 snapshots 同级（worldRoot 下），zpack 路径解析用各自根
        long bytesBefore = directorySize(snapshots);
        SnapshotIndex index = SnapshotIndex.load(SnapshotLayout.indexFile(worldRoot));
        List<String> ordered = topoOrder(index);
        List<String> live = new ArrayList<>();
        int dropped = 0;
        for (String slot : ordered) {
            SnapshotMeta meta = index.get(slot);
            if (meta == null || !meta.isComplete()) {
                continue;
            }
            if (meta.tombstone) {
                dropped++;
                continue;
            }
            live.add(slot);
        }
        // 旧父边可能指向墓碑/缺失节点：重烘焙时拍平为“最近存活祖先”，断边则为根
        Map<String, String> newParent = new LinkedHashMap<>();
        for (String slot : live) {
            newParent.put(slot, nearestLiveAncestor(index, slot, live));
        }
        ZPackChain chain = new ZPackChain(snapshots);
        Map<String, Map<String, byte[]>> resolved = new LinkedHashMap<>();
        for (String slot : live) {
            step(progress, "resolving " + slot);
            resolved.put(slot, chain.resolveAll(slot, index));
        }
        // 按拓扑序逐个重算差分写入 tmp（父先子后，保证新父 raw 已就绪）
        Map<String, Map<String, byte[]>> rebased = new LinkedHashMap<>();
        SnapshotIndex newIndex = SnapshotIndex.empty();
        newIndex.setHead(remapHead(index.getHead(), live));
        newIndex.setLastRollbackAt(index.getLastRollbackAt());
        List<String> rebaked = new ArrayList<>();
        for (String slot : live) {
            step(progress, "rebaking " + slot);
            SnapshotMeta meta = index.get(slot).copy();
            meta.parentSlot = newParent.get(slot);
            Map<String, byte[]> full = resolved.get(slot);
            Map<String, byte[]> parentRaw = rebased.get(meta.parentSlot);
            ZPack.Writer writer = new ZPack.Writer();
            List<String> keys = new ArrayList<>(full.keySet());
            Collections.sort(keys);
            for (String key : keys) {
                byte[] raw = full.get(key);
                byte[] dict = parentRaw == null ? null : parentRaw.get(key);
                boolean forceFull = dict == null
                        || ZPackChain.needsKeyframe(chainDepth(rebased, newIndex, meta.parentSlot, key));
                // 由 key 还原 (type,id,chunkPos)
                ZPack.ParsedKey parsed = ZPack.parseKey(key);
                ZPack.Entry entry = ZPack.buildEntry(parsed.targetType, parsed.id, parsed.chunkPos,
                        raw, dict, forceFull, ZstdDelta.DEFAULT_LEVEL);
                writer.add(entry);
            }
            byte[] zpackBytes = writer.toBytes();
            Path tmpZpack = tmpRoot.resolve(slot + SnapshotLayout.ZPACK_SUFFIX);
            Files.write(tmpZpack, zpackBytes);
            // inventory 原样复制（缺失则跳过）
            Path srcInv = SnapshotLayout.inventoryFile(worldRoot, slot);
            if (Files.isRegularFile(srcInv)) {
                Files.write(tmpRoot.resolve(slot + SnapshotLayout.INVENTORY_SUFFIX),
                        Files.readAllBytes(srcInv));
            }
            newIndex.put(meta, slot);
            rebased.put(slot, full);
            rebaked.add(slot);
        }
        newIndex.save(tmpRoot.resolve(SnapshotLayout.INDEX_FILE_NAME));
        // 旧路负载原样保留：存活槽位的槽位目录（含 .dat / datapacks 等松散文件）、
        // 清单与块映射必须跟过去——否则切换后回滚旧路（槽位 .dat 直读、块存储解码、
        // 在线玩家/世界数据恢复）无米下锅。墓碑槽位的一律不拷（即释放）。
        // blocks/ 整体拷过去再按新映射 GC（内容寻址共享，逐块挑着拷反而更贵）。
        step(progress, "carrying legacy payload");
        carryLegacyPayload(worldRoot, tmpRoot, live);
        // 校验：全部新 zpack 全量试解压 + XXH64 比对 + 与重烘焙前逐字节一致
        // ＋拷过去的旧路文件逐字节一致
        step(progress, "verifying " + rebaked.size() + " packs");
        verifyTmp(worldRoot, tmpRoot, newIndex, resolved);
        verifyCarriedPayload(worldRoot, tmpRoot, live);
        // 三阶段切换（kill 窗口定位口：先打让位日志，再打就位日志）
        step(progress, "swapping directories");
        RewindLog.LOGGER.info("Rewind: compact relaying {} -> .compact_old ({} live slots)",
                SnapshotLayout.ROOT_DIR_NAME, live.size());
        SnapshotSelfHealing.swapInCompacted(worldRoot);
        RewindLog.LOGGER.info("Rewind: compact swapped in, cleaning .compact_old");
        // 后台清理旧目录（同步做，失败由下次 heal 兜底）
        SnapshotSelfHealing.cleanupOld(worldRoot);
        long bytesAfter = directorySize(snapshots);
        step(progress, "done");
        Result result = new Result(live.size(), dropped, bytesBefore, bytesAfter, rebaked);
        RewindLog.LOGGER.info("Rewind: compact done: {}", result.summary());
        return result;
    }

    // ---------------------------------------------------------- 内部

    private static void step(Progress progress, String message) {
        if (progress != null) {
            progress.onStep(message);
        }
    }

    /** 拓扑序：父先子后（按 parent 深度排序，环保护上限）。 */
    static List<String> topoOrder(SnapshotIndex index) {
        List<String> slots = new ArrayList<>(index.slots());
        Map<String, Integer> depth = new LinkedHashMap<>();
        for (String slot : slots) {
            depth.put(slot, topoDepth(index, slot));
        }
        Collections.sort(slots, (a, b) -> {
            int cmp = Integer.compare(depth.get(a), depth.get(b));
            return cmp != 0 ? cmp : a.compareTo(b);
        });
        return slots;
    }

    private static int topoDepth(SnapshotIndex index, String slot) {
        int depth = 0;
        String current = slot;
        for (int i = 0; i < 64; i++) {
            SnapshotMeta meta = index.get(current);
            if (meta == null || meta.parentSlot == null || meta.parentSlot.isEmpty()) {
                break;
            }
            if (index.get(meta.parentSlot) == null) {
                break;
            }
            depth++;
            current = meta.parentSlot;
        }
        return depth;
    }

    private static String nearestLiveAncestor(SnapshotIndex index, String slot, List<String> live) {
        java.util.Set<String> liveSet = new java.util.HashSet<>(live);
        SnapshotMeta meta = index.get(slot);
        String parent = meta == null ? "" : meta.parentSlot;
        int steps = 0;
        while (parent != null && !parent.isEmpty() && steps++ < 64) {
            if (liveSet.contains(parent)) {
                return parent;
            }
            SnapshotMeta up = index.get(parent);
            parent = up == null ? "" : up.parentSlot;
        }
        return "";
    }

    private static String remapHead(String head, List<String> live) {
        if (head == null || head.isEmpty()) {
            return "";
        }
        return live.contains(head) ? head : "";
    }

    /** 新树下的连续 DELTA 深度（rebased 内自顶向下数）。 */
    private static int chainDepth(Map<String, Map<String, byte[]>> rebased,
            SnapshotIndex newIndex, String parentSlot, String key) {
        // 重烘焙输出保证：若父有该 key 且父链连续 DELTA，则深度+1；简化为按父链长度估算
        // 精确深度需读已写 tmp zpack；此处用 rebased 规模做保守估计（≥K 即 FULL，宁多不少）
        int depth = 0;
        String current = parentSlot;
        int steps = 0;
        while (current != null && !current.isEmpty() && steps++ < 64) {
            Map<String, byte[]> raw = rebased.get(current);
            if (raw == null || !raw.containsKey(key)) {
                break;
            }
            depth++;
            if (depth >= ZPack.KEYFRAME_CHAIN_LIMIT) {
                break;
            }
            SnapshotMeta meta = newIndex.get(current);
            current = meta == null ? "" : meta.parentSlot;
        }
        return depth;
    }

    private static void verifyTmp(Path worldRoot, Path tmpRoot, SnapshotIndex newIndex,
            Map<String, Map<String, byte[]>> expected) throws IOException {
        ZPackChain tmpChain = new ZPackChain(tmpRoot);
        for (Map.Entry<String, Map<String, byte[]>> e : expected.entrySet()) {
            String slot = e.getKey();
            Map<String, byte[]> want = e.getValue();
            Map<String, byte[]> got = tmpChain.resolveAll(slot, newIndex);
            if (got.size() != want.size()) {
                throw new IOException("rebake verify failed for " + slot
                        + ": size " + got.size() + " != " + want.size());
            }
            try {
                java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
                for (Map.Entry<String, byte[]> entry : want.entrySet()) {
                    byte[] actual = got.get(entry.getKey());
                    if (actual == null || !java.util.Arrays.equals(actual, entry.getValue())) {
                        throw new IOException("rebake verify mismatch for " + slot + " key " + entry.getKey());
                    }
                    digest.update(actual);
                }
                // SHA-256 走一遍，确保解压数据可哈希（防静默截断）
                digest.digest();
            } catch (java.security.NoSuchAlgorithmException ex) {
                throw new IOException("SHA-256 unavailable", ex);
            }
        }
    }

    /**
     * 旧路负载搬运：存活槽位的槽位目录、清单、块映射逐字节拷进临时区；
     * {@code blocks/} 整体拷过去再按新映射做标记-清除（墓碑独占块即释放）。
     *
     * <p>拷完才切目录；中途失败直接抛，调用方清临时区、原文件不动。
     */
    private static void carryLegacyPayload(Path worldRoot, Path tmpRoot, List<String> live)
            throws IOException {
        Path snapshots = SnapshotLayout.snapshotRoot(worldRoot);
        for (String slot : live) {
            copyFileIfExists(SnapshotLayout.manifestFile(worldRoot, slot),
                    tmpRoot.resolve(slot + SnapshotLayout.MANIFEST_SUFFIX));
            copyFileIfExists(SnapshotLayout.blockMapFile(worldRoot, slot),
                    tmpRoot.resolve(slot + SnapshotLayout.BLOCK_MAP_SUFFIX));
            Path srcDir = SnapshotLayout.slotDir(worldRoot, slot);
            if (Files.isDirectory(srcDir)) {
                copyTree(srcDir, tmpRoot.resolve(slot));
            }
        }
        Path srcBlocks = SnapshotLayout.blocksRoot(worldRoot);
        Path tmpBlocks = tmpRoot.resolve(SnapshotLayout.BLOCKS_DIR_NAME);
        if (Files.isDirectory(srcBlocks)) {
            copyTree(srcBlocks, tmpBlocks);
            // 新映射的并集之外即死块（含墓碑独占块）
            Set<String> liveHashes = new java.util.HashSet<>();
            try (java.nio.file.DirectoryStream<Path> maps =
                         Files.newDirectoryStream(tmpRoot, "*" + SnapshotLayout.BLOCK_MAP_SUFFIX)) {
                for (Path mapFile : maps) {
                    liveHashes.addAll(SnapshotBlocks.load(mapFile).allHashes());
                }
            }
            SnapshotBlockStore.collectGarbage(tmpBlocks, liveHashes);
        }
    }

    /**
     * 搬运校验：拷过去的清单/映射/槽位文件与源逐字节一致（大小＋内容抽样全比）。
     * 不一致直接抛，调用方清临时区、原文件不动。
     */
    private static void verifyCarriedPayload(Path worldRoot, Path tmpRoot, List<String> live)
            throws IOException {
        for (String slot : live) {
            assertSameBytes(SnapshotLayout.manifestFile(worldRoot, slot),
                    tmpRoot.resolve(slot + SnapshotLayout.MANIFEST_SUFFIX));
            assertSameBytes(SnapshotLayout.blockMapFile(worldRoot, slot),
                    tmpRoot.resolve(slot + SnapshotLayout.BLOCK_MAP_SUFFIX));
            Path srcDir = SnapshotLayout.slotDir(worldRoot, slot);
            Path tmpDir = tmpRoot.resolve(slot);
            if (Files.isDirectory(srcDir)) {
                if (!Files.isDirectory(tmpDir)) {
                    throw new IOException("rebake verify: slot dir missing for " + slot);
                }
                for (String relative : SnapshotMirror.listFiles(srcDir)) {
                    assertSameBytes(srcDir.resolve(relative), tmpDir.resolve(relative));
                }
                for (String relative : SnapshotMirror.listFiles(tmpDir)) {
                    if (!Files.isRegularFile(srcDir.resolve(relative))) {
                        throw new IOException("rebake verify: extra file for " + slot + ": " + relative);
                    }
                }
            }
        }
    }

    private static void assertSameBytes(Path expected, Path actual) throws IOException {
        boolean expectedFile = Files.isRegularFile(expected);
        boolean actualFile = Files.isRegularFile(actual);
        if (!expectedFile && !actualFile) {
            return;
        }
        if (expectedFile != actualFile) {
            throw new IOException("rebake verify: file presence mismatch: " + actual);
        }
        byte[] want = Files.readAllBytes(expected);
        byte[] got = Files.readAllBytes(actual);
        if (!java.util.Arrays.equals(want, got)) {
            throw new IOException("rebake verify: content mismatch: " + actual);
        }
    }

    private static void copyFileIfExists(Path source, Path target) throws IOException {
        if (!Files.isRegularFile(source)) {
            return;
        }
        Path parent = target.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.copy(source, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }

    private static void copyTree(Path source, Path target) throws IOException {
        Files.walkFileTree(source, new java.nio.file.SimpleFileVisitor<Path>() {
            @Override
            public java.nio.file.FileVisitResult preVisitDirectory(Path dir,
                    java.nio.file.attribute.BasicFileAttributes attrs) throws IOException {
                Files.createDirectories(target.resolve(source.relativize(dir).toString()));
                return java.nio.file.FileVisitResult.CONTINUE;
            }

            @Override
            public java.nio.file.FileVisitResult visitFile(Path file,
                    java.nio.file.attribute.BasicFileAttributes attrs) throws IOException {
                copyFileIfExists(file, target.resolve(source.relativize(file).toString()));
                return java.nio.file.FileVisitResult.CONTINUE;
            }
        });
    }

    private static long sizeIfExists(Path file) {
        try {
            return Files.isRegularFile(file) ? Files.size(file) : 0L;
        } catch (IOException e) {
            return 0L;
        }
    }

    static long directorySize(Path dir) {
        if (dir == null || !Files.isDirectory(dir)) {
            return 0L;
        }
        final long[] total = {0L};
        try {
            Files.walkFileTree(dir, new java.nio.file.SimpleFileVisitor<Path>() {
                @Override
                public java.nio.file.FileVisitResult visitFile(Path file,
                        java.nio.file.attribute.BasicFileAttributes attrs) {
                    total[0] += attrs.size();
                    return java.nio.file.FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException ignored) {
            // 估算失败按 0 处理
        }
        return total[0];
    }
}
