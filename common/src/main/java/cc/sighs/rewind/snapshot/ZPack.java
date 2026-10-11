package cc.sighs.rewind.snapshot;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code <slot>.zpack} 二进制布局（TODO2 Phase2 §2.1）。
 *
 * <pre>
 * [Header: 16 字节] 大端
 *   魔数 4B "RWZP" (0x52 0x57 0x5A 0x50)
 *   格式版本 2B uint16 (当前 1)
 *   条目数量 4B uint32
 *   标志位 2B uint16 (保留)
 *   保留 4B 0x00
 * [Entry Table: 变长]
 *   目标类型 1B: 0x01 CHUNK / 0x02 ENTITY / 0x03 POI / 0x04 DATA_FILE
 *   标识符:
 *     区块类: 维度 ID 字符串 (2B 长度 + UTF-8) + chunkPos 8B long
 *     DATA_FILE: 相对路径字符串 (2B 长度 + UTF-8)
 *   存储模式 1B: 0x00 DELTA / 0x01 FULL
 *   原始未压缩长度 4B uint32
 *   校验和 8B uint64 (未压缩 NBT 的 XXH64, seed 0)
 *   载荷偏移 8B uint64 (文件绝对偏移, 大端)
 *   压缩载荷长度 4B uint32
 * [Payload Data Block]
 *   按 Entry 顺序排列的连续 Zstd 载荷流
 * </pre>
 *
 * <p>Java 8 兼容、无 Minecraft 依赖。读写均为大端，与 DataOutputStream 一致。
 */
public final class ZPack {
    /** 魔数 "RWZP"。 */
    public static final int MAGIC = 0x52575A50;
    /** 当前格式版本。 */
    public static final int VERSION = 1;
    /** 头部长度。 */
    public static final int HEADER_SIZE = 16;

    /** 目标类型。 */
    public static final int TYPE_CHUNK = 0x01;
    /** 目标类型。 */
    public static final int TYPE_ENTITY = 0x02;
    /** 目标类型。 */
    public static final int TYPE_POI = 0x03;
    /** 目标类型。 */
    public static final int TYPE_DATA_FILE = 0x04;

    /** 存储模式：基于父槽位同名项的 Zstd 字典差分。 */
    public static final int MODE_DELTA = 0x00;
    /** 存储模式：独立无字典 Zstd 压缩（无基底/膨胀/关键帧 Anchor）。 */
    public static final int MODE_FULL = 0x01;

    /** 增量链深阈值：自上一 FULL 起超过 K 即强制 FULL 关键帧（抑制 O(N) 解压雪崩）。 */
    public static final int KEYFRAME_CHAIN_LIMIT = 20;

    private ZPack() {
    }

    /** 单个 Entry（含载荷）。 */
    public static final class Entry {
        public final int targetType;
        /** 区块类：维度 ID；DATA_FILE：相对路径。 */
        public final String id;
        /** 区块类有效；DATA_FILE 为 0。 */
        public final long chunkPos;
        public final int mode;
        public final int rawLength;
        public final long xxh64;
        public final byte[] payload;

        public Entry(int targetType, String id, long chunkPos, int mode,
                int rawLength, long xxh64, byte[] payload) {
            this.targetType = targetType;
            this.id = id;
            this.chunkPos = chunkPos;
            this.mode = mode;
            this.rawLength = rawLength;
            this.xxh64 = xxh64;
            this.payload = payload;
        }

        public boolean isChunkLike() {
            return targetType == TYPE_CHUNK || targetType == TYPE_ENTITY || targetType == TYPE_POI;
        }

        /** 链式解析用键：类型 + 标识 + 坐标。 */
        public String key() {
            return keyOf(targetType, id, chunkPos);
        }
    }

    /** 与 {@link Entry#key} 同式的链式键（回滚按它查表，建点按它分组）。 */
    public static String keyOf(int targetType, String id, long chunkPos) {
        if (targetType == TYPE_CHUNK || targetType == TYPE_ENTITY || targetType == TYPE_POI) {
            return targetType + "|" + id + "|" + chunkPos;
        }
        return targetType + "|" + id;
    }

    /** 解析后的链式键。 */
    public static final class ParsedKey {
        public final int targetType;
        /** 区块类：维度 ID；DATA_FILE：相对路径。 */
        public final String id;
        /** 区块类有效；DATA_FILE 为 0。 */
        public final long chunkPos;

