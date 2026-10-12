package cc.sighs.rewind.snapshot;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;

/** TODO2 P1：ZPack 二进制协议打包/解包、膨胀兜底、关键帧、链式重放。 */
class ZPackTest {

    private static byte[] nbt(int size, int seed) {
        byte[] b = new byte[size];
        Random r = new Random(seed);
        for (int i = 0; i < size; i++) {
            b[i] = (byte) ("Chunk:NBT:Sections:Blocks:".charAt(i % 25) + r.nextInt(4));
        }
        return b;
    }

    @Test
    void packRoundTripPreservesEntries() throws Exception {
        byte[] chunk = nbt(60 * 1024, 3);
        byte[] player = nbt(4 * 1024, 9);
        ZPack.Writer writer = new ZPack.Writer();
        writer.add(ZPack.buildEntry(ZPack.TYPE_CHUNK, "minecraft:overworld", 12345L,
                chunk, null, true, ZstdDelta.DEFAULT_LEVEL));
        writer.add(ZPack.buildEntry(ZPack.TYPE_DATA_FILE, "playerdata/abc.dat", 0L,
                player, null, true, ZstdDelta.DEFAULT_LEVEL));
        byte[] file = writer.toBytes();
        // 魔数 RWZP
        assertEquals(0x52, file[0] & 0xFF);
        assertEquals(0x57, file[1] & 0xFF);
        assertEquals(0x5A, file[2] & 0xFF);
        assertEquals(0x50, file[3] & 0xFF);
        ZPack.Packed packed = ZPack.read(file);
        assertEquals(2, packed.entries.size());
        ZPack.Entry e0 = packed.entries.get(0);
        assertEquals(ZPack.TYPE_CHUNK, e0.targetType);
        assertEquals("minecraft:overworld", e0.id);
        assertEquals(12345L, e0.chunkPos);
        assertEquals(ZPack.MODE_FULL, e0.mode);
        assertArrayEquals(chunk, ZPack.resolveEntry(e0, null));
        ZPack.Entry e1 = packed.entries.get(1);
        assertEquals(ZPack.TYPE_DATA_FILE, e1.targetType);
        assertArrayEquals(player, ZPack.resolveEntry(e1, null));
    }

    @Test
    void deltaEntryResolvesAgainstBase() throws Exception {
        byte[] base = nbt(80 * 1024, 11);
        byte[] target = base.clone();
        for (int i = 1000; i < 1064; i++) {
            target[i] ^= 0x77;
        }
        ZPack.Entry delta = ZPack.buildEntry(ZPack.TYPE_CHUNK, "minecraft:overworld", 99L,
                target, base, false, ZstdDelta.DEFAULT_LEVEL);
        // 小改动不应膨胀为 FULL
        assertEquals(ZPack.MODE_DELTA, delta.mode);
        assertArrayEquals(target, ZPack.resolveEntry(delta, base));
    }

    @Test
    void bloatFallsBackToFull() throws Exception {
        Random r = new Random(5);
        byte[] base = new byte[4096];
        r.nextBytes(base);
        byte[] target = new byte[4096];
        r.nextBytes(target);
        // 完全随机两块：差分必然膨胀，应回退 FULL
        ZPack.Entry entry = ZPack.buildEntry(ZPack.TYPE_DATA_FILE, "data/x.dat", 0L,
                target, base, false, ZstdDelta.DEFAULT_LEVEL);
        assertEquals(ZPack.MODE_FULL, entry.mode);
        assertArrayEquals(target, ZPack.resolveEntry(entry, null));
    }

    @Test
    void checksumMismatchIsDetected() throws Exception {
        byte[] raw = nbt(2048, 8);
        ZPack.Entry good = ZPack.buildEntry(ZPack.TYPE_DATA_FILE, "level.dat", 0L,
                raw, null, true, ZstdDelta.DEFAULT_LEVEL);
        ZPack.Entry bad = new ZPack.Entry(good.targetType, good.id, good.chunkPos,
                good.mode, good.rawLength, good.xxh64 ^ 1L, good.payload);
        try {
            ZPack.resolveEntry(bad, null);
            throw new AssertionError("expected checksum failure");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("xxh64"));
        }
    }

    @Test
    void keyframeThreshold() {
        assertTrue(ZPackChain.needsKeyframe(ZPack.KEYFRAME_CHAIN_LIMIT));
        assertTrue(ZPackChain.needsKeyframe(ZPack.KEYFRAME_CHAIN_LIMIT + 5));
        assertTrue(!ZPackChain.needsKeyframe(ZPack.KEYFRAME_CHAIN_LIMIT - 1));
        assertTrue(!ZPackChain.needsKeyframe(0));
    }

    @Test
    void emptyPackRoundTrips() throws Exception {
        ZPack.Writer writer = new ZPack.Writer();
        byte[] file = writer.toBytes();
        ZPack.Packed packed = ZPack.read(file);
        assertEquals(0, packed.entries.size());
        assertEquals(0, packed.index().size());
    }

    @Test
    void entryKeysAreUnique() throws Exception {
        byte[] raw = nbt(1024, 1);
        ZPack.Writer writer = new ZPack.Writer();
        writer.add(ZPack.buildEntry(ZPack.TYPE_CHUNK, "dim", 1L, raw, null, true, 3));
        writer.add(ZPack.buildEntry(ZPack.TYPE_ENTITY, "dim", 1L, raw, null, true, 3));
        writer.add(ZPack.buildEntry(ZPack.TYPE_DATA_FILE, "dim", 0L, raw, null, true, 3));
        Map<String, ZPack.Entry> index = ZPack.read(writer.toBytes()).index();
        assertEquals(3, index.size());
        List<ZPack.Entry> entries = ZPack.read(writer.toBytes()).entries;
        assertEquals(3, entries.size());
    }

    @Test
    void emptyRawDeltaAgainstBaseResolvesEmpty() throws Exception {
        // 删除标记 DELTA（父有基底、本槽位空）：无论编成 DELTA 还是 FULL，回放都得空数组。
        byte[] base = nbt(4096, 3);
        ZPack.Entry entry = ZPack.buildEntry(ZPack.TYPE_CHUNK, "dim", 9L,
                new byte[0], base, false, ZstdDelta.DEFAULT_LEVEL);
        byte[] resolved = ZPack.resolveEntry(entry, base.length == 0 ? null : base);
        assertNotNull(resolved);
        assertEquals(0, resolved.length);
        // 往返包一圈同样成立
        ZPack.Writer writer = new ZPack.Writer();
        writer.add(entry);
        ZPack.Entry back = ZPack.read(writer.toBytes()).entries.get(0);
        assertEquals(0, ZPack.resolveEntry(back, base).length);
    }
}
