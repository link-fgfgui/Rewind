package cc.sighs.rewind.snapshot;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;

/** TODO2 P2：离线 AnvilPacker 纯 Java 装配 .mca/.mcc。 */
class AnvilPackerTest {

    private static byte[] raw(int size, int seed) {
        byte[] b = new byte[size];
        new Random(seed).nextBytes(b);
        return b;
    }

    @Test
    void packsAndReadsBackSingleChunk() throws Exception {
        byte[] nbt = raw(9000, 3);
        Map<Long, AnvilPacker.Chunk> chunks = new HashMap<>();
        chunks.put(0L, new AnvilPacker.Chunk(0, 0, nbt));
        AnvilPacker.Packed packed = AnvilPacker.pack(chunks, 12345);
        assertTrue(packed.mca.length >= 3 * 4096);
        assertTrue(packed.mcc.isEmpty());
        SnapshotChunkDecoder.Sector sector = SnapshotChunkDecoder.readSector(
                SnapshotChunkDecoder.SectorSource.ofBytes(packed.mca), 0, 0);
        assertTrue(sector != null && !sector.external);
        assertEquals(AnvilFormat.VERSION_DEFLATE, sector.versionId);
        assertArrayEquals(nbt, SnapshotChunkDecoder.decompress(sector.versionId, sector.compressed));
    }

    @Test
    void missingChunkReadsNull() throws Exception {
        Map<Long, AnvilPacker.Chunk> chunks = new HashMap<>();
        chunks.put(0L, new AnvilPacker.Chunk(0, 0, raw(100, 1)));
        AnvilPacker.Packed packed = AnvilPacker.pack(chunks, 7);
        assertTrue(SnapshotChunkDecoder.readSector(
                SnapshotChunkDecoder.SectorSource.ofBytes(packed.mca), 5, 5) == null);
    }

    @Test
    void hugeChunkOverflowsToMcc() throws Exception {
        // 不可压缩随机体 1.2MiB：压缩后仍 ~1.2MiB ≥ 256 扇区 → 外溢
        byte[] nbt = raw(1200 * 1024, 99);
        Map<Long, AnvilPacker.Chunk> chunks = new HashMap<>();
        chunks.put(0L, new AnvilPacker.Chunk(3, 7, nbt));
        AnvilPacker.Packed packed = AnvilPacker.pack(chunks, 9);
        assertEquals(1, packed.mcc.size());
        assertTrue(packed.mcc.containsKey("c.3.7.mcc"));
        SnapshotChunkDecoder.Sector stub = SnapshotChunkDecoder.readSector(
                SnapshotChunkDecoder.SectorSource.ofBytes(packed.mca), 3, 7);
        assertTrue(stub != null && stub.external);
        // 外溢载荷是 DEFLATE 压缩字节：解开必须与原始 NBT 一致
        assertArrayEquals(nbt, SnapshotChunkDecoder.decompress(
                AnvilFormat.VERSION_DEFLATE, packed.mcc.get("c.3.7.mcc")));
    }

    @Test
    void emptyPackIsJustHeaders() throws Exception {
        AnvilPacker.Packed packed = AnvilPacker.pack(new HashMap<Long, AnvilPacker.Chunk>(), 42);
        assertEquals(8192, packed.mca.length);
        assertTrue(packed.mcc.isEmpty());
    }
}