        ParsedKey(int targetType, String id, long chunkPos) {
            this.targetType = targetType;
            this.id = id;
            this.chunkPos = chunkPos;
        }

        public boolean isChunkLike() {
            return targetType == TYPE_CHUNK || targetType == TYPE_ENTITY || targetType == TYPE_POI;
        }
    }

    /**
     * 解析链式键（与 {@link #keyOf} 互逆）。
     *
     * @throws IOException 形状非法（调用方按损坏处理）
     */
    public static ParsedKey parseKey(String key) throws IOException {
        if (key == null) {
            throw new IOException("null zpack key");
        }
        int first = key.indexOf('|');
        if (first < 0) {
            throw new IOException("bad zpack key: " + key);
        }
        int type;
        try {
            type = Integer.parseInt(key.substring(0, first));
        } catch (NumberFormatException e) {
            throw new IOException("bad zpack key type: " + key);
        }
        if (type == TYPE_CHUNK || type == TYPE_ENTITY || type == TYPE_POI) {
            int second = key.indexOf('|', first + 1);
            if (second < 0) {
                throw new IOException("bad zpack chunk key: " + key);
            }
            long chunkPos;
            try {
                chunkPos = Long.parseLong(key.substring(second + 1));
            } catch (NumberFormatException e) {
                throw new IOException("bad zpack key chunkPos: " + key);
            }
            return new ParsedKey(type, key.substring(first + 1, second), chunkPos);
        }
        if (type != TYPE_DATA_FILE) {
            throw new IOException("unknown zpack key type: " + key);
        }
        return new ParsedKey(type, key.substring(first + 1), 0L);
    }

    // ---------------------------------------------------------- 写

    /** 打包器：按加入顺序输出 Entry。 */
    public static final class Writer {
        private final List<Entry> entries = new ArrayList<>();

        public void add(Entry entry) {
            if (entry == null) {
                throw new NullPointerException("entry");
            }
            entries.add(entry);
        }

        public List<Entry> entries() {
            return Collections.unmodifiableList(entries);
        }

        public int size() {
            return entries.size();
        }

        /** 全部载荷字节之和（建点日志与验收对账用，不含头与 Entry 表）。 */
        public long payloadBytes() {
            long total = 0L;
            for (Entry e : entries) {
                total += e.payload.length;
            }
            return total;
        }

        public byte[] toBytes() throws IOException {
            ByteArrayOutputStream table = new ByteArrayOutputStream(1024);
            // 先算 payload 偏移：header + table 长度未知，需先编码 table 再回填
            List<byte[]> encodedEntries = new ArrayList<>(entries.size());
            for (Entry e : entries) {
                encodedEntries.add(encodeEntry(e, 0L));
            }
            int tableLen = 0;
            for (byte[] b : encodedEntries) {
                tableLen += b.length;
            }
            long base = HEADER_SIZE + tableLen;
            long offset = base;
            ByteArrayOutputStream out = new ByteArrayOutputStream((int) Math.min(base + 1024, 1 << 20));
            writeHeader(out, entries.size(), 0);
            for (int i = 0; i < entries.size(); i++) {
                Entry e = entries.get(i);
                byte[] row = encodeEntry(e, offset);
                out.write(row);
                offset += e.payload.length;
            }
            for (Entry e : entries) {
                out.write(e.payload);
            }
            return out.toByteArray();
        }

        private static byte[] encodeEntry(Entry e, long payloadOffset) throws IOException {
            byte[] idBytes = e.id.getBytes(StandardCharsets.UTF_8);
            if (idBytes.length > 0xFFFF) {
                throw new IOException("identifier too long: " + e.id);
            }
            ByteArrayOutputStream row = new ByteArrayOutputStream(32 + idBytes.length);
            row.write(e.targetType);
            writeU16(row, idBytes.length);
            row.write(idBytes);
            if (e.isChunkLike()) {
                writeLong(row, e.chunkPos);
            }
            row.write(e.mode);
            writeU32(row, e.rawLength);
            writeLong(row, e.xxh64);
            writeLong(row, payloadOffset);
            writeU32(row, e.payload.length);
            return row.toByteArray();
        }
    }

    // ---------------------------------------------------------- 读

    /** 解包结果：Entry 列表（载荷已切出）。 */
    public static final class Packed {
        public final int version;
        public final int flags;
        public final List<Entry> entries;

