package cc.sighs.rewind.snapshot;

import java.io.EOFException;

/**
 * Anvil region 格式常量与大端字节序工具。
 *
 * <p>解码（{@link SnapshotChunkDecoder}）、增量组装（{@link AnvilDeltaAssembler}）与
 * 离线装配（{@link AnvilPacker}）共用同一套常量，避免三处各写一遍后互相漂移。
 *
 * <p>纯 JDK、Java 8 兼容。
 */
public final class AnvilFormat {
    /** 扇区大小：与原版 .mca 一致。 */
    public static final int SECTOR_BYTES = 4096;
    /** 文件头（偏移表 + 时间戳表）。 */
    public static final int HEADER_BYTES = 8192;
    /** 每 region 文件的区块槽位数（32×32）。 */
    public static final int CHUNK_COUNT = 1024;
    /** 外溢标记：版本字节最高位（原版 {@code RegionFileVersion} 的 external 位）。 */
    public static final int EXTERNAL_STREAM_FLAG = 128;
    /** 外溢阈值：压缩后 ≥256 扇区（1 MiB）走 .mcc（原版 RegionFile.EXTERNAL_CHUNK_THRESHOLD）。 */
    public static final int EXTERNAL_CHUNK_THRESHOLD = 256;
    /** 流版本号（与原版 RegionFileVersion 一致）。 */
    public static final int VERSION_GZIP = 1;
    public static final int VERSION_DEFLATE = 2;
    public static final int VERSION_NONE = 3;
    public static final int VERSION_LZ4 = 4;

    private AnvilFormat() {
    }

    /** 大端读 4 字节 int；越界抛 {@link EOFException}。 */
    public static int getInt(byte[] bytes, int base) throws EOFException {
        if (base < 0 || base + 4 > bytes.length) {
            throw new EOFException("truncated int at " + base);
        }
        return ((bytes[base] & 0xFF) << 24)
                | ((bytes[base + 1] & 0xFF) << 16)
                | ((bytes[base + 2] & 0xFF) << 8)
                | (bytes[base + 3] & 0xFF);
    }

    /** 大端写 4 字节 int。 */
    public static void putInt(byte[] bytes, int base, int value) {
        bytes[base] = (byte) (value >> 24);
        bytes[base + 1] = (byte) (value >> 16);
        bytes[base + 2] = (byte) (value >> 8);
        bytes[base + 3] = (byte) value;
    }
}
