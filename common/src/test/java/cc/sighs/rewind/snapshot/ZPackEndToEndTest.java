package cc.sighs.rewind.snapshot;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Map;
import java.util.Random;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import cc.sighs.rewind.common.spi.RewindPlatform;
import cc.sighs.rewind.common.spi.StorageKind;
import cc.sighs.rewind.common.spi.StorageOverlay;
import cc.sighs.rewind.common.store.SnapshotStore;

/**
 * TODO2 全链路端到端（无头）：建点写包 → 软删除 → 整树重烘焙 → 离线纯 NBT 重建。
 *
 * <p>各段此前只单独测过；本用例证明它们在同一个世界目录里组装得起来——
 * 尤其是 GC 之后（槽位目录/清单/映射被搬运、墓碑被丢弃）离线重建仍能走纯 NBT 通路、
 * 且内容与快照一致（round 9 修的“旧路断粮”若复发，本用例必红）。
 */
class ZPackEndToEndTest {

    @TempDir
    Path worldRoot;

    private static final String DIM = "minecraft:overworld";

    private static final ZPackOffline.Layout LAYOUT = new ZPackOffline.Layout() {
        @Override
        public String dirRelative(int zpackType, String dimensionId) {
            return DIM.equals(dimensionId) && zpackType == ZPack.TYPE_CHUNK ? "region" : null;
        }

        @Override
        public int[] unpack(long packed) {
            return new int[]{(int) packed, (int) (packed >> 32)};
        }
    };

    private static final RewindPlatform PLATFORM = new RewindPlatform() {
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
    };

    private static long pos(int x, int z) {
        return (((long) z) << 32) | (x & 0xFFFFFFFFL);
    }

    private static byte[] nbt(int size, int seed) {
        byte[] b = new byte[size];
        Random r = new Random(seed);
        for (int i = 0; i < size; i++) {
            b[i] = (byte) ("E2E:NBT:Sections:".charAt(i % 16) + r.nextInt(4));
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

    private static byte[] gunzip(byte[] data) throws Exception {
        java.util.zip.GZIPInputStream in =
                new java.util.zip.GZIPInputStream(new java.io.ByteArrayInputStream(data));
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int read;
        while ((read = in.read(buf)) >= 0) {
            bos.write(buf, 0, read);
        }
        in.close();
        return bos.toByteArray();
    }

    private static SnapshotMeta meta(String slot, String parent) {
        SnapshotMeta m = new SnapshotMeta();
        m.slot = slot;
        m.status = SnapshotLayout.STATUS_COMPLETE;
        m.savedAtMillis = 1700000000000L;
        m.parentSlot = parent;
        return m;
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

    @Test
    void checkpointTombstoneRebakeOfflineRebuild() throws Exception {
        byte[] v1 = nbt(24 * 1024, 501);
        byte[] v2 = v1.clone();
        for (int i = 3000; i < 3080; i++) {
            v2[i] ^= 0x2D;
        }
        byte[] score = nbt(2048, 502);
        // s1 建点：chunk FULL + 槽位 .dat
        putIndex(meta("s1", ""));
        StorageOverlay j1 = new StorageOverlay();
        j1.onWritten(StorageKind.CHUNK, DIM, pos(3, 7), v1);
        Path slotDir1 = SnapshotLayout.slotDir(worldRoot, "s1");
        Files.createDirectories(slotDir1.resolve("data"));
        Files.write(slotDir1.resolve("data/scoreboard.dat"), gzip(score));
        SnapshotManifest manifest1 = SnapshotManifest.empty();
        manifest1.put("region/r.0.0.mca", 8192L, 1700000000000L);
        manifest1.put("data/scoreboard.dat", score.length, 1700000000000L);
        manifest1.save(SnapshotLayout.manifestFile(worldRoot, "s1"));
        ZPackCheckpoint.Written s1written = ZPackCheckpoint.writeSlotZpack(worldRoot, "s1", "",
                j1.entries(), java.util.Arrays.asList("data/scoreboard.dat"), slotDir1, PLATFORM);
        assertEquals(2, s1written.entries);
        // s2 建点：chunk 相对 s1 应为 DELTA
        putIndex(meta("s1", ""), meta("s2", "s1"));
        StorageOverlay j2 = new StorageOverlay();
        j2.onWritten(StorageKind.CHUNK, DIM, pos(3, 7), v2);
        Path slotDir2 = SnapshotLayout.slotDir(worldRoot, "s2");
        Files.createDirectories(slotDir2.resolve("data"));
        Files.write(slotDir2.resolve("data/scoreboard.dat"), gzip(score));
        SnapshotManifest manifest2 = SnapshotManifest.empty();
        manifest2.put("region/r.0.0.mca", 8192L, 1700000000001L);
        manifest2.put("data/scoreboard.dat", score.length, 1700000000001L);
        manifest2.save(SnapshotLayout.manifestFile(worldRoot, "s2"));
        ZPackCheckpoint.writeSlotZpack(worldRoot, "s2", "s1",
                j2.entries(), java.util.Arrays.asList("data/scoreboard.dat"), slotDir2, PLATFORM);
        ZPack.Packed s2pack = ZPack.read(Files.readAllBytes(SnapshotLayout.zpackFile(worldRoot, "s2")));
        assertEquals(2, s2pack.entries.size());
        for (ZPack.Entry e : s2pack.entries) {
            if (e.targetType == ZPack.TYPE_CHUNK) {
                assertEquals(ZPack.MODE_DELTA, e.mode, "小改动相对父槽位应为 DELTA");
            }
        }
        // s1 软删除 → GC
        assertTrue(SnapshotStore.delete(worldRoot, "s1"));
        FullTreeRebakeWorker.Result rebaked = FullTreeRebakeWorker.rebake(worldRoot);
        assertEquals(1, rebaked.liveSlots);
        assertEquals(1, rebaked.droppedTombstones);
        SnapshotIndex after = SnapshotIndex.load(SnapshotLayout.indexFile(worldRoot));
        assertTrue(after.get("s1") == null);
        assertEquals("", after.get("s2").parentSlot);
        assertFalse(Files.exists(SnapshotLayout.slotDir(worldRoot, "s1")));
        // 离线重建 s2：必须走纯 NBT 通路（此时 s2 有包，链完备）
        ZPackOffline.Outcome outcome = ZPackOffline.rebuild(worldRoot, "s2", LAYOUT);
        assertTrue(outcome.applied(), "GC 后应走纯 NBT 重建，而非回退: " + outcome.reason);
        Path mca = worldRoot.resolve("region/r.0.0.mca");
        SnapshotChunkDecoder.Sector sector = SnapshotChunkDecoder.readSector(
                SnapshotChunkDecoder.SectorSource.ofBytes(Files.readAllBytes(mca)), 3, 7);
        assertNotNull(sector);
        assertArrayEquals(v2, SnapshotChunkDecoder.decompress(sector.versionId, sector.compressed));
        assertArrayEquals(score, gunzip(Files.readAllBytes(worldRoot.resolve("data/scoreboard.dat"))));
        // 且旧路负载仍在（s2 槽位 .dat 与清单被搬运过来）
        assertTrue(Files.isRegularFile(SnapshotLayout.slotDir(worldRoot, "s2").resolve("data/scoreboard.dat")));
        assertTrue(SnapshotManifest.load(SnapshotLayout.manifestFile(worldRoot, "s2"))
                .get("data/scoreboard.dat") != null);
    }
}
