package cc.sighs.rewind.snapshot;

/**
 * 64 位 XXH64 校验（纯 Java 8 实现，与 zstd-jni 原生结果逐字节一致）。
 *
 * <p>TODO2 Phase2 规格定死：每个 ZPack Entry 附带未压缩 NBT 的 XXH64（seed 0）。
 * 不经由 CRC32。纯 Java 实现保证 common 在任何平台（即使 JNI 未加载）都能校验；
 * 有 JNI 时仍用本实现做比对，避免原生/纯 Java 双规并存。
 *
 * <p>算法为官方 XXH64（seed 0）：素数 P1..P5 与标准 avalanche，
 * 小端读取、逐块累加。空数组结果为固定值 0xEF46DB3751D8E999L。
 */
public final class Xxh64 {
    private static final long P1 = 0x9E3779B185EBCA87L;
    private static final long P2 = 0xC2B2AE3D27D4EB4FL;
    private static final long P3 = 0x165667B19E3779F9L;
    private static final long P4 = 0x85EBCA77C2B2AE63L;
    private static final long P5 = 0x27D4EB2F165667C5L;

    private Xxh64() {
    }

    public static long hash(byte[] data) {
        return hash(data, 0, data == null ? 0 : data.length, 0L);
    }

    private static long hash(byte[] data, int offset, int length, long seed) {
        if (data == null || length <= 0) {
            return avalanche(seed + P5);
        }
        int end = offset + length;
        long hash;
        int pos = offset;
        if (length >= 32) {
            long v1 = seed + P1 + P2;
            long v2 = seed + P2;
            long v3 = seed;
            long v4 = seed - P1;
            int limit = end - 32;
            while (pos <= limit) {
                v1 = round(v1, readLong(data, pos));
                pos += 8;
                v2 = round(v2, readLong(data, pos));
                pos += 8;
                v3 = round(v3, readLong(data, pos));
                pos += 8;
                v4 = round(v4, readLong(data, pos));
                pos += 8;
            }
            hash = Long.rotateLeft(v1, 1) + Long.rotateLeft(v2, 7)
                    + Long.rotateLeft(v3, 12) + Long.rotateLeft(v4, 18);
            hash = mergeRound(hash, v1);
            hash = mergeRound(hash, v2);
            hash = mergeRound(hash, v3);
            hash = mergeRound(hash, v4);
        } else {
            hash = seed + P5;
        }
        hash += length;
        while (pos + 8 <= end) {
            hash ^= round(0L, readLong(data, pos));
            hash = Long.rotateLeft(hash, 27) * P1 + P4;
            pos += 8;
        }
        if (pos + 4 <= end) {
            hash ^= (readInt(data, pos) & 0xFFFFFFFFL) * P1;
            hash = Long.rotateLeft(hash, 23) * P2 + P3;
            pos += 4;
        }
        while (pos < end) {
            hash ^= (data[pos] & 0xFFL) * P5;
            hash = Long.rotateLeft(hash, 11) * P1;
            pos++;
        }
        return avalanche(hash);
    }

    private static long round(long acc, long lane) {
        acc += lane * P2;
        acc = Long.rotateLeft(acc, 31);
        return acc * P1;
    }

    private static long mergeRound(long hash, long lane) {
        hash ^= round(0L, lane);
        return hash * P1 + P4;
    }

    private static long avalanche(long hash) {
        hash ^= hash >>> 33;
        hash *= P2;
        hash ^= hash >>> 29;
        hash *= P3;
        hash ^= hash >>> 32;
        return hash;
    }

    private static long readLong(byte[] data, int pos) {
        return ((long) (data[pos] & 0xFF))
                | ((long) (data[pos + 1] & 0xFF) << 8)
                | ((long) (data[pos + 2] & 0xFF) << 16)
                | ((long) (data[pos + 3] & 0xFF) << 24)
                | ((long) (data[pos + 4] & 0xFF) << 32)
                | ((long) (data[pos + 5] & 0xFF) << 40)
                | ((long) (data[pos + 6] & 0xFF) << 48)
                | ((long) (data[pos + 7] & 0xFF) << 56);
    }

    private static long readInt(byte[] data, int pos) {
        return (data[pos] & 0xFF)
                | ((data[pos + 1] & 0xFF) << 8)
                | ((data[pos + 2] & 0xFF) << 16)
                | ((data[pos + 3] & 0xFF) << 24);
    }
}
