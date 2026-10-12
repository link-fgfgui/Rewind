package cc.sighs.rewind.snapshot;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import cc.sighs.rewind.common.spi.RewindPlatform;
import cc.sighs.rewind.common.spi.StorageKind;
import cc.sighs.rewind.common.spi.StorageOverlay;

/**
 * TODO2 P1/P3：zpack 建点桥（写包/读表/删除标记/父链差分）与 NBT 文件谓词。
 *
 * <p>平台桩用恒等编解码（raw 即 tag 字节），只验桥逻辑，不验 NBT。
 */
class ZPackCheckpointTest {

    @TempDir
    Path worldRoot;

    /** 最小平台桩：tag 即 byte[]，恒等编解码。 */
    private static final class IdentityPlatform implements RewindPlatform {
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
        public cc.sighs.rewind.common.spi.FlushOutcome flushForCheckpoint(Object server, String slot, String source) {
            return null;
        }

        @Override
        public boolean supportsInPlaceRollback() {
            return false;
        }

        @Override
        public cc.sighs.rewind.common.spi.RollbackOutcome rollbackInPlace(Object server, Path worldRoot, String slot) {
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
        public String chunkDirRelative(StorageKind kind, String dimensionId) {
            return null;
        }
    }

    private static byte[] nbt(int size, int seed) {
        byte[] b = new byte[size];
        Random r = new Random(seed);
        for (int i = 0; i < size; i++) {
            b[i] = (byte) ("Chunk:NBT:Sections:".charAt(i % 18) + r.nextInt(4));
        }
        return b;
    }

    private void putIndexHere(String slot, String parent) throws Exception {
        Files.createDirectories(SnapshotLayout.snapshotRoot(worldRoot));
        SnapshotIndex index = SnapshotIndex.load(SnapshotLayout.indexFile(worldRoot));
        SnapshotMeta m = new SnapshotMeta();
        m.slot = slot;
        m.status = SnapshotLayout.STATUS_COMPLETE;
        m.savedAtMillis = System.currentTimeMillis();
        m.parentSlot = parent;
        index.put(m, slot);
        index.save(SnapshotLayout.indexFile(worldRoot));
    }

    @Test
    void kindMappingAndFilePredicate() {
        assertEquals(ZPack.TYPE_CHUNK, ZPackCheckpoint.kindToType(StorageKind.CHUNK));
        assertEquals(ZPack.TYPE_ENTITY, ZPackCheckpoint.kindToType(StorageKind.ENTITY));
        assertEquals(ZPack.TYPE_POI, ZPackCheckpoint.kindToType(StorageKind.POI));
        assertEquals(ZPack.TYPE_CHUNK, ZPackCheckpoint.kindToType(null));
        assertTrue(ZPackCheckpoint.isNbtDataFile("level.dat"));
        assertTrue(ZPackCheckpoint.isNbtDataFile("level.dat_old"));
        assertTrue(ZPackCheckpoint.isNbtDataFile("playerdata/abc.dat"));
        assertTrue(ZPackCheckpoint.isNbtDataFile("players/data/abc.dat"));
        assertTrue(ZPackCheckpoint.isNbtDataFile("data/scoreboard.dat"));
        assertTrue(ZPackCheckpoint.isNbtDataFile("DIM-1/data/raids.dat"));
        assertTrue(ZPackCheckpoint.isNbtDataFile("dimensions/minecraft/overworld/data/x.dat"));
        assertFalse(ZPackCheckpoint.isNbtDataFile("advancements/x.json"));
        assertFalse(ZPackCheckpoint.isNbtDataFile("stats/x.json"));
        assertFalse(ZPackCheckpoint.isNbtDataFile("icon.png"));
        assertFalse(ZPackCheckpoint.isNbtDataFile("region/r.0.0.mca"));
        assertFalse(ZPackCheckpoint.isNbtDataFile(null));
    }

    @Test
    void gunzipRoundTrip() throws Exception {
        byte[] raw = nbt(8192, 4);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        GZIPOutputStream out = new GZIPOutputStream(bos);
        out.write(raw);
        out.close();
        Path file = worldRoot.resolve("level.dat");
        Files.write(file, bos.toByteArray());
        assertArrayEquals(raw, ZPackCheckpoint.gunzipFile(file));
        // 非 gzip 返回 null（跳过该文件）
        Path plain = worldRoot.resolve("plain.dat");
        Files.write(plain, raw);
        assertNull(ZPackCheckpoint.gunzipFile(plain));
    }

    @Test
    void writeAndResolveWithDeletionMarker() throws Exception {
        putIndexHere("s1", "");
        byte[] chunk = nbt(32 * 1024, 7);
        StorageOverlay journal = new StorageOverlay();
        journal.onWritten(StorageKind.CHUNK, "minecraft:overworld", 11L, chunk);
        journal.onWritten(StorageKind.ENTITY, "minecraft:overworld", 12L, null);
        ZPackCheckpoint.Written written = ZPackCheckpoint.writeSlotZpack(worldRoot, "s1", "",
                journal.entries(), Collections.<String>emptyList(),
                SnapshotLayout.slotDir(worldRoot, "s1"), new IdentityPlatform());
        assertEquals(2, written.entries);
        assertTrue(written.payloadBytes > 0);
        assertTrue(Files.isRegularFile(SnapshotLayout.zpackFile(worldRoot, "s1")));
        Map<String, byte[]> resolved = ZPackCheckpoint.tryResolve(worldRoot, "s1");
        assertNotNull(resolved);
        assertEquals(2, resolved.size());
        assertArrayEquals(chunk, resolved.get(ZPack.keyOf(ZPack.TYPE_CHUNK, "minecraft:overworld", 11L)));
        // 删除标记即空数组（调用方转回 null 喂覆盖层）
        byte[] tomb = resolved.get(ZPack.keyOf(ZPack.TYPE_ENTITY, "minecraft:overworld", 12L));
        assertNotNull(tomb);
        assertEquals(0, tomb.length);
    }

