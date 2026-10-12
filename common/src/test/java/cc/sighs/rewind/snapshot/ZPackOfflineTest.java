package cc.sighs.rewind.snapshot;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import cc.sighs.rewind.common.core.CheckpointWriter;
import cc.sighs.rewind.common.spi.RewindPlatform;
import cc.sighs.rewind.common.spi.RewindPlatforms;

/**
 * TODO2 P2/P4：离线纯 NBT 重建（zpack 链 → Anvil 装配 → /saves）与旧路回退。
 */
class ZPackOfflineTest {

    @TempDir
    Path worldRoot;

    private static final String DIM = "minecraft:overworld";

    /** 布局桩：主世界三件套标准目录 + toLong 坐标布局。 */
    private static final ZPackOffline.Layout LAYOUT = new ZPackOffline.Layout() {
        @Override
        public String dirRelative(int zpackType, String dimensionId) {
            if (!DIM.equals(dimensionId)) {
                return null;
            }
            if (zpackType == ZPack.TYPE_ENTITY) {
                return "entities";
            }
            if (zpackType == ZPack.TYPE_POI) {
                return "poi";
            }
            if (zpackType == ZPack.TYPE_CHUNK) {
                return "region";
            }
            return null;
        }

        @Override
        public int[] unpack(long packed) {
            return new int[]{(int) packed, (int) (packed >> 32)};
        }
    };

    private static long pos(int x, int z) {
        return (((long) z) << 32) | (x & 0xFFFFFFFFL);
    }

    private static byte[] nbt(int size, int seed) {
        byte[] b = new byte[size];
        Random r = new Random(seed);
        for (int i = 0; i < size; i++) {
            b[i] = (byte) ("Chunk:NBT:Sections:".charAt(i % 18) + r.nextInt(4));
        }
        return b;
    }

