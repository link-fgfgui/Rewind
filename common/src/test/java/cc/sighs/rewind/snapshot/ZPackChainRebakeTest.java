package cc.sighs.rewind.snapshot;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * TODO2 P2+P3：墓碑软删除不删文件、自愈三态、整树重烘焙拍平与逐字节一致。
 */
class ZPackChainRebakeTest {

    @TempDir
    Path worldRoot;

    private static byte[] nbt(int size, int seed) {
        byte[] b = new byte[size];
        Random r = new Random(seed);
        for (int i = 0; i < size; i++) {
            b[i] = (byte) ("ZPACK:NBT:Sections:".charAt(i % 18) + r.nextInt(4));
        }
        return b;
    }

    private void writePack(String slot, ZPack.Writer writer) throws Exception {
        Path root = SnapshotLayout.snapshotRoot(worldRoot);
        Files.createDirectories(root);
        Files.write(root.resolve(slot + SnapshotLayout.ZPACK_SUFFIX), writer.toBytes());
    }

    private void putIndex(SnapshotMeta... metas) throws Exception {
        Path root = SnapshotLayout.snapshotRoot(worldRoot);
        Files.createDirectories(root);
        SnapshotIndex index = SnapshotIndex.empty();
        for (SnapshotMeta m : metas) {
            index.put(m, m.slot);
        }
        index.save(root.resolve(SnapshotLayout.INDEX_FILE_NAME));
    }

    private static SnapshotMeta meta(String slot, String parent) {
        SnapshotMeta m = new SnapshotMeta();
        m.slot = slot;
        m.status = SnapshotLayout.STATUS_COMPLETE;
        m.savedAtMillis = System.currentTimeMillis();
        m.parentSlot = parent;
        return m;
    }

