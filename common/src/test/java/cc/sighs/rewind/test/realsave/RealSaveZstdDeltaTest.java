package cc.sighs.rewind.test.realsave;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.zip.GZIPInputStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import cc.sighs.rewind.snapshot.ZPack;
import cc.sighs.rewind.snapshot.ZstdDelta;

/**
 * 真实生产存档黄金切片（Level-A，随仓库提交）的 Zstd 字典差分测试。
 *
 * <p>切片来源：{@code mc-backups.zip}（1.20.1 Forge 重度工业服），
 * 由 {@code tools/extract_golden_slices.py} 提取（见 {@code manifest.json} 的 sha256 清单）。
 * 生产路径（{@link ZstdDelta}，非裸 JNI 调用）断言 NATIVE_TEST.md：
 * TEST-ZSTD-02（微变收敛 ≤100 B）、TEST-ZSTD-03（大单体 ≤5 KiB）、TEST-ZSTD-05（篡改检出）。
 */
class RealSaveZstdDeltaTest {

    private static byte[] slice(String name) throws IOException {
        try (InputStream in = RealSaveZstdDeltaTest.class.getResourceAsStream("/goldenslices/" + name)) {
            assertNotNull(in, "黄金切片缺失（先跑 tools/extract_golden_slices.py）: " + name);
            ByteArrayOutputStream out = new ByteArrayOutputStream(1 << 16);
            byte[] buf = new byte[8192];
            int read;
            while ((read = in.read(buf)) >= 0) {
                out.write(buf, 0, read);
            }
            return out.toByteArray();
        }
    }

    private static byte[] gunzip(byte[] data) throws IOException {
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
    @DisplayName("切片完整性：Level-A 文件与 manifest.json sha256 清单一致")
    void slicesMatchManifest() throws Exception {
        String manifest;
        try (InputStream in = RealSaveZstdDeltaTest.class.getResourceAsStream("/goldenslices/manifest.json")) {
            assertNotNull(in, "manifest.json 缺失");
            ByteArrayOutputStream out = new ByteArrayOutputStream(4096);
            byte[] buf = new byte[1024];
            int read;
            while ((read = in.read(buf)) >= 0) {
                out.write(buf, 0, read);
            }
            manifest = new String(out.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
        }
        java.security.MessageDigest sha = java.security.MessageDigest.getInstance("SHA-256");
        for (String name : new String[]{"t1_base_chunk_14_18.nbt", "t1_target_chunk_14_18.nbt",
                "t1_base_disk_manager.dat", "t1_target_disk_manager.dat"}) {
            byte[] data = slice(name);
            assertTrue(manifest.contains(hex(sha.digest(data))),
                    "切片与清单不一致（被篡改或提取脚本漂移）: " + name);
        }
    }

    private static String hex(byte[] bytes) {
        char[] digits = "0123456789abcdef".toCharArray();
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(digits[(b >> 4) & 0xF]).append(digits[b & 0xF]);
        }
        return sb.toString();
    }

    @Test
    @DisplayName("TEST-ZSTD-02: 35s 微变区块补丁收敛 ≤100B + 100% 还原")
    void microDeltaConverges() throws Exception {
        byte[] base = slice("t1_base_chunk_14_18.nbt");
        byte[] target = slice("t1_target_chunk_14_18.nbt");
        assertTrue(base.length > 0 && base.length == target.length);
        byte[] patch = ZstdDelta.compressDelta(target, base, ZstdDelta.DEFAULT_LEVEL);
        System.out.println("T1 micro: raw=" + base.length + " patch=" + patch.length);
        assertTrue(patch.length <= 100, "微变补丁过大: " + patch.length + " B");
        assertArrayEquals(target, ZstdDelta.decompress(patch, base, target.length));
    }

    @Test
    @DisplayName("TEST-ZSTD-03: AE2 disk_manager.dat 大单体补丁 ≤5KiB + 100% 还原")
    void largeSavedDataDelta() throws Exception {
        byte[] baseRaw = gunzip(slice("t1_base_disk_manager.dat"));
        byte[] targetRaw = gunzip(slice("t1_target_disk_manager.dat"));
        System.out.println("disk_manager raw: " + baseRaw.length + " -> " + targetRaw.length);
        byte[] patch = ZstdDelta.compressDelta(targetRaw, baseRaw, ZstdDelta.DEFAULT_LEVEL);
        System.out.println("disk_manager patch: " + patch.length);
        assertTrue(patch.length <= 5120, "AE2 补丁膨胀: " + patch.length + " B");
        assertArrayEquals(targetRaw, ZstdDelta.decompress(patch, baseRaw, targetRaw.length));
    }

    @Test
    @DisplayName("TEST-ZSTD-05: zpack 载荷篡改一个字节即被检出")
    void tamperedPayloadIsRejected() throws Exception {
        byte[] raw = slice("t1_target_chunk_14_18.nbt");
        ZPack.Entry good = ZPack.buildEntry(ZPack.TYPE_CHUNK, "minecraft:overworld", 1L,
                raw, null, true, ZstdDelta.DEFAULT_LEVEL);
        byte[] badPayload = good.payload.clone();
        badPayload[badPayload.length / 2] ^= 0xFF;
        ZPack.Entry bad = new ZPack.Entry(good.targetType, good.id, good.chunkPos,
                good.mode, good.rawLength, good.xxh64, badPayload);
        boolean rejected = false;
        try {
            ZPack.resolveEntry(bad, null);
        } catch (IOException expected) {
            rejected = true;
        }
        assertTrue(rejected, "篡改载荷必须被拒绝");
    }
}
