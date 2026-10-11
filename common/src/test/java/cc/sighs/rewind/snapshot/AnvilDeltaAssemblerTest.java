package cc.sighs.rewind.snapshot;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * {@link AnvilDeltaAssembler}：组装、更新、删除、外溢 stub 与重排正确性。
 */
class AnvilDeltaAssemblerTest {

    private static byte[] payload(String text) throws Exception {
        return text.getBytes("UTF-8");
    }

    @Test
    void assemblesNewFileAndReadsBack() throws IOException {
        Map<Integer, AnvilDeltaAssembler.NewChunk> changes = new HashMap<>();
        changes.put(SnapshotChunkDecoder.chunkIndex(0, 0),
                AnvilDeltaAssembler.NewChunk.update(SnapshotChunkDecoder.VERSION_NONE, new byte[]{1, 2, 3}));
        byte[] file = AnvilDeltaAssembler.assemble(null, changes, 100);
        assertEquals(0, file.length % 4096, "文件必须是扇区整数倍");
        assertTrue(file.length >= 12288, "头 8 KiB + 至少 1 扇区");

        SnapshotChunkDecoder.Sector sector = SnapshotChunkDecoder.readSectorFromBytes(file, 0, 0);
        assertNotNull(sector);
        assertArrayEquals(new byte[]{1, 2, 3},
                SnapshotChunkDecoder.decompress(sector.versionId, sector.compressed));
        assertNull(SnapshotChunkDecoder.readSectorFromBytes(file, 1, 0), "没写的区块必须缺席");
    }

    @Test
    void updateKeepsUntouchedChunks() throws Exception {
        Map<Integer, AnvilDeltaAssembler.NewChunk> first = new HashMap<>();
        first.put(SnapshotChunkDecoder.chunkIndex(0, 0),
                AnvilDeltaAssembler.NewChunk.update(SnapshotChunkDecoder.VERSION_NONE, payload("a")));
        first.put(SnapshotChunkDecoder.chunkIndex(1, 0),
                AnvilDeltaAssembler.NewChunk.update(SnapshotChunkDecoder.VERSION_NONE, payload("b")));
        byte[] before = AnvilDeltaAssembler.assemble(null, first, 10);

        Map<Integer, AnvilDeltaAssembler.NewChunk> update = new HashMap<>();
        update.put(SnapshotChunkDecoder.chunkIndex(0, 0),
                AnvilDeltaAssembler.NewChunk.update(SnapshotChunkDecoder.VERSION_NONE, payload("a2")));
        byte[] after = AnvilDeltaAssembler.assemble(before, update, 20);

        assertArrayEquals(payload("a2"), SnapshotChunkDecoder.decompress(
                SnapshotChunkDecoder.readSectorFromBytes(after, 0, 0).versionId,
                SnapshotChunkDecoder.readSectorFromBytes(after, 0, 0).compressed));
        assertArrayEquals(payload("b"), SnapshotChunkDecoder.decompress(
                SnapshotChunkDecoder.readSectorFromBytes(after, 1, 0).versionId,
                SnapshotChunkDecoder.readSectorFromBytes(after, 1, 0).compressed));
    }

    @Test
    void deleteDropsTheOffset() throws Exception {
        Map<Integer, AnvilDeltaAssembler.NewChunk> first = new HashMap<>();
        first.put(0, AnvilDeltaAssembler.NewChunk.update(SnapshotChunkDecoder.VERSION_NONE, payload("x")));
        byte[] before = AnvilDeltaAssembler.assemble(null, first, 10);

        Map<Integer, AnvilDeltaAssembler.NewChunk> del = new HashMap<>();
        del.put(0, AnvilDeltaAssembler.NewChunk.delete());
        byte[] after = AnvilDeltaAssembler.assemble(before, del, 20);
        assertNull(SnapshotChunkDecoder.readSectorFromBytes(after, 0, 0), "删除后偏移必须归零");
    }

    @Test
    void externalChunkBecomesSingleSectorStub() throws Exception {
        Map<Integer, AnvilDeltaAssembler.NewChunk> changes = new HashMap<>();
        changes.put(0, AnvilDeltaAssembler.NewChunk.external(SnapshotChunkDecoder.VERSION_DEFLATE));
        byte[] file = AnvilDeltaAssembler.assemble(null, changes, 30);
        assertEquals(12288, file.length, "stub 只占 1 扇区：头 8 KiB + 4 KiB");
        SnapshotChunkDecoder.Sector sector = SnapshotChunkDecoder.readSectorFromBytes(file, 0, 0);
        assertNotNull(sector);
        assertTrue(sector.external);
        assertEquals(SnapshotChunkDecoder.VERSION_DEFLATE, sector.versionId);
    }
}
