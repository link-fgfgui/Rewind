package cc.sighs.rewind.test.realsave;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import cc.sighs.rewind.snapshot.SnapshotIndex;
import cc.sighs.rewind.snapshot.SnapshotLayout;
import cc.sighs.rewind.snapshot.SnapshotMeta;
import cc.sighs.rewind.snapshot.ZPack;
import cc.sighs.rewind.snapshot.ZPackChain;
import cc.sighs.rewind.snapshot.ZstdDelta;

/**
 * T4 全量回放（Level-B，不随仓库提交）：1 小时工业负载的全部差异 chunk 对与差异 `.dat` 对。
 *
 * <p>数据由 {@code tools/extract_golden_slices.py --with-t4} 生成到
 * {@code $REWIND_TEST_SLICES_DIR}（默认 {@code common/build/goldenslices-t4}）。
 * 目录不存在时假设不成立、测试跳过（FastCI 不依赖 1.9 GB 巨石归档）：
 * {@code REWIND_TEST_BACKUPS_ZIP=... python3 tools/extract_golden_slices.py --with-t4}
 * 后再跑本套件（NATIVE_TEST.md §6.2 命令 3）。
 *
 * <p>空 base = 快照后新建区块（退化 FULL），空 target = 快照后删除区块，两侧 round-trip 语义不变。
 */
class RealSaveFullReplayTest {

    @TempDir
    Path tempRoot;

    private static Path slicesDir() {
        String override = System.getenv("REWIND_TEST_SLICES_DIR");
        if (override != null && !override.isEmpty()) {
            return Paths.get(override);
        }
        return Paths.get("build", "goldenslices-t4");
    }

    private static byte[] read(Path file) throws Exception {
        return Files.readAllBytes(file);
    }

    /** Level-B manifest 全文（切片完整性对账用，无 JSON 解析器，只做 sha 子串匹配）。 */
    private static String readManifest(Path dir) throws Exception {
        Path manifest = dir.resolve("manifest.json");
        Assumptions.assumeTrue(Files.isRegularFile(manifest), "Level-B manifest 缺席");
        return new String(Files.readAllBytes(manifest), java.nio.charset.StandardCharsets.UTF_8);
    }