        Packed(int version, int flags, List<Entry> entries) {
            this.version = version;
            this.flags = flags;
            this.entries = entries;
        }

        /** 按链式键索引。 */
        public Map<String, Entry> index() {
            Map<String, Entry> map = new LinkedHashMap<>();
            for (Entry e : entries) {
                map.put(e.key(), e);
            }
            return map;
        }
    }

    public static Packed read(byte[] file) throws IOException {
        if (file == null || file.length < HEADER_SIZE) {
            throw new EOFException("zpack too short: " + (file == null ? -1 : file.length));
        }
        int pos = 0;
        int magic = readU32(file, pos);
        pos += 4;
        if (magic != MAGIC) {
            throw new IOException(String.format("bad zpack magic: 0x%08X", magic));
        }
        int version = readU16(file, pos);
        pos += 2;
        if (version != VERSION) {
            throw new IOException("unsupported zpack version: " + version);
        }
        long count = readU32Long(file, pos);
        pos += 4;
        int flags = readU16(file, pos);
        pos += 2;
        pos += 4; // reserved
        if (count > 500000) {
            throw new IOException("zpack entry count unreasonable: " + count);
        }
        List<Entry> entries = new ArrayList<>((int) Math.min(count, 8192));
        List<long[]> payloadWindows = new ArrayList<>((int) Math.min(count, 8192));
        for (long i = 0; i < count; i++) {
            if (pos >= file.length) {
                throw new EOFException("zpack entry table truncated at entry " + i);
            }
            int type = file[pos++] & 0xFF;
            if (type != TYPE_CHUNK && type != TYPE_ENTITY && type != TYPE_POI && type != TYPE_DATA_FILE) {
                throw new IOException("unknown zpack entry type: " + type);
            }
            boolean chunkLike = type == TYPE_CHUNK || type == TYPE_ENTITY || type == TYPE_POI;
            int idLen = readU16(file, pos);
            pos += 2;
            if (pos + idLen > file.length) {
                throw new EOFException("zpack identifier truncated at entry " + i);
            }
            String id = new String(file, pos, idLen, StandardCharsets.UTF_8);
            pos += idLen;
            long chunkPos = 0L;
            if (chunkLike) {
                if (pos + 8 > file.length) {
                    throw new EOFException("zpack chunkPos truncated at entry " + i);
                }
                chunkPos = readLong(file, pos);
                pos += 8;
            }
            if (pos + 1 + 4 + 8 + 8 + 4 > file.length) {
                throw new EOFException("zpack entry fields truncated at entry " + i);
            }
            int mode = file[pos++] & 0xFF;
            if (mode != MODE_DELTA && mode != MODE_FULL) {
                throw new IOException("unknown zpack mode: " + mode);
            }
            long rawLength = readU32Long(file, pos);
            pos += 4;
            long xxh64 = readLong(file, pos);
            pos += 8;
            long payloadOffset = readLong(file, pos);
            pos += 8;
            long payloadLength = readU32Long(file, pos);
            pos += 4;
            if (rawLength > Integer.MAX_VALUE) {
                throw new IOException("zpack raw length too large: " + rawLength);
            }
            if (payloadLength > Integer.MAX_VALUE) {
                throw new IOException("zpack payload length too large: " + payloadLength);
            }
            payloadWindows.add(new long[]{payloadOffset, payloadLength});
            // 暂存占位 Entry，载荷第二遍切出
            entries.add(new Entry(type, id, chunkPos, mode, (int) rawLength, xxh64, new byte[0]));
        }
        // 切出载荷并校验窗口
        List<Entry> finished = new ArrayList<>(entries.size());
        for (int i = 0; i < entries.size(); i++) {
            Entry e = entries.get(i);
            long off = payloadWindows.get(i)[0];
            long len = payloadWindows.get(i)[1];
            if (off < 0 || len < 0 || off + len > file.length) {
                throw new EOFException("zpack payload window out of bounds at entry " + i
                        + ": off=" + off + " len=" + len + " file=" + file.length);
            }
            byte[] payload = new byte[(int) len];
            System.arraycopy(file, (int) off, payload, 0, (int) len);
            finished.add(new Entry(e.targetType, e.id, e.chunkPos, e.mode, e.rawLength, e.xxh64, payload));
        }
        return new Packed(version, flags, Collections.unmodifiableList(finished));
    }

    // ---------------------------------------------------------- 构造辅助

