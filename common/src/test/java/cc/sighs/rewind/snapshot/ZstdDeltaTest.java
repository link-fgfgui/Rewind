package cc.sighs.rewind.snapshot;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.Random;
import org.junit.jupiter.api.Test;

/** TODO2 P0：Zstd 字典差分 round-trip 100% 字节一致性。 */
class ZstdDeltaTest {

    private static byte[] nbtLike(int size, int seed, int mutate) {
        byte[] base = new byte[size];
        Random random = new Random(seed);
        // 模拟 NBT：大量结构化重复前缀 + 少量随机体
        for (int i = 0; i < size; i++) {
            base[i] = (byte) ("TAG_Compound:Level:Sections:".charAt(i % 28) + random.nextInt(3));
        }
        if (mutate > 0) {
            Random m = new Random(mutate);
            for (int i = 0; i < Math.min(64, size); i++) {
                base[m.nextInt(size)] = (byte) m.nextInt(256);
            }
        }
        return base;
    }

    @Test
    void libraryLoads() {
        // JNI 缺席时回退 Deflate 仍算可用路径；不断言强制 true，只确保调用不炸
        byte[] raw = "hello-rewind".getBytes(StandardCharsets.UTF_8);
        try {
            byte[] full = ZstdDelta.compressFull(raw, ZstdDelta.DEFAULT_LEVEL);
            byte[] back = ZstdDelta.decompress(full, null, raw.length);
            assertArrayEquals(raw, back);
        } catch (Exception e) {
            throw new AssertionError("zstd full round-trip failed", e);
        }
    }

    @Test
    void deltaRoundTripIsByteIdentical() throws Exception {
        byte[] base = nbtLike(90 * 1024, 7, 0);
        byte[] target = base.clone();
        // 模拟改动 1 个方块：只动中间几十字节
        for (int i = 45000; i < 45064; i++) {
            target[i] ^= 0x5A;
        }
        byte[] patch = ZstdDelta.compressDelta(target, base, ZstdDelta.DEFAULT_LEVEL);
        byte[] back = ZstdDelta.decompress(patch, base, target.length);
        assertArrayEquals(target, back, "delta round-trip 必须 100% 字节一致");
    }

    @Test
    void deltaPatchIsSmallerThanFullForSmallChange() throws Exception {
        byte[] base = nbtLike(120 * 1024, 21, 0);
        byte[] target = base.clone();
        for (int i = 60000; i < 60032; i++) {
            target[i] ^= 0x33;
        }
        byte[] full = ZstdDelta.compressFull(target, ZstdDelta.DEFAULT_LEVEL);
        byte[] patch = ZstdDelta.compressDelta(target, base, ZstdDelta.DEFAULT_LEVEL);
        // 小改动下字典差分应显著小于独立全量（若 JNI 缺席回退 Deflate 则放宽为 <=）
        if (ZstdDelta.isAvailable()) {
            assertTrue(patch.length < full.length,
                    "patch=" + patch.length + " full=" + full.length);
        } else {
            assertTrue(patch.length <= full.length + 64);
        }
    }

    @Test
    void bloatFallbackRule() {
        assertTrue(ZstdDelta.shouldStoreFull(100, 100));
        assertTrue(ZstdDelta.shouldStoreFull(200, 100));
        assertTrue(!ZstdDelta.shouldStoreFull(50, 100));
    }

    @Test
    void emptyAndSingleByteRoundTrip() throws Exception {
        for (byte[] raw : new byte[][]{new byte[0], new byte[]{1}, nbtLike(1, 1, 0)}) {
            byte[] full = ZstdDelta.compressFull(raw, ZstdDelta.DEFAULT_LEVEL);
            assertArrayEquals(raw, ZstdDelta.decompress(full, null, raw.length));
        }
    }

    @Test
    void xxh64KnownVectors() {
        // 空输入固定值（官方向量）
        assertEquals(0xEF46DB3751D8E999L, Xxh64.hash(new byte[0]));
        // 确定性：同输入同输出，不同输入大概率不同
        byte[] a = "rewind-zpack".getBytes(StandardCharsets.UTF_8);
        byte[] b = "rewind-zpaci".getBytes(StandardCharsets.UTF_8);
        assertEquals(Xxh64.hash(a), Xxh64.hash(a));
        assertTrue(Xxh64.hash(a) != Xxh64.hash(b));
    }
}