    @Test
    void nineteenNodeBranchTopologySurvivesTombstoneAndRebake() throws Exception {
        // TEST-TREE-01 精神的无头版：主干 s1..s10，分叉 A（s11,s12）/ B（s13,s14），
        // 共 14 节点（19 节点拓扑的等价缩影：覆盖“主干＋双分支＋中间墓碑＋跨分支回滚”）。
        // 每槽位 3 个 chunk 项，逐槽递变；回滚即链式 resolve。
        final int perSlot = 3;
        final int rawSize = 8 * 1024;
        Map<String, byte[]> v = new java.util.LinkedHashMap<>();
        java.util.List<SnapshotMeta> metas = new java.util.ArrayList<>();
        // 主干
        byte[][] cur = new byte[perSlot][];
        for (int k = 0; k < perSlot; k++) {
            cur[k] = nbt(rawSize, 100 + k);
        }
        String parent = "";
        Map<String, Map<String, byte[]>> expect = new java.util.LinkedHashMap<>();
        for (int s = 1; s <= 10; s++) {
            String slot = "s" + s;
            for (int k = 0; k < perSlot; k++) {
                byte[] next = cur[k].clone();
                next[(s * 37 + k) % rawSize] ^= (byte) s;
                cur[k] = next;
                v.put(slot + "#" + k, next);
            }
            Map<String, byte[]> prev = expect.get(parent.isEmpty() ? null : parent);
            ZPack.Writer w = new ZPack.Writer();
            for (int k = 0; k < perSlot; k++) {
                byte[] dict = prev == null ? null : prev.get(key(k));
                w.add(ZPack.buildEntry(ZPack.TYPE_CHUNK, "dim", k, cur[k], dict, dict == null, 3));
            }
            writePack(slot, w);
            metas.add(meta(slot, parent));
            Map<String, byte[]> full = new java.util.LinkedHashMap<>();
            for (int k = 0; k < perSlot; k++) {
                full.put(key(k), cur[k]);
            }
            expect.put(slot, full);
            parent = slot;
        }
        // 分支 A：s11（子 s10）、s12（子 s11）；分支 B：s13（子 s10）、s14（子 s13）
        String[][] branches = {{"s11", "s10"}, {"s12", "s11"}, {"s13", "s10"}, {"s14", "s13"}};
        for (String[] edge : branches) {
            String slot = edge[0];
            Map<String, byte[]> prev = expect.get(edge[1]);
            // 从父状态分叉递变
            Map<String, byte[]> full = new java.util.LinkedHashMap<>();
            ZPack.Writer w = new ZPack.Writer();
            int n = Integer.parseInt(slot.substring(1));
            for (int k = 0; k < perSlot; k++) {
                byte[] next = prev.get(key(k)).clone();
                next[(n * 91 + k * 7) % rawSize] ^= (byte) (n + k);
                full.put(key(k), next);
                w.add(ZPack.buildEntry(ZPack.TYPE_CHUNK, "dim", k, next, prev.get(key(k)), false, 3));
            }
            writePack(slot, w);
            metas.add(meta(slot, edge[1]));
            expect.put(slot, full);
        }
        putIndex(metas.toArray(new SnapshotMeta[0]));
        SnapshotIndex index = SnapshotIndex.load(SnapshotLayout.indexFile(worldRoot));
        ZPackChain chain = new ZPackChain(SnapshotLayout.snapshotRoot(worldRoot));
        // 跨分支回滚：s12 与 s14 各自解出
        assertBranch(chain, index, expect, "s12");
        assertBranch(chain, index, expect, "s14");
        // 中间墓碑 s11：链不断，s12 照解；文件不删
        assertTrue(cc.sighs.rewind.common.store.SnapshotStore.delete(worldRoot, "s11"));
        SnapshotIndex tomb = SnapshotIndex.load(SnapshotLayout.indexFile(worldRoot));
        assertTrue(tomb.isTombstoned("s11"));
        assertTrue(Files.isRegularFile(SnapshotLayout.zpackFile(worldRoot, "s11")));
        assertBranch(chain, tomb, expect, "s12");
        // 整树重烘焙：墓碑消失，s12 父拍平为 s10，全员逐字节一致
        FullTreeRebakeWorker.Result result = FullTreeRebakeWorker.rebake(worldRoot, null);
        assertEquals(13, result.liveSlots);
        assertEquals(1, result.droppedTombstones);
        SnapshotIndex after = SnapshotIndex.load(SnapshotLayout.indexFile(worldRoot));
        assertTrue(after.get("s11") == null);
        assertEquals("s10", after.get("s12").parentSlot);
        ZPackChain afterChain = new ZPackChain(SnapshotLayout.snapshotRoot(worldRoot));
        assertBranch(afterChain, after, expect, "s12");
        assertBranch(afterChain, after, expect, "s14");
        assertBranch(afterChain, after, expect, "s10");
    }

