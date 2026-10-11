package cc.sighs.rewind.snapshot;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.GZIPOutputStream;

/**
 * 离线 Anvil 装配器（TODO2 §4.3：纯 NBT 流式装配 Anvil 格式重写器）。
 *
 * <p>Phase 2 弃用 4 KiB 物理扇区快照后，{@code WorldRepair} 不再盲写扇区二进制块，
 * 改为在本类中由目标槽位 zpack 链还原出的全量未压缩 NBT（{@code byte[]}，调用方经
 * {@link ZPackChain} 取得）在内存中完成：压缩（GZIP/DEFLATE/NONE，LZ4 不支持）、
 * 写入 5 字节 Anvil 头、4 KiB 扇区对齐、生成 {@code .mca} 文件（超 1 MiB 自动
 * 输出 {@code .mcc} 外溢 stub + 独立载荷）。
 *
 * <p>common 纯 Java 8、无 Minecraft 依赖：输入输出均为字节，不解析 NBT 结构。
 * 调用方负责把“哪些 (x,z) 有数据、版本号用哪个”告诉本类（默认 DEFLATE=2，
 * 与原版 {@code DedicatedServerProperties regionFileComression} 默认一致）。
 */
public final class AnvilPacker {
    /** 与 SnapshotChunkDecoder 对齐的流版本号。 */
    public static final int VERSION_GZIP = 1;
    public static final int VERSION_DEFLATE = 2;
    public static final int VERSION_NONE = 3;
    /** 外溢阈值：压缩后 ≥256 扇区（1 MiB）走 .mcc（RegionFile.EXTERNAL_CHUNK_THRESHOLD）。 */
    public static final int EXTERNAL_CHUNK_THRESHOLD = 256;
    static final int SECTOR = 4096;

    private AnvilPacker() {
    }

    /** 单个区块的装配输入：未压缩 NBT + 期望流版本。 */
    public static final class Chunk {
        public final int x;
        public final int z;
        public final byte[] uncompressedNbt;
        public final int versionId;

        public Chunk(int x, int z, byte[] uncompressedNbt, int versionId) {
            this.x = x;
            this.z = z;
            this.uncompressedNbt = uncompressedNbt;
            this.versionId = versionId;
        }
    }

    /** 装配结果：.mca 字节 + 外溢 .mcc（c.x.z.mcc 相对名 → 压缩载荷）。 */
    public static final class Packed {
        public final byte[] mca;
        public final Map<String, byte[]> mcc;

        Packed(byte[] mca, Map<String, byte[]> mcc) {
            this.mca = mca;
            this.mcc = mcc;
        }
    }

