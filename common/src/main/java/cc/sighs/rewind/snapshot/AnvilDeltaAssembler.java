package cc.sighs.rewind.snapshot;

import java.io.EOFException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Anvil region 文件的内存组装器（纯 common / 纯 JDK）。
 *
 * <p>增量建点用它：版本节点把监听到的 NBT 在内存里压缩成扇区负载（见各版本平台的
 * sector 编码器），common 把新扇区拼进上一个快照的文件字节里，全程不读
 * {@code /saves}。重建的文件与原版 {@code RegionFile} 写出来的字节逐字节兼容：
 * 8 KiB 头（偏移表 + 时间戳表，大端）+ 4 KiB 扇区顺序拼接。
 *
 * <p>组装策略是“打包重排”：以上一个快照的文件为底，把未改动的区块扇区原样搬运、
 * 改动的换成新扇区、删除的丢掉，再从 2 号扇区起顺序重排。偏移指向新位置，
 * 旧扇区的空洞不保留——文件天然碎片整理，不会越改越大。时间戳：改动/新增用
 * {@code nowSeconds}，未改动沿用旧值。
 *
 * <p>外溢（压缩后超过 1 MiB）：{@code .mca} 里只放 1 扇区 stub（长度 1、版本
 * {@code |128}），真正的压缩负载由调用方另存为 {@code .mcc} 条目（见
 * {@link SnapshotChunkDecoder#mccRelative})。组装器只管 {@code .mca} 这边。
 */
public final class AnvilDeltaAssembler {
    public static final int SECTOR_BYTES = 4096;
    public static final int HEADER_BYTES = 8192;
    public static final int CHUNK_COUNT = 1024;
    public static final int EXTERNAL_STREAM_FLAG = 128;

    private AnvilDeltaAssembler() {
    }

    /**
     * 一个区块的新扇区（调用方=版本节点已压缩好）。
     *
     * <p>{@code delete} 为 true 表示删除该区块（对应 {@code RegionFile.clear}：
     * 偏移归零，时间戳照样更新——这里直接丢掉条目，读到就是缺席）。
     * 外溢时 {@code compressed} 为 null（负载走 {@code .mcc}），只留版本。
     */
    public static final class NewChunk {
        public final boolean delete;
        public final boolean external;
        public final int versionId;
        public final byte[] compressed;

        private NewChunk(boolean delete, boolean external, int versionId, byte[] compressed) {
            this.delete = delete;
            this.external = external;
            this.versionId = versionId;
            this.compressed = compressed;
        }

        /** 普通更新：版本 + 压缩负载（j1 字节）。 */
        public static NewChunk update(int versionId, byte[] compressed) {
            if (compressed == null) {
                throw new NullPointerException("compressed");
            }
            return new NewChunk(false, false, versionId, compressed);
        }

        /** 外溢更新：只留版本，负载另存 .mcc。 */
        public static NewChunk external(int versionId) {
            return new NewChunk(false, true, versionId, null);
        }

        /** 删除该区块。 */
        public static NewChunk delete() {
            return new NewChunk(true, false, 0, null);
        }
    }

    /**
     * 组装一个新的 {@code .mca} 文件字节。
     *
     * @param prevFile 上一个快照的该文件字节；新文件时传 null（或不足 8 KiB，同样按空文件处理）
     * @param changes 下标（见 {@link SnapshotChunkDecoder#chunkIndex}）到新扇区的映射；
     *     null 值同样按删除处理（与 {@link NewChunk#delete()} 等价）
     * @param nowSeconds 新/改区块的时间戳（秒，与原版 {@code getTimestamp} 一致：epoch 毫秒 / 1000）
     * @return 新文件字节（头部 8 KiB + 扇区；空文件时就是 8 KiB 全零）
     */
    public static byte[] assemble(byte[] prevFile, Map<Integer, NewChunk> changes, int nowSeconds)
            throws IOException {
        int[] prevOffsets = new int[CHUNK_COUNT];
        int[] prevTimes = new int[CHUNK_COUNT];
        List<byte[]> prevSectors = new ArrayList<>(CHUNK_COUNT);
        for (int i = 0; i < CHUNK_COUNT; i++) {
            prevSectors.add(null);
        }
        if (prevFile != null && prevFile.length >= HEADER_BYTES) {
            for (int i = 0; i < CHUNK_COUNT; i++) {
                prevOffsets[i] = getInt(prevFile, i * 4);
                prevTimes[i] = getInt(prevFile, 4096 + i * 4);
            }
            for (int i = 0; i < CHUNK_COUNT; i++) {
                int packed = prevOffsets[i];
                int sectorNumber = (packed >> 8) & 0xFFFFFF;
                int sectorCount = packed & 0xFF;
                if (packed == 0 || sectorNumber < 2 || sectorCount == 0) {
                    continue;
                }
                long start = (long) sectorNumber * SECTOR_BYTES;
                long length = (long) sectorCount * SECTOR_BYTES;
                if (start < 0 || length <= 0 || start + length > prevFile.length) {
                    throw new EOFException("previous file sector " + i + " out of bounds");
                }
                byte[] raw = new byte[(int) length];
                System.arraycopy(prevFile, (int) start, raw, 0, (int) length);
                prevSectors.set(i, raw);
            }
        }

        Map<Integer, byte[]> live = new HashMap<>();
        Map<Integer, Integer> liveTimes = new HashMap<>();
        for (int i = 0; i < CHUNK_COUNT; i++) {
            byte[] raw = prevSectors.get(i);
            if (raw != null) {
                live.put(i, raw);
                liveTimes.put(i, prevTimes[i]);
            }
        }
        if (changes != null) {
            for (Map.Entry<Integer, NewChunk> e : changes.entrySet()) {
                int index = e.getKey();
                if (index < 0 || index >= CHUNK_COUNT) {
                    throw new IOException("chunk index out of range: " + index);
                }
                NewChunk change = e.getValue();
                if (change == null || change.delete) {
                    live.remove(index);
                    liveTimes.remove(index);
                    continue;
                }
                byte[] sector;
                if (change.external) {
                    sector = buildExternalStub(change.versionId);
                } else {
                    sector = buildSector(change.versionId, change.compressed);
                }
                live.put(index, sector);
                liveTimes.put(index, nowSeconds);
            }
        }

        // 从 2 号扇区起按下标顺序重排
        List<Integer> order = new ArrayList<>(live.keySet());
        java.util.Collections.sort(order);
        Map<Integer, Integer> newOffsets = new HashMap<>();
        int nextSector = 2;
        for (int index : order) {
            byte[] raw = live.get(index);
            int count = raw.length / SECTOR_BYTES;
            newOffsets.put(index, (nextSector << 8) | count);
            nextSector += count;
        }

        byte[] out = new byte[nextSector * SECTOR_BYTES];
        for (int i = 0; i < CHUNK_COUNT; i++) {
            Integer packed = newOffsets.get(i);
            putInt(out, i * 4, packed == null ? 0 : packed);
            int time;
            if (packed == null) {
                // 删除的区块：偏移归零即可，时间戳沿用旧值（与原版 clear 语义兼容，读侧只看偏移）
                time = prevTimes[i];
            } else if (changes != null && changes.containsKey(i)) {
                time = nowSeconds;
            } else {
                time = liveTimes.get(i);
            }
            putInt(out, 4096 + i * 4, time);
        }
        for (int index : order) {
            byte[] raw = live.get(index);
            int packed = newOffsets.get(index);
            int sectorNumber = (packed >> 8) & 0xFFFFFF;
            System.arraycopy(raw, 0, out, sectorNumber * SECTOR_BYTES, raw.length);
        }
        return out;
    }

    /** 普通扇区字节：4 字节长度（压缩长 + 1）+ 1 字节版本 + 压缩负载，补零到扇区整数倍。 */
    public static byte[] buildSector(int versionId, byte[] compressed) throws IOException {
        if (compressed == null) {
            throw new NullPointerException("compressed");
        }
        if ((versionId & EXTERNAL_STREAM_FLAG) != 0) {
            throw new IOException("plain sector must not carry the external flag: " + versionId);
        }
        int total = 5 + compressed.length;
        int sectors = (total + SECTOR_BYTES - 1) / SECTOR_BYTES;
        byte[] out = new byte[sectors * SECTOR_BYTES];
        putInt(out, 0, compressed.length + 1);
        out[4] = (byte) versionId;
        System.arraycopy(compressed, 0, out, 5, compressed.length);
        return out;
    }

    /** 外溢 stub 扇区：长度恒 1、版本带 |128，1 扇区。 */
    public static byte[] buildExternalStub(int versionId) throws IOException {
        if ((versionId & EXTERNAL_STREAM_FLAG) != 0) {
            throw new IOException("version already carries the external flag: " + versionId);
        }
        byte[] out = new byte[SECTOR_BYTES];
        putInt(out, 0, 1);
        out[4] = (byte) (versionId | EXTERNAL_STREAM_FLAG);
        return out;
    }

    private static int getInt(byte[] bytes, int base) {
        return ((bytes[base] & 0xFF) << 24)
                | ((bytes[base + 1] & 0xFF) << 16)
                | ((bytes[base + 2] & 0xFF) << 8)
                | (bytes[base + 3] & 0xFF);
    }

    private static void putInt(byte[] bytes, int base, int value) {
        bytes[base] = (byte) (value >> 24);
        bytes[base + 1] = (byte) (value >> 16);
        bytes[base + 2] = (byte) (value >> 8);
        bytes[base + 3] = (byte) value;
    }
}