    /**
     * TODO2-FIX #1 回归：30 深链重烘焙后体积不得膨胀、关键帧节律不得漂移。
     *
     * <p>旧 Bug：`chainDepth` 只看祖先是否有该 key，不看其实际写成 FULL 还是 DELTA，
     * 自第 21 个节点起深度累积恒 ≥K，s21..s30 全部退化为无字典 FULL（体积暴涨约 10 倍）。
     */
    @Test
    void thirtyNodeChainRebakeKeepsKeyframeCadenceAndSize() throws Exception {
        final int nodes = 30;
        final int rawSize = 16 * 1024;
        final String key = key(0);
        // 建点阶段用生产同款规则（ZPackChain.deltaDepth + needsKeyframe）造链：
        // s1 无基底 FULL，此后连续 DELTA 满 20 个（s2..s21）在 s22 处再落一个关键帧。
        ZPackChain chain = new ZPackChain(SnapshotLayout.snapshotRoot(worldRoot));
        SnapshotIndex index = SnapshotIndex.empty();
        List<SnapshotMeta> metas = new ArrayList<>();
        byte[] cur = nbt(rawSize, 7);
        String parent = "";
        for (int s = 1; s <= nodes; s++) {
            byte[] next = cur.clone();
            next[(s * 131) % rawSize] ^= (byte) s;
            byte[] dict = s == 1 ? null : cur;
            boolean forceFull = dict == null
                    || ZPackChain.needsKeyframe(chain.deltaDepth(parent, key, index));
            ZPack.Writer w = new ZPack.Writer();
            w.add(ZPack.buildEntry(ZPack.TYPE_CHUNK, "dim", 0L, next, dict, forceFull, 3));
            writePack("s" + s, w);
            SnapshotMeta m = meta("s" + s, parent);
            metas.add(m);
            index.put(m, m.slot);
            cur = next;
            parent = "s" + s;
        }
        putIndex(metas.toArray(new SnapshotMeta[0]));
        long before = FullTreeRebakeWorker.directorySize(SnapshotLayout.snapshotRoot(worldRoot));
        FullTreeRebakeWorker.Result result = FullTreeRebakeWorker.rebake(worldRoot, null);
        assertEquals(nodes, result.liveSlots);
        assertEquals(0, result.droppedTombstones);
        long after = FullTreeRebakeWorker.directorySize(SnapshotLayout.snapshotRoot(worldRoot));
        assertTrue(after <= before, "重烘焙后体积不得膨胀：" + before + " -> " + after);
        // 关键帧节律：仅 s1/s22 为 FULL 锚点，其余槽位保持 DELTA
        for (int s = 1; s <= nodes; s++) {
            ZPack.Packed packed = ZPack.read(
                    Files.readAllBytes(SnapshotLayout.zpackFile(worldRoot, "s" + s)));
            assertEquals(1, packed.entries.size(), "s" + s + " 条目数");
            int mode = packed.entries.get(0).mode;
            if (s == 1 || s == 22) {
                assertEquals(ZPack.MODE_FULL, mode, "s" + s + " 应为关键帧锚点");
            } else {
                assertEquals(ZPack.MODE_DELTA, mode, "s" + s + " 应保持差分链");
            }
        }
        // 最深节点仍逐字节还原
        SnapshotIndex afterIndex = SnapshotIndex.load(SnapshotLayout.indexFile(worldRoot));
        ZPackChain afterChain = new ZPackChain(SnapshotLayout.snapshotRoot(worldRoot));
        assertArrayEquals(cur, afterChain.resolveAll("s" + nodes, afterIndex).get(key));
    }

    private static String key(int k) {
        return ZPack.keyOf(ZPack.TYPE_CHUNK, "dim", k);
    }

    private static void assertBranch(ZPackChain chain, SnapshotIndex index,
            Map<String, Map<String, byte[]>> expect, String slot) throws Exception {
        Map<String, byte[]> got = chain.resolveAll(slot, index);
        Map<String, byte[]> want = expect.get(slot);
        assertEquals(want.size(), got.size(), "槽位 " + slot + " 条目数");
        for (Map.Entry<String, byte[]> e : want.entrySet()) {
            assertArrayEquals(e.getValue(), got.get(e.getKey()), "槽位 " + slot + " 键 " + e.getKey());
        }
    }

    @Test
    void chainResolvesAcrossParentLinks() throws Exception {
        byte[] v1 = nbt(32 * 1024, 1);
        byte[] v2 = v1.clone();
        for (int i = 500; i < 560; i++) {
            v2[i] ^= 0x11;
        }
        ZPack.Writer w1 = new ZPack.Writer();
        w1.add(ZPack.buildEntry(ZPack.TYPE_CHUNK, "dim", 7L, v1, null, true, 3));
        writePack("s1", w1);
        ZPack.Writer w2 = new ZPack.Writer();
        w2.add(ZPack.buildEntry(ZPack.TYPE_CHUNK, "dim", 7L, v2, v1, false, 3));
        writePack("s2", w2);
        putIndex(meta("s1", ""), meta("s2", "s1"));
        SnapshotIndex index = SnapshotIndex.load(SnapshotLayout.indexFile(worldRoot));
        ZPackChain chain = new ZPackChain(SnapshotLayout.snapshotRoot(worldRoot));
        Map<String, byte[]> resolved = chain.resolveAll("s2", index);
        assertEquals(1, resolved.size());
        assertArrayEquals(v2, resolved.values().iterator().next());
        // s1 仍解析出 v1
        assertArrayEquals(v1, chain.resolveAll("s1", index).values().iterator().next());
    }