    @Test
    void childDeltaResolvesAgainstParent() throws Exception {
        putIndexHere("s1", "");
        putIndexHere("s2", "s1");
        byte[] v1 = nbt(48 * 1024, 21);
        byte[] v2 = v1.clone();
        for (int i = 2000; i < 2080; i++) {
            v2[i] ^= 0x44;
        }
        StorageOverlay j1 = new StorageOverlay();
        j1.onWritten(StorageKind.CHUNK, "dim", 5L, v1);
        ZPackCheckpoint.writeSlotZpack(worldRoot, "s1", "", j1.entries(),
                Collections.<String>emptyList(), SnapshotLayout.slotDir(worldRoot, "s1"),
                new IdentityPlatform());
        StorageOverlay j2 = new StorageOverlay();
        j2.onWritten(StorageKind.CHUNK, "dim", 5L, v2);
        ZPackCheckpoint.writeSlotZpack(worldRoot, "s2", "s1", j2.entries(),
                Collections.<String>emptyList(), SnapshotLayout.slotDir(worldRoot, "s2"),
                new IdentityPlatform());
        // 子包应对同名项存 DELTA（小改动不膨胀）
        ZPack.Packed child = ZPack.read(Files.readAllBytes(SnapshotLayout.zpackFile(worldRoot, "s2")));
        assertEquals(1, child.entries.size());
        assertEquals(ZPack.MODE_DELTA, child.entries.get(0).mode);
        Map<String, byte[]> resolved = ZPackCheckpoint.tryResolve(worldRoot, "s2");
        assertArrayEquals(v2, resolved.get(ZPack.keyOf(ZPack.TYPE_CHUNK, "dim", 5L)));
    }

    @Test
    void quickSlotIsAlwaysFullEvenWithParent() throws Exception {
        // TODO-ZSTD §3：F7 的 quick 槽位「只压缩、不 diff」。即使挂了父槽位、内容只有微改，
        // 也必须是 MODE_FULL（建点不物化父链；回滚解算链深度恒为 0）。
        putIndexHere("s1", "");
        putIndexHere("quick", "s1");
        byte[] v1 = nbt(40 * 1024, 91);
        byte[] v2 = v1.clone();
        for (int i = 900; i < 940; i++) {
            v2[i] ^= 0x21;
        }
        StorageOverlay j1 = new StorageOverlay();
        j1.onWritten(StorageKind.CHUNK, "dim", 5L, v1);
        ZPackCheckpoint.writeSlotZpack(worldRoot, "s1", "", j1.entries(),
                Collections.<String>emptyList(), SnapshotLayout.slotDir(worldRoot, "s1"),
                new IdentityPlatform());
        StorageOverlay j2 = new StorageOverlay();
        j2.onWritten(StorageKind.CHUNK, "dim", 5L, v2);
        ZPackCheckpoint.writeSlotZpack(worldRoot, "quick", "s1", j2.entries(),
                Collections.<String>emptyList(), SnapshotLayout.slotDir(worldRoot, "quick"),
                new IdentityPlatform());
        ZPack.Packed quick = ZPack.read(Files.readAllBytes(SnapshotLayout.zpackFile(worldRoot, "quick")));
        assertEquals(1, quick.entries.size());
        assertEquals(ZPack.MODE_FULL, quick.entries.get(0).mode, "quick 必须只压缩、不 diff");
        Map<String, byte[]> resolved = ZPackCheckpoint.tryResolve(worldRoot, "quick");
        assertArrayEquals(v2, resolved.get(ZPack.keyOf(ZPack.TYPE_CHUNK, "dim", 5L)));
    }

    @Test
    void missingPackResolvesNull() throws Exception {
        putIndexHere("s9", "");
        assertNull(ZPackCheckpoint.tryResolve(worldRoot, "s9"));
    }

    @Test
    void dataFilesEnterPack() throws Exception {
        putIndexHere("s1", "");
        byte[] raw = nbt(4096, 77);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        GZIPOutputStream out = new GZIPOutputStream(bos);
        out.write(raw);
        out.close();
        Path slotDir = SnapshotLayout.slotDir(worldRoot, "s1");
        Files.createDirectories(slotDir.resolve("data"));
        Files.write(slotDir.resolve("data/scoreboard.dat"), bos.toByteArray());
        // 非 NBT 松散文件不应进包
        Files.write(slotDir.resolve("icon.png"), new byte[]{1, 2, 3});
        List<String> files = new ArrayList<>();
        files.add("data/scoreboard.dat");
        files.add("icon.png");
        ZPackCheckpoint.Written written = ZPackCheckpoint.writeSlotZpack(worldRoot, "s1", "",
                Collections.<StorageOverlay.Entry>emptyList(), files, slotDir, new IdentityPlatform());
        assertEquals(1, written.entries);
        assertTrue(written.payloadBytes > 0);
        Map<String, byte[]> resolved = ZPackCheckpoint.tryResolve(worldRoot, "s1");
        assertArrayEquals(raw, resolved.get(ZPack.keyOf(ZPack.TYPE_DATA_FILE, "data/scoreboard.dat", 0L)));
    }

    @Test
    void formatBytesUnits() {
        assertEquals("0 B", FullTreeRebakeWorker.formatBytes(0));
        assertEquals("512 B", FullTreeRebakeWorker.formatBytes(512));
        assertEquals("1.5 KiB", FullTreeRebakeWorker.formatBytes(1536));
        assertEquals("2.0 MiB", FullTreeRebakeWorker.formatBytes(2L * 1024 * 1024));
    }
}
