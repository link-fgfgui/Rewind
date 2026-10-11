package cc.sighs.rewind.snapshot;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link SnapshotChunkDecoder}：5 字节头截断（j1 = i1 - 1）、版本分派、.mcc 外溢 stub。
 */
class SnapshotChunkDecoderTest {

    @TempDir
    Path tempRoot;

    private static byte[] compressDeflate(byte[] raw) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (DeflaterOutputStream deflater = new DeflaterOutputStream(out)) {
            deflater.write(raw);
        }
        return out.toByteArray();
    }

    private static byte[] compressGzip(byte[] raw) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(out)) {
            gzip.write(raw);
        }
        return out.toByteArray();
    }

    @Test
    void truncatesToJ1AndDispatchesDeflate() throws IOException {
        byte[] raw = "hello-chunk-payload".getBytes("UTF-8");
        byte[] compressed = compressDeflate(raw);
        Map<Integer, AnvilDeltaAssembler.NewChunk> changes = new HashMap<>();
        changes.put(SnapshotChunkDecoder.chunkIndex(3, 5),
                AnvilDeltaAssembler.NewChunk.update(SnapshotChunkDecoder.VERSION_DEFLATE, compressed));
        byte[] file = AnvilDeltaAssembler.assemble(null, changes, 1234);

        SnapshotChunkDecoder.Sector sector = SnapshotChunkDecoder.readSectorFromBytes(file, 3, 5);
        assertNotNull(sector, "刚写入的区块必须读得回来");
        assertEquals(SnapshotChunkDecoder.VERSION_DEFLATE, sector.versionId);
        // 必须截断到 j1：扇区尾部是补零，全量喂入会解出垃圾
        assertEquals(compressed.length, sector.compressed.length);
        assertArrayEquals(raw, SnapshotChunkDecoder.decompress(sector.versionId, sector.compressed));
    }

    @Test
    void dispatchesGzipAndNone() throws IOException {
        byte[] raw = "gzip-payload-123".getBytes("UTF-8");
        Map<Integer, AnvilDeltaAssembler.NewChunk> changes = new HashMap<>();
        changes.put(0, AnvilDeltaAssembler.NewChunk.update(
                SnapshotChunkDecoder.VERSION_GZIP, compressGzip(raw)));
        changes.put(1, AnvilDeltaAssembler.NewChunk.update(SnapshotChunkDecoder.VERSION_NONE, raw));
        byte[] file = AnvilDeltaAssembler.assemble(null, changes, 42);

        SnapshotChunkDecoder.Sector gzip = SnapshotChunkDecoder.readSectorFromBytes(file, 0, 0);
        assertNotNull(gzip);
        assertArrayEquals(raw, SnapshotChunkDecoder.decompress(gzip.versionId, gzip.compressed));

        SnapshotChunkDecoder.Sector none = SnapshotChunkDecoder.readSectorFromBytes(file, 1, 0);
        assertNotNull(none);
        assertEquals(SnapshotChunkDecoder.VERSION_NONE, none.versionId);
        assertArrayEquals(raw, SnapshotChunkDecoder.decompress(none.versionId, none.compressed));
    }

    @Test
    void missingChunkReadsNull() throws IOException {
        byte[] file = AnvilDeltaAssembler.assemble(null, null, 0);
        assertNull(SnapshotChunkDecoder.readSectorFromBytes(file, 7, 9), "空文件读任何区块都是缺席");
    }

    @Test
    void externalStubResolvesMccEntry() throws IOException {
        Path blocks = tempRoot.resolve("blocks");
        byte[] payload = "external-payload".getBytes("UTF-8");
        byte[] compressed = compressDeflate(payload);
        Map<Integer, AnvilDeltaAssembler.NewChunk> changes = new HashMap<>();
        changes.put(SnapshotChunkDecoder.chunkIndex(1, 2),
                AnvilDeltaAssembler.NewChunk.external(SnapshotChunkDecoder.VERSION_DEFLATE));
        byte[] file = AnvilDeltaAssembler.assemble(null, changes, 99);

        SnapshotChunkDecoder.Sector stub = SnapshotChunkDecoder.readSectorFromBytes(file, 1, 2);
        assertNotNull(stub);
        assertTrue(stub.external, "外溢 chunk 在 .mca 里必须是 stub");
        assertNull(stub.compressed);

        // stub + 独立 .mcc 条目走通块存储
        String mcaRelative = "region/r.0.0.mca";
        String mccRelative = SnapshotChunkDecoder.mccRelative(mcaRelative, 1, 2);
        assertEquals("region/c.1.2.mcc", mccRelative);
        SnapshotBlocks blocksMap = SnapshotBlocks.empty();
        try (SnapshotBlockStore store = SnapshotBlockStore.open(blocks)) {
            SnapshotBlockStore.Stored mca = store.encodeBytes(file);
            SnapshotBlockStore.Stored mcc = store.encodeBytes(compressed);
            blocksMap.put(mcaRelative, file.length, mca.hashes);
            blocksMap.put(mccRelative, compressed.length, mcc.hashes);

            SnapshotChunkDecoder.Sector back = SnapshotChunkDecoder.readSector(store, blocksMap, mcaRelative, 1, 2);
            assertNotNull(back);
            assertTrue(back.external);
            byte[] external = SnapshotChunkDecoder.readExternalPayload(store, blocksMap, mccRelative);
            assertArrayEquals(payload, SnapshotChunkDecoder.decompress(back.versionId, external));
        }
    }

    @Test
    void blockStoreRoundTripThroughDecoder() throws IOException {
        Path blocks = tempRoot.resolve("blocks2");
        byte[] raw = "round-trip-bytes".getBytes("UTF-8");
        byte[] compressed = compressDeflate(raw);
        Map<Integer, AnvilDeltaAssembler.NewChunk> changes = new HashMap<>();
        changes.put(SnapshotChunkDecoder.chunkIndex(0, 0),
                AnvilDeltaAssembler.NewChunk.update(SnapshotChunkDecoder.VERSION_DEFLATE, compressed));
        byte[] file = AnvilDeltaAssembler.assemble(null, changes, 7);
        String mcaRelative = "poi/r.0.0.mca";

        SnapshotBlocks blocksMap = SnapshotBlocks.empty();
        try (SnapshotBlockStore store = SnapshotBlockStore.open(blocks)) {
            SnapshotBlockStore.Stored stored = store.encodeBytes(file);
            blocksMap.put(mcaRelative, file.length, stored.hashes);
            SnapshotChunkDecoder.Sector sector =
                    SnapshotChunkDecoder.readSector(store, blocksMap, mcaRelative, 0, 0);
            assertNotNull(sector);
            assertArrayEquals(raw, SnapshotChunkDecoder.decompress(sector.versionId, sector.compressed));
        }
        assertTrue(Files.isDirectory(blocks), "encodeBytes 也要走 close() 落盘");
    }
}