    @Test
    void tombstoneKeepsFilesButHidesFromRepair() throws Exception {
        byte[] v = nbt(4096, 2);
        ZPack.Writer w = new ZPack.Writer();
        w.add(ZPack.buildEntry(ZPack.TYPE_DATA_FILE, "level.dat", 0L, v, null, true, 3));
        writePack("quick", w);
        SnapshotMeta m = meta("quick", "");
        putIndex(m);
        // 软删除：只写标记
        assertTrue(cc.sighs.rewind.common.store.SnapshotStore.delete(worldRoot, "quick"));
        SnapshotIndex after = SnapshotIndex.load(SnapshotLayout.indexFile(worldRoot));
        assertTrue(after.isTombstoned("quick"));
        assertTrue(Files.isRegularFile(SnapshotLayout.zpackFile(worldRoot, "quick")));
        // 链仍可解析（父子链不断）
        ZPackChain chain = new ZPackChain(SnapshotLayout.snapshotRoot(worldRoot));
        assertArrayEquals(v, chain.resolveAll("quick", after).values().iterator().next());
        // 第二次删返回 false（已是墓碑）
        assertFalse(cc.sighs.rewind.common.store.SnapshotStore.delete(worldRoot, "quick"));
        // 复活
        assertTrue(cc.sighs.rewind.common.store.SnapshotStore.restore(worldRoot, "quick"));
        assertFalse(SnapshotIndex.load(SnapshotLayout.indexFile(worldRoot)).isTombstoned("quick"));
    }

    @Test
    void selfHealingRecoversAllThreeStates() throws Exception {
        // 状态1：snapshots 缺席 + old 存在 → 搬回
        Path snapshots = SnapshotLayout.snapshotRoot(worldRoot);
        Path oldDir = SnapshotLayout.compactOldDir(worldRoot);
        Path tmpDir = SnapshotLayout.compactTmpDir(worldRoot);
        Files.createDirectories(oldDir);
        Files.write(oldDir.resolve("probe.txt"), new byte[]{1});
        SnapshotSelfHealing.Result r1 = SnapshotSelfHealing.heal(worldRoot);
        assertTrue(r1.recoveredOld);
        assertTrue(Files.isDirectory(snapshots));
        // 状态2：两者并存 → 清 old
        Files.createDirectories(oldDir);
        SnapshotSelfHealing.Result r2 = SnapshotSelfHealing.heal(worldRoot);
        assertTrue(r2.cleanedOld);
        assertFalse(Files.exists(oldDir));
        // 状态3：tmp 残留 → 清 tmp
        Files.createDirectories(tmpDir);
        SnapshotSelfHealing.Result r3 = SnapshotSelfHealing.heal(worldRoot);
        assertTrue(r3.cleanedTmp);
        assertFalse(Files.exists(tmpDir));
    }

