package cc.sighs.rewind.test.realsave;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import cc.sighs.rewind.snapshot.SnapshotChunkDecoder;

/**
 * TEST-SEC-01 真实扇区版：生产服 `.mca` 扇区经生产解码器。
 *
 * <p>切片：{@code boundary_deflate_sector.bin}（该区块已分配扇区原字节：5 字节头＋载荷＋尾部空隙）
 * 与 {@code boundary_deflate_expected.nbt}（期望未压缩 NBT），
 * 由 {@code tools/extract_golden_slices.py} 从真实 `r.0.0.mca` 提取。
 * 断言：按 `j1 = i1 - 1` 截断解码 == 期望 NBT；篡改 `i1` 放大后解码器抛 `EOFException`
 * （越界即失败，绝不 bleed 到相邻扇区）。
 *
 * <p>注：扇区尾部空隙未必是 `0x00`（原版只写载荷、不清尾，残留 stale 字节属正常现象；
 * 117 个 region 文件实测即如此）——截断的意义正在于“停在 `j1` 处”，与尾部内容无关。
 */
class RealSaveSectorTest {

    /** 切片对应的区块坐标（提取时 chunk(0,0)，见 manifest.json）。 */
    private static final int CHUNK_X = 0;
    private static final int CHUNK_Z = 0;

    private static byte[] slice(String name) throws IOException {
        try (InputStream in = RealSaveSectorTest.class.getResourceAsStream("/goldenslices/" + name)) {
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

    /** 最小 .mca：8 KiB 头（location 指向 2 号扇区）＋切片扇区原字节。 */
    private static byte[] forgeMca(byte[] sector) {
        int sectors = (sector.length + 4095) / 4096;
        byte[] mca = new byte[8192 + sectors * 4096];
        int idx = (CHUNK_Z & 31) * 32 + (CHUNK_X & 31);
        int loc = (2 << 8) | sectors;
        mca[idx * 4] = (byte) (loc >> 24);
        mca[idx * 4 + 1] = (byte) (loc >> 16);
        mca[idx * 4 + 2] = (byte) (loc >> 8);
        mca[idx * 4 + 3] = (byte) loc;
        System.arraycopy(sector, 0, mca, 8192, sector.length);
        return mca;
    }

    @Test
    @DisplayName("TEST-SEC-01: 真实扇区经生产解码器 == 期望 NBT")
    void realSectorDecodesToExpected() throws Exception {
        byte[] sector = slice("boundary_deflate_sector.bin");
        byte[] expected = slice("boundary_deflate_expected.nbt");
        int version = sector[4] & 0xFF;
        assertTrue(version == 2, "切片应为 DEFLATE 扇区，实际版本=" + version);
        SnapshotChunkDecoder.Sector decoded =
                SnapshotChunkDecoder.readSectorFromBytes(forgeMca(sector), CHUNK_X, CHUNK_Z);
        assertNotNull(decoded);
        assertTrue(!decoded.external);
        assertArrayEquals(expected,
                SnapshotChunkDecoder.decompress(decoded.versionId, decoded.compressed));
    }

    @Test
    @DisplayName("TEST-SEC-01b: 篡改 i1 放大后越界即抛，不 bleed 相邻扇区")
    void corruptedLengthIsRejected() throws Exception {
        byte[] sector = slice("boundary_deflate_sector.bin").clone();
        int i1 = ((sector[0] & 0xFF) << 24) | ((sector[1] & 0xFF) << 16)
                | ((sector[2] & 0xFF) << 8) | (sector[3] & 0xFF);
        int bad = i1 + 5000;
        sector[0] = (byte) (bad >> 24);
        sector[1] = (byte) (bad >> 16);
        sector[2] = (byte) (bad >> 8);
        sector[3] = (byte) bad;
        boolean rejected = false;
        try {
            SnapshotChunkDecoder.readSectorFromBytes(forgeMca(sector), CHUNK_X, CHUNK_Z);
        } catch (IOException expected) {
            rejected = true;
        }
        assertTrue(rejected, "放大后的 i1 越界必须抛，不能 bleed 到相邻扇区");
    }
}