    /**
     * 构造单个 Entry：计算 XXH64 + 同基线膨胀兜底。
     *
     * @param raw          未压缩 NBT
     * @param dict         父槽位同名项未压缩 NBT（无基底传 null）
     * @param forceFull    关键帧 Anchor / 无基底时强制 FULL
     * @param level        zstd 等级
     * @return DELTA 或 FULL 的 Entry（膨胀时自动回退 FULL）
     */
    public static Entry buildEntry(int targetType, String id, long chunkPos,
            byte[] raw, byte[] dict, boolean forceFull, int level) throws IOException {
        if (raw == null) {
            throw new IOException("null raw nbt for " + id);
        }
        long checksum = Xxh64.hash(raw);
        byte[] full = ZstdDelta.compressFull(raw, level);
        if (forceFull || dict == null || dict.length == 0) {
            return new Entry(targetType, id, chunkPos, MODE_FULL, raw.length, checksum, full);
        }
        byte[] patch = ZstdDelta.compressDelta(raw, dict, level);
        if (ZstdDelta.shouldStoreFull(patch.length, full.length)) {
            return new Entry(targetType, id, chunkPos, MODE_FULL, raw.length, checksum, full);
        }
        return new Entry(targetType, id, chunkPos, MODE_DELTA, raw.length, checksum, patch);
    }

    /** 解开单个 Entry（DELTA 需 dict；FULL 传 null），并校验 XXH64。 */
    public static byte[] resolveEntry(Entry entry, byte[] dictRaw) throws IOException {
        byte[] raw;
        if (entry.mode == MODE_FULL) {
            raw = ZstdDelta.decompress(entry.payload, null, entry.rawLength);
        } else {
            if (dictRaw == null) {
                throw new IOException("delta entry has no base: " + entry.key());
            }
            raw = ZstdDelta.decompress(entry.payload, dictRaw, entry.rawLength);
        }
        if (raw.length != entry.rawLength) {
            throw new IOException("zpack length mismatch for " + entry.key()
                    + ": expected " + entry.rawLength + " got " + raw.length);
        }
        long actual = Xxh64.hash(raw);
        if (actual != entry.xxh64) {
            throw new IOException(String.format("zpack xxh64 mismatch for %s: expected %016x got %016x",
                    entry.key(), entry.xxh64, actual));
        }
        return raw;
    }

    // ---------------------------------------------------------- 编解码小工具（大端）

    private static void writeHeader(ByteArrayOutputStream out, long count, int flags) {
        writeU32(out, MAGIC);
        writeU16(out, VERSION);
        writeU32(out, count);
        writeU16(out, flags);
        writeU32(out, 0);
    }

    private static void writeU16(ByteArrayOutputStream out, int v) {
        out.write((v >>> 8) & 0xFF);
        out.write(v & 0xFF);
    }

    private static void writeU32(ByteArrayOutputStream out, long v) {
        out.write((int) ((v >>> 24) & 0xFF));
        out.write((int) ((v >>> 16) & 0xFF));
        out.write((int) ((v >>> 8) & 0xFF));
        out.write((int) (v & 0xFF));
    }

    private static void writeLong(ByteArrayOutputStream out, long v) {
        for (int i = 7; i >= 0; i--) {
            out.write((int) ((v >>> (i * 8)) & 0xFF));
        }
    }

    private static int readU16(byte[] b, int pos) throws EOFException {
        if (pos + 2 > b.length) {
            throw new EOFException("truncated u16 at " + pos);
        }
        return ((b[pos] & 0xFF) << 8) | (b[pos + 1] & 0xFF);
    }

    private static int readU32(byte[] b, int pos) throws EOFException {
        if (pos + 4 > b.length) {
            throw new EOFException("truncated u32 at " + pos);
        }
        return ((b[pos] & 0xFF) << 24) | ((b[pos + 1] & 0xFF) << 16)
                | ((b[pos + 2] & 0xFF) << 8) | (b[pos + 3] & 0xFF);
    }

    private static long readU32Long(byte[] b, int pos) throws EOFException {
        return readU32(b, pos) & 0xFFFFFFFFL;
    }

    private static long readLong(byte[] b, int pos) throws EOFException {
        if (pos + 8 > b.length) {
            throw new EOFException("truncated u64 at " + pos);
        }
        long v = 0L;
        for (int i = 0; i < 8; i++) {
            v = (v << 8) | (b[pos + i] & 0xFF);
        }
        return v;
    }
}