    @Test
    void healSweepsOrphanedTempFiles() throws Exception {
        // 建点/镜像/zpack 写盘崩在 move 之前留下的 *.rewind-tmp：离线自愈见之即清，
        // 真文件（同名去后缀）分毫不碰；正式索引缺席时的 index.properties.tmp 不替它做主。
        Path snapshots = SnapshotLayout.snapshotRoot(worldRoot);
        Files.createDirectories(snapshots.resolve("s1"));
        Files.write(snapshots.resolve("quick.zpack.rewind-tmp"), new byte[]{1, 2});
        Files.write(snapshots.resolve("s1").resolve("level.dat.rewind-tmp"), new byte[]{3});
        Files.write(snapshots.resolve("quick.zpack"), new byte[]{9});
        SnapshotSelfHealing.Result r = SnapshotSelfHealing.heal(worldRoot);
        assertTrue(r.cleanedTemps);
        assertFalse(Files.exists(snapshots.resolve("quick.zpack.rewind-tmp")));
        assertFalse(Files.exists(snapshots.resolve("s1").resolve("level.dat.rewind-tmp")));
        assertArrayEquals(new byte[]{9}, Files.readAllBytes(snapshots.resolve("quick.zpack")));
        // 正式索引健在时的索引 tmp → 清；正式索引缺席时 → 留（无法分辨写完与否）
        Files.write(snapshots.resolve("index.properties"), "format=1\n".getBytes("UTF-8"));
        Files.write(snapshots.resolve("index.properties.tmp"), "format=1\n".getBytes("UTF-8"));
        SnapshotSelfHealing.Result r2 = SnapshotSelfHealing.heal(worldRoot);
        assertTrue(r2.cleanedTemps);
        assertFalse(Files.exists(snapshots.resolve("index.properties.tmp")));
        assertTrue(Files.isRegularFile(snapshots.resolve("index.properties")));
    }

    @Test
    void crashBetweenRelayAndSwapRecoversWithContentIntact() throws Exception {
        // 模拟离线 GC 三阶段切换 6a→6b 之间的 SIGKILL：让位已完成（snapshots 搬到 .compact_old），
        // 就位还没发生。重启后自愈必须把原快照一字不差地搬回来（验收 §六.3 的无头版本）。
        Path snapshots = SnapshotLayout.snapshotRoot(worldRoot);
        Path oldDir = SnapshotLayout.compactOldDir(worldRoot);
        Files.createDirectories(snapshots);
        byte[] pack = {(byte) 0x52, (byte) 0x57, (byte) 0x5A, (byte) 0x50, 1, 2, 3, 4};
        Files.write(snapshots.resolve("quick.zpack"), pack);
        Files.write(snapshots.resolve("index.properties"), "format=1\n".getBytes("UTF-8"));
        // 6a 让位（与 SnapshotSelfHealing.swapInCompacted 第一步同语义的目录重命名）
        Files.move(snapshots, oldDir);
        assertFalse(Files.exists(snapshots));
        // —— 此处崩溃（进程没了，6b 从未执行）——
        SnapshotSelfHealing.Result healed = SnapshotSelfHealing.heal(worldRoot);
        assertTrue(healed.recoveredOld);
        assertTrue(Files.isDirectory(snapshots));
        assertFalse(Files.exists(oldDir));
        assertArrayEquals(pack, Files.readAllBytes(snapshots.resolve("quick.zpack")),
                "自愈搬回的快照必须逐字节完好");
        assertTrue(Files.isRegularFile(snapshots.resolve("index.properties")));
        // 幂等：再跑一次什么都不动
        SnapshotSelfHealing.Result second = SnapshotSelfHealing.heal(worldRoot);
        assertFalse(second.touched());
    }