    private static byte[] gzip(byte[] raw) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        GZIPOutputStream out = new GZIPOutputStream(bos);
        out.write(raw);
        out.close();
        return bos.toByteArray();
    }

    private static byte[] gunzip(byte[] compressed) throws Exception {
        java.util.zip.GZIPInputStream in =
                new java.util.zip.GZIPInputStream(new java.io.ByteArrayInputStream(compressed));
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int read;
        while ((read = in.read(buf)) >= 0) {
            bos.write(buf, 0, read);
        }
        in.close();
        return bos.toByteArray();
    }

    private void writeIndex(String slot, long savedAt) throws Exception {
        Files.createDirectories(SnapshotLayout.snapshotRoot(worldRoot));
        SnapshotIndex index = SnapshotIndex.load(SnapshotLayout.indexFile(worldRoot));
        SnapshotMeta m = new SnapshotMeta();
        m.slot = slot;
        m.status = SnapshotLayout.STATUS_COMPLETE;
        m.savedAtMillis = savedAt;
        m.parentSlot = "";
        index.put(m, slot);
        index.save(SnapshotLayout.indexFile(worldRoot));
    }

    private void writeManifest(String... paths) throws Exception {
        SnapshotManifest manifest = SnapshotManifest.empty();
        long now = 1700000000000L;
        for (String path : paths) {
            manifest.put(path, path.length() * 10L + 100, now);
        }
        manifest.save(SnapshotLayout.manifestFile(worldRoot, "quick"));
        Files.createDirectories(SnapshotLayout.slotDir(worldRoot, "quick"));
    }

    private void writePack(byte[] c00, byte[] c10, byte[] c01, byte[] level, byte[] score) throws Exception {
        ZPack.Writer writer = new ZPack.Writer();
        writer.add(ZPack.buildEntry(ZPack.TYPE_CHUNK, DIM, pos(0, 0), c00, null, true, 3));
        writer.add(ZPack.buildEntry(ZPack.TYPE_CHUNK, DIM, pos(1, 0), c10, null, true, 3));
        writer.add(ZPack.buildEntry(ZPack.TYPE_CHUNK, DIM, pos(0, 1), c01, null, true, 3));
        writer.add(ZPack.buildEntry(ZPack.TYPE_DATA_FILE, "level.dat", 0L, level, null, true, 3));
        writer.add(ZPack.buildEntry(ZPack.TYPE_DATA_FILE, "data/scoreboard.dat", 0L, score, null, true, 3));
        Files.createDirectories(SnapshotLayout.snapshotRoot(worldRoot));
        Files.write(SnapshotLayout.zpackFile(worldRoot, "quick"), writer.toBytes());
    }

    private static byte[] readChunk(Path mca, int x, int z) throws Exception {
        SnapshotChunkDecoder.Sector sector = SnapshotChunkDecoder.readSector(
                SnapshotChunkDecoder.SectorSource.ofBytes(Files.readAllBytes(mca)), x, z);
        assertNotNull(sector, "chunk (" + x + "," + z + ") 应存在");
        assertFalse(sector.external);
        return SnapshotChunkDecoder.decompress(sector.versionId, sector.compressed);
    }

    private static byte[] gunzipFile(Path file) throws Exception {
        byte[] compressed = Files.readAllBytes(file);
        java.util.zip.GZIPInputStream in =
                new java.util.zip.GZIPInputStream(new java.io.ByteArrayInputStream(compressed));
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int read;
        while ((read = in.read(buf)) >= 0) {
            bos.write(buf, 0, read);
        }
        in.close();
        return bos.toByteArray();
    }

    @Test
    void rebuildsMcaDatAndLooseFiles() throws Exception {
        byte[] c00 = nbt(9000, 1);
        byte[] c10 = nbt(5000, 2);
        byte[] c01 = nbt(3000, 3);
        byte[] level = nbt(2048, 4);
        byte[] score = nbt(1024, 5);
        writeIndex("quick", 1700000000000L);
        writePack(c00, c10, c01, level, score);
        writeManifest("region/r.0.0.mca", "region/c.0.1.mcc", "level.dat",
                "data/scoreboard.dat", "datapacks/pack.zip");
        Path slotDir = SnapshotLayout.slotDir(worldRoot, "quick");
        Files.createDirectories(slotDir.resolve("datapacks"));
        Files.write(slotDir.resolve("datapacks/pack.zip"), new byte[]{9, 8, 7, 6});
        // 世界现状：旧 region 内容 + 快照后多出来的文件 + 过期 .mcc
        Files.createDirectories(worldRoot.resolve("region"));
        Files.createDirectories(worldRoot.resolve("data"));
        Files.write(worldRoot.resolve("region/r.0.0.mca"), new byte[8192]);
        Files.write(worldRoot.resolve("data/oldmod.dat"), new byte[]{1});
        Files.write(worldRoot.resolve("region/c.0.1.mcc"), new byte[]{2});

        ZPackOffline.Outcome outcome = ZPackOffline.rebuild(worldRoot, "quick", LAYOUT);
        assertTrue(outcome.applied(), "完备链应走纯 NBT 重建: " + outcome.reason);
        SnapshotMirror.Result result = outcome.result;

        Path mca = worldRoot.resolve("region/r.0.0.mca");
        assertArrayEquals(c00, readChunk(mca, 0, 0));
        assertArrayEquals(c10, readChunk(mca, 1, 0));
        assertArrayEquals(c01, readChunk(mca, 0, 1));
        assertArrayEquals(level, gunzipFile(worldRoot.resolve("level.dat")));
        assertArrayEquals(score, gunzipFile(worldRoot.resolve("data/scoreboard.dat")));
        assertArrayEquals(new byte[]{9, 8, 7, 6},
                Files.readAllBytes(worldRoot.resolve("datapacks/pack.zip")));
        // 快照后多出来的 + 过期 .mcc 都被删掉
        assertFalse(Files.exists(worldRoot.resolve("data/oldmod.dat")));
        assertFalse(Files.exists(worldRoot.resolve("region/c.0.1.mcc")));
        // mtime 拨回清单值
        assertEquals(1700000000000L, Files.getLastModifiedTime(mca).toMillis());
        assertTrue(result.decoded >= 1);
    }

    @Test
    void deletionMarkerOmitsChunk() throws Exception {        byte[] c00 = nbt(6000, 11);
        writeIndex("quick", 1700000000000L);
        ZPack.Writer writer = new ZPack.Writer();
        writer.add(ZPack.buildEntry(ZPack.TYPE_CHUNK, DIM, pos(0, 0), c00, null, true, 3));
        // (1,0) 在快照时已被删除：空 raw 删除标记
        writer.add(ZPack.buildEntry(ZPack.TYPE_CHUNK, DIM, pos(1, 0), new byte[0], null, true, 3));
        Files.createDirectories(SnapshotLayout.snapshotRoot(worldRoot));
        Files.write(SnapshotLayout.zpackFile(worldRoot, "quick"), writer.toBytes());
        writeManifest("region/r.0.0.mca");
        Files.createDirectories(SnapshotLayout.slotDir(worldRoot, "quick"));

        ZPackOffline.Outcome outcome = ZPackOffline.rebuild(worldRoot, "quick", LAYOUT);
        assertTrue(outcome.applied(), outcome.reason);
        SnapshotMirror.Result result = outcome.result;
        Path mca = worldRoot.resolve("region/r.0.0.mca");
        assertArrayEquals(c00, readChunk(mca, 0, 0));
        assertTrue(SnapshotChunkDecoder.readSector(
                        SnapshotChunkDecoder.SectorSource.ofBytes(Files.readAllBytes(mca)), 1, 0) == null,
                "删除标记的区块不应出现在重建 region 里");
    }

    @Test
    void fallsBackWithoutPack() throws Exception {
        writeIndex("quick", 1L);
        ZPackOffline.Outcome outcome = ZPackOffline.rebuild(worldRoot, "quick", LAYOUT);
        assertFalse(outcome.applied());
        assertNotNull(outcome.reason);
    }

    @Test
    void fallsBackOnUnknownDimension() throws Exception {
        writeIndex("quick", 1L);
        ZPack.Writer writer = new ZPack.Writer();
        writer.add(ZPack.buildEntry(ZPack.TYPE_CHUNK, "mod:weird", pos(0, 0),
                nbt(1000, 1), null, true, 3));
        Files.createDirectories(SnapshotLayout.snapshotRoot(worldRoot));
        Files.write(SnapshotLayout.zpackFile(worldRoot, "quick"), writer.toBytes());
        writeManifest("region/r.0.0.mca");
        Files.createDirectories(SnapshotLayout.slotDir(worldRoot, "quick"));
        ZPackOffline.Outcome outcome = ZPackOffline.rebuild(worldRoot, "quick", LAYOUT);
        assertFalse(outcome.applied(), "未知维度布局应走 not-applicable");
        assertTrue(outcome.reason.contains("mod:weird"));
    }

    @Test
    void fallsBackWhenManifestRegionHasNoData() throws Exception {
        writeIndex("quick", 1L);
        ZPack.Writer writer = new ZPack.Writer();
        writer.add(ZPack.buildEntry(ZPack.TYPE_CHUNK, DIM, pos(0, 0), nbt(1000, 1), null, true, 3));
        Files.createDirectories(SnapshotLayout.snapshotRoot(worldRoot));
        Files.write(SnapshotLayout.zpackFile(worldRoot, "quick"), writer.toBytes());
        // 清单说 r.5.5.mca 存在，链里一个 chunk 都没有 → 链洞 → 抛异常（调用方回退并告警）
        writeManifest("region/r.0.0.mca", "region/r.5.5.mca");
        Files.createDirectories(SnapshotLayout.slotDir(worldRoot, "quick"));
        assertThrows(java.io.IOException.class,
                () -> ZPackOffline.rebuild(worldRoot, "quick", LAYOUT));
    }

    @Test
    void restoreFilesUsesPurePathWhenInstalled() throws Exception {
        byte[] c00 = nbt(7000, 31);
        byte[] level = nbt(1500, 32);
        byte[] score = nbt(800, 33);
        writeIndex("quick", 1700000000000L);
        writePack(c00, nbt(4000, 34), nbt(2000, 35), level, score);
        writeManifest("region/r.0.0.mca", "level.dat", "data/scoreboard.dat");
        Files.createDirectories(SnapshotLayout.slotDir(worldRoot, "quick"));
        // 安装布局桩平台（worldRoot 走入参，其余走桩逻辑）
        RewindPlatform stub = new RewindPlatform() {
            @Override
            public String modVersion() {
                return "test";
            }

            @Override
            public Path worldRoot(Object server) {
                return worldRoot;
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
            public cc.sighs.rewind.common.spi.EncodedSector encodeChunkSector(Object tag) {
                return null;
            }

            @Override
            public Object decodeChunkSector(byte[] uncompressed) {
                return null;
            }

            @Override
            public byte[] encodeRawNbt(Object tag) {
                return null;
            }

            @Override
            public String chunkDirRelative(cc.sighs.rewind.common.spi.StorageKind kind, String dimensionId) {
                return LAYOUT.dirRelative(
                        ZPackCheckpoint.kindToType(kind), dimensionId);
            }

            @Override
            public int[] unpackChunkPos(long packed) {
                return LAYOUT.unpack(packed);
            }
        };
        RewindPlatforms.install(stub);
        try {
            CheckpointWriter.Result result = CheckpointWriter.restoreFiles(worldRoot, "quick");
            assertNotNull(result);
            assertArrayEquals(c00, readChunk(worldRoot.resolve("region/r.0.0.mca"), 0, 0));
            assertArrayEquals(level, gunzipFile(worldRoot.resolve("level.dat")));
        } finally {
            RewindPlatforms.install(null);
        }
    }

    @Test
    void unusedGzipHelperSanity() throws Exception {
        assertArrayEquals(nbt(100, 9), gunzip(gzip(nbt(100, 9))));
    }

    @Test
    void negativeCoordsAndStaleMcc() throws Exception {
        // 负坐标 region（r.-1.0.mca）+ 清单残留的旧 .mcc（c.-1.-2.mcc）：
        // 重建把 chunk 打成普通扇区，世界里的过期 .mcc 必须被删掉（parseMcc 负号解析）。
        byte[] raw = nbt(6000, 55);
        writeIndex("quick", 1700000000000L);
        ZPack.Writer writer = new ZPack.Writer();
        writer.add(ZPack.buildEntry(ZPack.TYPE_CHUNK, DIM, pos(-1, -2), raw, null, true, 3));
        Files.createDirectories(SnapshotLayout.snapshotRoot(worldRoot));
        Files.write(SnapshotLayout.zpackFile(worldRoot, "quick"), writer.toBytes());
        // chunk(-1,-2) 落在 region(-1,-1)（算术右移下取整）
        writeManifest("region/r.-1.-1.mca", "region/c.-1.-2.mcc");
        Files.createDirectories(SnapshotLayout.slotDir(worldRoot, "quick"));
        Files.createDirectories(worldRoot.resolve("region"));
        Files.write(worldRoot.resolve("region/c.-1.-2.mcc"), new byte[]{7, 7, 7});

        ZPackOffline.Outcome outcome = ZPackOffline.rebuild(worldRoot, "quick", LAYOUT);
        assertTrue(outcome.applied(), outcome.reason);
        assertArrayEquals(raw, readChunk(worldRoot.resolve("region/r.-1.-1.mca"), -1, -2));
        assertFalse(Files.exists(worldRoot.resolve("region/c.-1.-2.mcc")),
                "过期 .mcc 必须被删掉");
    }
}
