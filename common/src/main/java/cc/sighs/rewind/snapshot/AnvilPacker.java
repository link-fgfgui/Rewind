package cc.sighs.rewind.snapshot;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;

/**
 * 离线 Anvil 装配器（TODO2 §4.3：纯 NBT 装配 Anvil 格式重写器）。
 *
 * <p>Phase 2 弃用 4 KiB 物理扇区快照后，{@code WorldRepair} 不再盲写扇区二进制块，
 * 改为在本类中由目标槽位 zpack 链还原出的全量未压缩 NBT（{@code byte[]}，调用方经
 * {@link ZPackChain} 取得）在内存中完成：DEFLATE 压缩、写入 5 字节 Anvil 头、
 * 4 KiB 扇区对齐、生成 {@code .mca} 文件（超 1 MiB 自动输出 {@code .mcc} 外溢
 * stub + 独立载荷）。
 *
 * <p>zpack 只存未压缩 NBT，不记原 Anvil 流版本；重建统一用
 * {@link AnvilFormat#VERSION_DEFLATE}——解码端按头分派，内容逐字节一致。
 *
 * <p>common 纯 Java 8、无 Minecraft 依赖：输入输出均为字节，不解析 NBT 结构。
 */
public final class AnvilPacker {
    private AnvilPacker() {
    }

    /** 单个区块的装配输入：未压缩 NBT。 */
    public static final class Chunk {
        public final int x;
        public final int z;
        public final byte[] uncompressedNbt;

        public Chunk(int x, int z, byte[] uncompressedNbt) {
            this.x = x;
            this.z = z;
            this.uncompressedNbt = uncompressedNbt;
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
        for (Map.Entry<Long, Chunk> e : chunks.entrySet()) {
            Chunk c = e.getValue();
            byte[] payload = compress(c.uncompressedNbt);
            int total = 5 + payload.length;
            int sectors = (total + AnvilFormat.SECTOR_BYTES - 1) / AnvilFormat.SECTOR_BYTES;
            boolean ext = sectors >= AnvilFormat.EXTERNAL_CHUNK_THRESHOLD;
            compressed.put(e.getKey(), payload);
            external.put(e.getKey(), ext);
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
                int sectors = (total + AnvilFormat.SECTOR_BYTES - 1) / AnvilFormat.SECTOR_BYTES;
                allocation.put(key, new int[]{nextSector, sectors});
                nextSector += sectors;
            }
        }
        int totalSectors = Math.max(nextSector, 2);
        byte[] mca = new byte[totalSectors * AnvilFormat.SECTOR_BYTES];
        // Location 表 + 时间戳表
        for (Map.Entry<Long, Chunk> e : chunks.entrySet()) {
            Chunk c = e.getValue();
            int index = SnapshotChunkDecoder.chunkIndex(c.x, c.z);
            int[] alloc = allocation.get(e.getKey());
            AnvilFormat.putInt(mca, index * 4, (alloc[0] << 8) | (alloc[1] & 0xFF));
            AnvilFormat.putInt(mca, AnvilFormat.SECTOR_BYTES + index * 4, timestampSeconds);
        }
        Map<String, byte[]> mcc = new LinkedHashMap<>();
        for (Map.Entry<Long, Chunk> e : chunks.entrySet()) {
            Chunk c = e.getValue();
            long key = e.getKey();
            int[] alloc = allocation.get(key);
            int base = alloc[0] * AnvilFormat.SECTOR_BYTES;
            byte[] payload = compressed.get(key);
            if (external.get(key)) {
                // 外溢 stub 只占 1 扇区；载荷与原版 writeToExternalFile 一致，仅压缩字节（不含 5 字节头）
                byte[] stub = AnvilDeltaAssembler.buildExternalStub(AnvilFormat.VERSION_DEFLATE);
                System.arraycopy(stub, 0, mca, base, stub.length);
                mcc.put(MccName.format(c.x, c.z), payload);
            } else {
                byte[] sector = AnvilDeltaAssembler.buildSector(AnvilFormat.VERSION_DEFLATE, payload);
                System.arraycopy(sector, 0, mca, base, sector.length);
            }
        }
        return new Packed(mca, mcc);
    }

    /** 压缩未压缩 NBT 为 DEFLATE 载荷（不含 5 字节头）。 */
    public static byte[] compress(byte[] raw) throws IOException {
        if (raw == null) {
            throw new IOException("null raw nbt");
        }
        ByteArrayOutputStream bos = new ByteArrayOutputStream(raw.length / 2 + 32);
        DeflaterOutputStream out = new DeflaterOutputStream(bos, new Deflater(6));
        out.write(raw);
        out.close();
        return bos.toByteArray();
    }
}