    @Test
    void rebakeDropsTombstonesAndStaysByteIdentical() throws Exception {
        byte[] v1 = nbt(16 * 1024, 21);
        byte[] v2 = v1.clone();
        for (int i = 200; i < 240; i++) {
            v2[i] ^= 0x5E;
        }
        byte[] v3 = v2.clone();
        for (int i = 900; i < 930; i++) {
            v3[i] ^= 0x2B;
        }
        ZPack.Writer w1 = new ZPack.Writer();
        w1.add(ZPack.buildEntry(ZPack.TYPE_CHUNK, "dim", 3L, v1, null, true, 3));
        writePack("s1", w1);
        ZPack.Writer w2 = new ZPack.Writer();
        w2.add(ZPack.buildEntry(ZPack.TYPE_CHUNK, "dim", 3L, v2, v1, false, 3));
        writePack("s2", w2);
        ZPack.Writer w3 = new ZPack.Writer();
        w3.add(ZPack.buildEntry(ZPack.TYPE_CHUNK, "dim", 3L, v3, v2, false, 3));
        writePack("s3", w3);
        SnapshotMeta m1 = meta("s1", "");
        SnapshotMeta m2 = meta("s2", "s1");
        SnapshotMeta m3 = meta("s3", "s2");
        putIndex(m1, m2, m3);
        // s2 软删除：s3 的父链穿过墓碑
        cc.sighs.rewind.common.store.SnapshotStore.delete(worldRoot, "s2");
        SnapshotIndex before = SnapshotIndex.load(SnapshotLayout.indexFile(worldRoot));
        ZPackChain beforeChain = new ZPackChain(SnapshotLayout.snapshotRoot(worldRoot));
        byte[] wantS3 = beforeChain.resolveAll("s3", before).values().iterator().next();
        assertArrayEquals(v3, wantS3);

        FullTreeRebakeWorker.Result result = FullTreeRebakeWorker.rebake(worldRoot, null);
        assertEquals(2, result.liveSlots);
        assertEquals(1, result.droppedTombstones);

        SnapshotIndex after = SnapshotIndex.load(SnapshotLayout.indexFile(worldRoot));
        assertTrue(after.get("s2") == null, "墓碑槽位不应写入新索引");
        assertTrue(after.get("s1") != null && after.get("s3") != null);
        // s3 新父应拍平为 s1（最近存活祖先）
        assertEquals("s1", after.get("s3").parentSlot);
        ZPackChain afterChain = new ZPackChain(SnapshotLayout.snapshotRoot(worldRoot));
        byte[] gotS3 = afterChain.resolveAll("s3", after).values().iterator().next();
        assertArrayEquals(v3, gotS3, "重烘焙前后存活槽位逐字节一致");
        assertArrayEquals(v1, afterChain.resolveAll("s1", after).values().iterator().next());
        // 残留目录已清理
        assertFalse(Files.exists(SnapshotLayout.compactTmpDir(worldRoot)));
        assertFalse(Files.exists(SnapshotLayout.compactOldDir(worldRoot)));
    }