    /**
     * 由区块表装配一份 .mca（含外溢）。
     *
     * @param chunks (x,z) → Chunk；空表产出仅 8 KiB 头的空 region
     * @param timestampSeconds  Location/时间戳表用的秒级时间（与原版一致取 now 秒）
     */
    public static Packed pack(Map<Long, Chunk> chunks, int timestampSeconds) throws IOException {
        if (chunks == null) {
            chunks = new LinkedHashMap<>();
        }
        // 先压缩全部区块，确定哪些外溢
        Map<Long, byte[]> compressed = new LinkedHashMap<>();
        Map<Long, Boolean> external = new LinkedHashMap<>();
        Map<Long, Integer> versions = new LinkedHashMap<>();
        for (Map.Entry<Long, Chunk> e : chunks.entrySet()) {
            Chunk c = e.getValue();
            byte[] payload = compress(c.uncompressedNbt, c.versionId);
            int total = 5 + payload.length;
            int sectors = (total + SECTOR - 1) / SECTOR;
            boolean ext = sectors >= EXTERNAL_CHUNK_THRESHOLD;
            compressed.put(e.getKey(), payload);
            external.put(e.getKey(), ext);
            versions.put(e.getKey(), c.versionId);
        }
        // 扇区分配：2 起，按 chunkIndex 顺序
        int nextSector = 2;
        Map<Long, int[]> allocation = new LinkedHashMap<>();
        // 外溢 stub 只占 1 扇区；普通按实际扇区数
        for (Map.Entry<Long, Chunk> e : chunks.entrySet()) {
            long key = e.getKey();
            if (external.get(key)) {
                allocation.put(key, new int[]{nextSector, 1});
                nextSector += 1;
            } else {
                byte[] payload = compressed.get(key);
                int total = 5 + payload.length;
                int sectors = (total + SECTOR - 1) / SECTOR;
                allocation.put(key, new int[]{nextSector, sectors});
                nextSector += sectors;
            }
        }
        int totalSectors = Math.max(nextSector, 2);
        byte[] mca = new byte[totalSectors * SECTOR];
        // Location 表 + 时间戳表
        for (Map.Entry<Long, Chunk> e : chunks.entrySet()) {
            Chunk c = e.getValue();
            int index = SnapshotChunkDecoder.chunkIndex(c.x, c.z);
            int[] alloc = allocation.get(e.getKey());
            putInt(mca, index * 4, (alloc[0] << 8) | (alloc[1] & 0xFF));
            putInt(mca, SECTOR + index * 4, timestampSeconds);
        }
        Map<String, byte[]> mcc = new LinkedHashMap<>();
        for (Map.Entry<Long, Chunk> e : chunks.entrySet()) {
            Chunk c = e.getValue();
            long key = e.getKey();
            int[] alloc = allocation.get(key);
            int base = alloc[0] * SECTOR;
            byte[] payload = compressed.get(key);
            if (external.get(key)) {
                int versionByte = (versions.get(key) & 0xFF) | 128;
                putInt(mca, base, 1);
                mca[base + 4] = (byte) versionByte;
                // 外溢载荷：与原版 writeToExternalFile 一致，仅压缩字节（不含 5 字节头）
                mcc.put("c." + c.x + "." + c.z + ".mcc", payload);
            } else {
                putInt(mca, base, payload.length + 1);
                mca[base + 4] = (byte) (versions.get(key) & 0xFF);
                System.arraycopy(payload, 0, mca, base + 5, payload.length);
            }
        }
        return new Packed(mca, mcc);
    }

    /** 压缩未压缩 NBT 为 Anvil 载荷（不含 5 字节头）。 */
    public static byte[] compress(byte[] raw, int versionId) throws IOException {
        if (raw == null) {
            throw new IOException("null raw nbt");
        }
        switch (versionId) {
            case VERSION_GZIP: {
                ByteArrayOutputStream bos = new ByteArrayOutputStream(raw.length / 2 + 32);
                GZIPOutputStream out = new GZIPOutputStream(bos);
                out.write(raw);
                out.close();
                return bos.toByteArray();
            }
            case VERSION_DEFLATE: {
                ByteArrayOutputStream bos = new ByteArrayOutputStream(raw.length / 2 + 32);
                DeflaterOutputStream out = new DeflaterOutputStream(bos, new Deflater(6));
                out.write(raw);
                out.close();
                return bos.toByteArray();
            }
            case VERSION_NONE: {
                byte[] copy = new byte[raw.length];
                System.arraycopy(raw, 0, copy, 0, raw.length);
                return copy;
            }
            default:
                throw new IOException("unsupported anvil stream version for pack: " + versionId);
        }
    }

    /** 解开 Anvil 载荷为未压缩 NBT（与 SnapshotChunkDecoder.decompress 语义一致，不含 LZ4 反射）。 */
    public static byte[] decompress(byte[] payload, int versionId) throws IOException {
        return SnapshotChunkDecoder.decompress(versionId, payload);
    }

    private static void putInt(byte[] b, int base, int v) {
        b[base] = (byte) ((v >>> 24) & 0xFF);
        b[base + 1] = (byte) ((v >>> 16) & 0xFF);
        b[base + 2] = (byte) ((v >>> 8) & 0xFF);
        b[base + 3] = (byte) (v & 0xFF);
    }

    /** 测试用：比较两份 NBT 字节逐字节一致。 */
    static boolean sameBytes(byte[] a, byte[] b) {
        return Arrays.equals(a, b);
    }
}