    private static String hex(byte[] bytes) {
        char[] digits = "0123456789abcdef".toCharArray();
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(digits[(b >> 4) & 0xF]).append(digits[b & 0xF]);
        }
        return sb.toString();
    }

    private static byte[] gunzip(byte[] data) throws Exception {
        try (GZIPInputStream in = new GZIPInputStream(new java.io.ByteArrayInputStream(data));
                ByteArrayOutputStream out = new ByteArrayOutputStream(data.length * 2)) {
            byte[] buf = new byte[8192];
            int read;
            while ((read = in.read(buf)) >= 0) {
                out.write(buf, 0, read);
            }
            return out.toByteArray();
        }
    }

    @Test
    @DisplayName("TEST-ZSTD-01: T4 全部差异 chunk 对 100% round-trip + 补丁统计")
    void replayAllChunkPairs() throws Exception {
        Path dir = slicesDir();
        Assumptions.assumeTrue(Files.isDirectory(dir),
                "Level-B 切片缺失（先跑 tools/extract_golden_slices.py --with-t4），跳过全量回放");
        List<Path> bases = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "t4_pair_*_base.nbt")) {
            for (Path p : stream) {
                bases.add(p);
            }
        }
        Assumptions.assumeTrue(!bases.isEmpty(), "Level-B 切片为空，跳过全量回放");
        Collections.sort(bases);
        String manifest = readManifest(dir);
        java.security.MessageDigest sha;
        try {
            sha = java.security.MessageDigest.getInstance("SHA-256");
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        long totalRaw = 0L;
        long totalPatch = 0L;
        int worst = 0;
        String worstName = "";
        List<String> top = new ArrayList<>();
        for (Path baseFile : bases) {
            String name = baseFile.getFileName().toString();
            String targetName = name.substring(0, name.length() - "_base.nbt".length()) + "_target.nbt";
            byte[] base = read(baseFile);
            byte[] target = read(dir.resolve(targetName));
            assertTrue(manifest.contains(hex(sha.digest(base))), "切片与清单不一致: " + name);
            assertTrue(manifest.contains(hex(sha.digest(target))), "切片与清单不一致: " + targetName);
            byte[] dict = base.length == 0 ? null : base;
            byte[] patch = ZstdDelta.compressDelta(target, dict, ZstdDelta.DEFAULT_LEVEL);
            assertArrayEquals(target, ZstdDelta.decompress(patch, dict, target.length),
                    "回放对不一致: " + name);
            totalRaw += target.length;
            totalPatch += patch.length;
            if (patch.length > worst) {
                worst = patch.length;
                worstName = name;
            }
            top.add(String.format(Locale.ROOT, "%s raw=%d patch=%d", name, target.length, patch.length));
        }
        Collections.sort(top);
        System.out.println("T4 replay: pairs=" + bases.size()
                + " totalRaw=" + totalRaw + " totalPatch=" + totalPatch
                + String.format(Locale.ROOT, " saving=%.2f%%", 100.0 * (1.0 - totalPatch / (double) totalRaw))
                + " worst=" + worst + "@" + worstName);
        for (int i = Math.max(0, top.size() - 5); i < top.size(); i++) {
            System.out.println("  " + top.get(i));
        }
        // 实测基线（2026-10-11，141 对 / 17.6MB raw → 19196 B）：留 3 倍以上余量。
        // 对应 NATIVE_TEST.md TEST-ZSTD-01（57 对 ≤15KB）按对数放大后的同等门限。
        assertTrue(totalPatch <= 65536, "T4 总补丁超标: " + totalPatch + " B");
        assertTrue(worst <= 8192, "单区块补丁超标: " + worst + " B@" + worstName);
        assertTrue(totalPatch * 100L < totalRaw,
                "总节省不足 99%: raw=" + totalRaw + " patch=" + totalPatch);
    }

    @Test
    @DisplayName("TEST-ZSTD-03b: T4 差异 .dat 对 100% round-trip + 补丁统计")
    void replayDatPairs() throws Exception {
        Path dir = slicesDir();
        Assumptions.assumeTrue(Files.isDirectory(dir), "Level-B 切片缺失，跳过 .dat 回放");
        List<Path> bases = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "t4_dat_*_base.dat")) {
            for (Path p : stream) {
                bases.add(p);
            }
        }
        Assumptions.assumeTrue(!bases.isEmpty(), "Level-B .dat 切片为空，跳过 .dat 回放");
        Collections.sort(bases);
        long totalPatch = 0L;
        for (Path baseFile : bases) {
            String name = baseFile.getFileName().toString();
            String targetName = name.substring(0, name.length() - "_base.dat".length()) + "_target.dat";
            byte[] baseRaw = gunzip(read(baseFile));
            byte[] targetRaw = gunzip(read(dir.resolve(targetName)));
            byte[] patch = ZstdDelta.compressDelta(targetRaw, baseRaw, ZstdDelta.DEFAULT_LEVEL);
            assertArrayEquals(targetRaw, ZstdDelta.decompress(patch, baseRaw, targetRaw.length),
                    "dat 回放对不一致: " + name);
            totalPatch += patch.length;
            System.out.println("  " + name + " raw=" + targetRaw.length + " patch=" + patch.length);
            assertTrue(patch.length <= 5120, ".dat 补丁膨胀: " + name + " = " + patch.length + " B");
        }
        System.out.println("T4 dat replay: pairs=" + bases.size() + " totalPatch=" + totalPatch);
        // 实测基线（16 对 → 2724 B）：留约 6 倍余量。
        assertTrue(totalPatch <= 16384, "T4 .dat 总补丁超标: " + totalPatch + " B");
    }

    /** 建点写包桩平台：tag 即 byte[] 恒等编解码（只量 zpack 层吞吐，不量 NBT 序列化）。 */
    private static final class IdentityPlatform implements cc.sighs.rewind.common.spi.RewindPlatform {
        @Override
        public String modVersion() {
            return "test";
        }

        @Override
        public Path worldRoot(Object server) {
            return null;
        }

        @Override
        public boolean isSameThread(Object server) {
            return true;
        }

        @Override
        public boolean isDedicatedServer(Object server) {
            return false;
        }

        @Override
        public boolean isPublished(Object server) {
            return false;
        }

        @Override
        public boolean hasPlayers(Object server) {
            return false;
        }

        @Override
        public cc.sighs.rewind.common.spi.FlushOutcome flushForCheckpoint(Object server, String slot,
                String source) {
            return null;
        }

        @Override
        public boolean supportsInPlaceRollback() {
            return false;
        }

        @Override
        public cc.sighs.rewind.common.spi.RollbackOutcome rollbackInPlace(Object server, Path worldRoot,
                String slot) {
            return null;
        }

        @Override
        public ClassLoader modClassLoader(String modId) {
            return null;
        }

        @Override
        public byte[] encodeRawNbt(Object tag) {
            return tag instanceof byte[] ? (byte[]) tag : null;
        }

        @Override
        public Object decodeChunkSector(byte[] uncompressed) {
            return uncompressed;
        }

        @Override
        public cc.sighs.rewind.common.spi.EncodedSector encodeChunkSector(Object tag) {
            return null;
        }

        @Override
        public String chunkDirRelative(cc.sighs.rewind.common.spi.StorageKind kind, String dimensionId) {
            return null;
        }
    }

    private static long timeWritePack(Path world, String slot, List<byte[]> raws) throws Exception {
        cc.sighs.rewind.common.spi.StorageOverlay journal =
                new cc.sighs.rewind.common.spi.StorageOverlay();
        for (int i = 0; i < raws.size(); i++) {
            journal.onWritten(cc.sighs.rewind.common.spi.StorageKind.CHUNK, "dim", i, raws.get(i));
        }
        long start = System.nanoTime();
        cc.sighs.rewind.snapshot.ZPackCheckpoint.Written written =
                cc.sighs.rewind.snapshot.ZPackCheckpoint.writeSlotZpack(world, slot, "",
                journal.entries(), Collections.<String>emptyList(),
                world.resolve("rewind_snapshots").resolve(slot), new IdentityPlatform());
        long elapsedMs = (System.nanoTime() - start) / 1000000L;
        assertTrue(written.entries == raws.size(), "应全量入包");
        assertTrue(Files.isRegularFile(world.resolve("rewind_snapshots").resolve(slot + ".zpack")));
        return elapsedMs;
    }

    @Test
    @DisplayName("TEST-BENCH-02(zpack 层): 35s 微变建点写包耗时 ≤10ms")
    void checkpointMicroThroughput() throws Exception {
        Path world = tempRoot.resolve("micro");
        Files.createDirectories(world);
        byte[] target;
        try (java.io.InputStream in = RealSaveFullReplayTest.class.getResourceAsStream(
                "/goldenslices/t1_target_chunk_14_18.nbt")) {
            Assumptions.assumeTrue(in != null, "Level-A 切片缺席");
            ByteArrayOutputStream out = new ByteArrayOutputStream(8192);
            byte[] buf = new byte[8192];
            int read;
            while ((read = in.read(buf)) >= 0) {
                out.write(buf, 0, read);
            }
            target = out.toByteArray();
        }
        List<byte[]> raws = Collections.singletonList(target);
        timeWritePack(world, "s1", raws); // 预热
        long elapsedMs = timeWritePack(world, "s2", raws);
        System.out.println("checkpoint-micro: entries=1 totalMs=" + elapsedMs);
        assertTrue(elapsedMs <= 10, "微变建点 " + elapsedMs + "ms 超出 10ms 预算");
    }

    @Test
    @DisplayName("TEST-BENCH-02(zpack 层): 1h 工业负载建点写包耗时 ≤150ms")
    void checkpointIndustrialThroughput() throws Exception {
        Path dir = slicesDir();
        Assumptions.assumeTrue(Files.isDirectory(dir), "Level-B 切片缺失，跳过建点吞吐");
        List<Path> bases = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "t4_pair_*_base.nbt")) {
            for (Path p : stream) {
                bases.add(p);
            }
        }
        Assumptions.assumeTrue(!bases.isEmpty(), "Level-B 切片为空，跳过建点吞吐");
        Collections.sort(bases);
        List<byte[]> raws = new ArrayList<>(bases.size());
        long totalBytes = 0L;
        for (Path baseFile : bases) {
            String name = baseFile.getFileName().toString();
            byte[] target = Files.readAllBytes(dir.resolve(
                    name.substring(0, name.length() - "_base.nbt".length()) + "_target.nbt"));
            raws.add(target);
            totalBytes += target.length;
        }
        Path world = tempRoot.resolve("industrial");
        Files.createDirectories(world);
        timeWritePack(world, "s1", raws); // 预热（类加载/JNI 首调用不计入）
        long elapsedMs = timeWritePack(world, "s2", raws);
        System.out.println("checkpoint-industrial: entries=" + raws.size()
                + " rawBytes=" + totalBytes + " totalMs=" + elapsedMs);
        assertTrue(elapsedMs <= 150, "工业建点 " + elapsedMs + "ms 超出 150ms 预算");
    }

    @Test
    @DisplayName("TEST-BENCH-03(zpack 层): 141 项单包全量解析灌装耗时 ≤50ms")
    void timedCoverResolve() throws Exception {
        Path dir = slicesDir();
        Assumptions.assumeTrue(Files.isDirectory(dir), "Level-B 切片缺失，跳过灌装计时");
        List<Path> bases = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "t4_pair_*_base.nbt")) {
            for (Path p : stream) {
                bases.add(p);
            }
        }
        Assumptions.assumeTrue(!bases.isEmpty(), "Level-B 切片为空，跳过灌装计时");
        Collections.sort(bases);
        // 建包：s1 全 FULL（141 项），s2 全 DELTA（相对 s1），键用合成坐标（只量解码吞吐）。
        Path snapshots = tempRoot.resolve("rewind_snapshots");
        Files.createDirectories(snapshots);
        SnapshotIndex index = SnapshotIndex.empty();
        List<byte[]> targets = new ArrayList<>(bases.size());
        ZPack.Writer w1 = new ZPack.Writer();
        int i = 0;
        for (Path baseFile : bases) {
            String name = baseFile.getFileName().toString();
            Path targetFile = dir.resolve(
                    name.substring(0, name.length() - "_base.nbt".length()) + "_target.nbt");
            byte[] base = Files.readAllBytes(baseFile);
            byte[] target = Files.readAllBytes(targetFile);
            targets.add(target);
            w1.add(ZPack.buildEntry(ZPack.TYPE_CHUNK, "dim", i, base, null, true, 3));
            i++;
        }
        Files.write(snapshots.resolve("s1" + SnapshotLayout.ZPACK_SUFFIX), w1.toBytes());
        ZPack.Writer w2 = new ZPack.Writer();
        int j = 0;
        for (Path baseFile : bases) {
            String name = baseFile.getFileName().toString();
            Path targetFile = dir.resolve(
                    name.substring(0, name.length() - "_base.nbt".length()) + "_target.nbt");
            byte[] base = Files.readAllBytes(baseFile);
            byte[] target = Files.readAllBytes(targetFile);
            w2.add(ZPack.buildEntry(ZPack.TYPE_CHUNK, "dim", j,
                    target, base.length == 0 ? null : base, false, 3));
            j++;
        }
        Files.write(snapshots.resolve("s2" + SnapshotLayout.ZPACK_SUFFIX), w2.toBytes());
        SnapshotMeta m1 = new SnapshotMeta();
        m1.slot = "s1";
        m1.status = SnapshotLayout.STATUS_COMPLETE;
        m1.parentSlot = "";
        index.put(m1, "s1");
        SnapshotMeta m2 = new SnapshotMeta();
        m2.slot = "s2";
        m2.status = SnapshotLayout.STATUS_COMPLETE;
        m2.parentSlot = "s1";
        index.put(m2, "s2");
        index.save(snapshots.resolve(SnapshotLayout.INDEX_FILE_NAME));
        SnapshotIndex loaded = SnapshotIndex.load(snapshots.resolve(SnapshotLayout.INDEX_FILE_NAME));
        ZPackChain chain = new ZPackChain(snapshots);
        chain.resolveAll("s2", loaded); // 预热
        // 换一个新链（不共享缓存），测冷解析
        chain = new ZPackChain(snapshots);
        long start = System.nanoTime();
        java.util.Map<String, byte[]> resolved = chain.resolveAll("s2", loaded);
        long elapsedMs = (System.nanoTime() - start) / 1000000L;
        assertTrue(resolved.size() == targets.size(), "应全量解出 141 项");
        for (int k = 0; k < targets.size(); k++) {
            assertArrayEquals(targets.get(k), resolved.get(ZPack.keyOf(ZPack.TYPE_CHUNK, "dim", k)),
                    "项 " + k + " 不一致");
        }
        System.out.println("cover-resolve: entries=" + resolved.size() + " totalMs=" + elapsedMs);
        // NATIVE_TEST.md TEST-BENCH-03 门限（populateCover 全链路 ≤50ms）：此处仅 zpack 层，
        // 应远低于门限；生产环境另有 NBT 反序列化开销，但量级一致。
        assertTrue(elapsedMs <= 50, "141 项灌装解析 " + elapsedMs + "ms 超出 50ms 预算");
    }

    @Test
    @DisplayName("§六.4 无头代理：141 真实 chunk 装配多 region 并重解析一致")
    void regionPackReparseRoundTrip() throws Exception {
        Path dir = slicesDir();
        Assumptions.assumeTrue(Files.isDirectory(dir), "Level-B 切片缺失，跳过装配回读");
        List<Path> bases = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "t4_pair_*_base.nbt")) {
            for (Path p : stream) {
                bases.add(p);
            }
        }
        Assumptions.assumeTrue(!bases.isEmpty(), "Level-B 切片为空，跳过装配回读");
        Collections.sort(bases);
        // 按块分组装配：前半正坐标、后半负坐标，覆盖正负 region 文件名与多扇区分配。
        java.util.Map<String, java.util.Map<Long, cc.sighs.rewind.snapshot.AnvilPacker.Chunk>> regions =
                new java.util.LinkedHashMap<>();
        java.util.Map<String, byte[]> expect = new java.util.LinkedHashMap<>();
        int i = 0;
        for (Path baseFile : bases) {
            String name = baseFile.getFileName().toString();
            byte[] raw = Files.readAllBytes(dir.resolve(
                    name.substring(0, name.length() - "_base.nbt".length()) + "_target.nbt"));
            if (raw.length == 0) {
                continue; // 删除标记不进 region
            }
            int x = (i % 32) - (i < bases.size() / 2 ? 0 : 64);
            int z = (i / 32) - 1;
            String region = "r." + (x >> 5) + "." + (z >> 5) + ".mca";
            java.util.Map<Long, cc.sighs.rewind.snapshot.AnvilPacker.Chunk> chunks = regions.get(region);
            if (chunks == null) {
                chunks = new java.util.LinkedHashMap<>();
                regions.put(region, chunks);
            }
            long key = (((long) x) << 32) | (z & 0xFFFFFFFFL);
            chunks.put(key, new cc.sighs.rewind.snapshot.AnvilPacker.Chunk(x, z, raw));
            expect.put(region + "#" + x + "#" + z, raw);
            i++;
        }
        Assumptions.assumeTrue(!expect.isEmpty(), "无有效 chunk，跳过装配回读");
        int files = 0;
        int chunks = 0;
        for (Map.Entry<String, java.util.Map<Long, cc.sighs.rewind.snapshot.AnvilPacker.Chunk>> e
                : regions.entrySet()) {
            cc.sighs.rewind.snapshot.AnvilPacker.Packed packed =
                    cc.sighs.rewind.snapshot.AnvilPacker.pack(e.getValue(), 1700000000);
            assertTrue(packed.mcc.isEmpty(), "T4 对无超 1MiB 块，不应外溢: " + e.getKey());
            byte[] mca = packed.mca;
            // location 表结构自检：已分配扇区两两不交叠、起点 ≥2
            List<int[]> spans = new ArrayList<>();
            for (int s = 0; s < 1024; s++) {
                int loc = ((mca[s * 4] & 0xFF) << 24) | ((mca[s * 4 + 1] & 0xFF) << 16)
                        | ((mca[s * 4 + 2] & 0xFF) << 8) | (mca[s * 4 + 3] & 0xFF);
                if (loc == 0) {
                    continue;
                }
                spans.add(new int[]{(loc >> 8) & 0xFFFFFF, loc & 0xFF});
            }
            assertTrue(spans.size() == e.getValue().size(), "location 条目数应等于 chunk 数: " + e.getKey());
            Collections.sort(spans, (a, b) -> Integer.compare(a[0], b[0]));
            for (int[] span : spans) {
                assertTrue(span[0] >= 2 && span[1] >= 1, "非法扇区分配: " + e.getKey());
            }
            for (int s = 1; s < spans.size(); s++) {
                assertTrue(spans.get(s)[0] >= spans.get(s - 1)[0] + spans.get(s - 1)[1],
                        "扇区交叠: " + e.getKey());
            }
            // 逐 chunk 重解析比对
            for (Map.Entry<Long, cc.sighs.rewind.snapshot.AnvilPacker.Chunk> c
                    : e.getValue().entrySet()) {
                int x = (int) (c.getKey() >> 32);
                int z = (int) (long) c.getKey();
                cc.sighs.rewind.snapshot.SnapshotChunkDecoder.Sector sector =
                        cc.sighs.rewind.snapshot.SnapshotChunkDecoder.readSector(
                                cc.sighs.rewind.snapshot.SnapshotChunkDecoder.SectorSource.ofBytes(mca), x, z);
                assertTrue(sector != null && !sector.external, "chunk 应可读: " + x + "," + z);
                assertArrayEquals(c.getValue().uncompressedNbt,
                        cc.sighs.rewind.snapshot.SnapshotChunkDecoder.decompress(
                                sector.versionId, sector.compressed),
                        "chunk 不一致: " + e.getKey() + " " + x + "," + z);
                chunks++;
            }
            files++;
        }
        System.out.println("region-reparse: files=" + files + " chunks=" + chunks);
        assertTrue(chunks == expect.size(), "应全量回读");
    }
}