    @Test
    void rebakeCarriesLegacyPayloadAndGcBlocks() throws Exception {
        // GC 后旧路不断粮：存活槽位的槽位目录/清单/块映射必须跟过去，blocks/ 按新映射 GC。
        // 否则切换后 populateDataCover（槽位 .dat 直读）与块存储解码无米下锅。
        byte[] v1 = nbt(16 * 1024, 31);
        ZPack.Writer w1 = new ZPack.Writer();
        w1.add(ZPack.buildEntry(ZPack.TYPE_CHUNK, "dim", 3L, v1, null, true, 3));
        writePack("s1", w1);
        ZPack.Writer w2 = new ZPack.Writer();
        w2.add(ZPack.buildEntry(ZPack.TYPE_CHUNK, "dim", 3L, v1, v1, false, 3));
        writePack("s2", w2);
        putIndex(meta("s1", ""), meta("s2", "s1"));
        // 旧路负载：s1 槽位 .dat + 清单 + 块映射；blocks/ 里一块被引用、一块孤儿
        byte[] dat = nbt(2048, 77);
        Path slotDir = SnapshotLayout.slotDir(worldRoot, "s1");
        Files.createDirectories(slotDir.resolve("playerdata"));
        Files.write(slotDir.resolve("playerdata/x.dat"), dat);
        SnapshotManifest manifest = SnapshotManifest.empty();
        manifest.put("playerdata/x.dat", dat.length, 1700000000000L);
        manifest.save(SnapshotLayout.manifestFile(worldRoot, "s1"));
        byte[] kept = nbt(5000, 41);
        byte[] orphan = nbt(5000, 42);
        List<String> keptHashes;
        List<String> orphanHashes;
        try (SnapshotBlockStore store = SnapshotBlockStore.open(SnapshotLayout.blocksRoot(worldRoot))) {
            keptHashes = store.encodeBytes(kept).hashes;
            orphanHashes = store.encodeBytes(orphan).hashes;
        }
        SnapshotBlocks map = SnapshotBlocks.empty();
        map.put("region/r.0.0.mca", kept.length, keptHashes);
        map.save(SnapshotLayout.blockMapFile(worldRoot, "s1"));
        // s2 墓碑（带自己的旧路文件，GC 后应一并消失）
        Files.createDirectories(SnapshotLayout.slotDir(worldRoot, "s2"));
        Files.write(SnapshotLayout.slotDir(worldRoot, "s2").resolve("junk.dat"), new byte[]{1});
        cc.sighs.rewind.common.store.SnapshotStore.delete(worldRoot, "s2");

        FullTreeRebakeWorker.Result result = FullTreeRebakeWorker.rebake(worldRoot, null);
        assertEquals(1, result.liveSlots);
        assertEquals(1, result.droppedTombstones);

        // 旧路文件逐字节跟过来
        assertArrayEquals(dat, Files.readAllBytes(
                SnapshotLayout.slotDir(worldRoot, "s1").resolve("playerdata/x.dat")));
        SnapshotManifest afterManifest =
                SnapshotManifest.load(SnapshotLayout.manifestFile(worldRoot, "s1"));
        assertTrue(afterManifest.get("playerdata/x.dat") != null);
        SnapshotBlocks afterMap = SnapshotBlocks.load(SnapshotLayout.blockMapFile(worldRoot, "s1"));
        assertTrue(afterMap.get("region/r.0.0.mca") != null);
        // 墓碑文件消失
        assertFalse(Files.exists(SnapshotLayout.zpackFile(worldRoot, "s2")));
        assertFalse(Files.exists(SnapshotLayout.slotDir(worldRoot, "s2")));
        // 被引用的块在新 blocks/ 里可解出；孤儿块已被 GC（逐哈希查新索引）
        SnapshotBlocks.Entry entry = afterMap.get("region/r.0.0.mca");
        try (SnapshotBlockStore store = SnapshotBlockStore.open(SnapshotLayout.blocksRoot(worldRoot))) {
            assertArrayEquals(kept, store.readAll(entry.hashes, entry.size));
        }
        Set<String> remaining = new java.util.HashSet<>();
        Path newBlocks = SnapshotLayout.blocksRoot(worldRoot);
        if (Files.isDirectory(newBlocks)) {
            try (java.nio.file.DirectoryStream<Path> shards = Files.newDirectoryStream(newBlocks)) {
                for (Path shard : shards) {
                    Path indexFile = shard.resolve("index");
                    if (!Files.isRegularFile(indexFile)) {
                        continue;
                    }
                    for (String line : Files.readAllLines(indexFile,
                            java.nio.charset.StandardCharsets.UTF_8)) {
                        if (line.isEmpty() || line.charAt(0) == '#') {
                            continue;
                        }
                        int space = line.indexOf(' ');
                        if (space > 0) {
                            remaining.add(line.substring(0, space));
                        }
                    }
                }
            }
        }
        assertTrue(remaining.containsAll(keptHashes), "被引用块必须保留");
        for (String orphanHash : orphanHashes) {
            assertFalse(remaining.contains(orphanHash), "孤儿块必须被 GC: " + orphanHash);
        }
        // zpack 快路不受影响
        SnapshotIndex after = SnapshotIndex.load(SnapshotLayout.indexFile(worldRoot));
        ZPackChain chain = new ZPackChain(SnapshotLayout.snapshotRoot(worldRoot));
        assertArrayEquals(v1, chain.resolveAll("s1", after).values().iterator().next());
    }
}
