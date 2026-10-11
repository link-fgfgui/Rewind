package cc.sighs.rewind.snapshot;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;
import cc.sighs.rewind.common.RewindLog;

/**
 * Zstd 字典差分封装（TODO2 Phase2 §1.1：以旧代典 Reference Dictionary Delta）。
 *
 * <p>直接在未压缩原始 NBT 字节层做差分：父槽位 NBT 充当 dictionary，
 * 当前槽位 NBT 为 target，产出极小 patch（典型中位数 200B~1.5KiB）。
 * 解压时以 base 为字典传入 patch，微秒级还原 100% 字节一致的 target。
 *
 * <p>运行库为 {@code com.github.luben:zstd-jni}（C 原生高吞吐 + 全功能字典差分）。
 * 本类经反射调用 zstd-jni，避免 common 编译期硬绑定特定版本 API 形变；
 * JNI 缺席时回退为纯 JDK Deflate 独立压缩（FULL 仍可用，DELTA 退化为 FULL）。
 *
 * <p>Java 8 兼容、无 Minecraft 依赖。
 */
public final class ZstdDelta {
    /** 默认压缩等级：兼顾建点吞吐与补丁体积。 */
    public static final int DEFAULT_LEVEL = 3;
    /** 压缩等级上限（zstd-jni 最高 22）。 */
    public static final int MAX_LEVEL = 22;

    private ZstdDelta() {
    }

    /** zstd-jni 是否可用（本地库能加载且反射到字典 API）。 */
    public static boolean isAvailable() {
        try {
            Class<?> zstd = Class.forName("com.github.luben.zstd.Zstd");
            noteNative();
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 首次确认原生库可用时记一条 INFO（上机验收的判定口；之后永不再记）。
     *
     * <p>{@code Class.forName} 会执行静态初始化，而 `Zstd` 的静态块第一条即
     * `Native.load()`（1.5.7-11 字节码已核）——能走到这里即本地库已解压加载成功；
     * 缺 `.so` 时抛 `ExceptionInInitializerError`，外层 `catch (Throwable)` 吞掉，
     * 本方法根本不会被调到。
     */
    private static final AtomicBoolean ANNOUNCED = new AtomicBoolean();

    private static void noteNative() {
        if (ANNOUNCED.compareAndSet(false, true)) {
            RewindLog.LOGGER.info("Rewind: zstd-jni native library loaded");
        }
    }

    /**
     * 独立无字典压缩（FULL 基线；同算法同基线比较用它）。
     */
    public static byte[] compressFull(byte[] raw, int level) throws IOException {
        if (raw == null) {
            throw new IOException("null raw nbt");
        }
        try {
            Class<?> zstd = Class.forName("com.github.luben.zstd.Zstd");
            try {
                java.lang.reflect.Method m = zstd.getMethod("compress", byte[].class, int.class);
                return (byte[]) m.invoke(null, raw, level);
            } catch (NoSuchMethodException e) {
                // 兼容旧版签名 compress(byte[], int) 缺席时走流式 API
                return compressFullStreaming(raw, level);
            }
        } catch (ReflectiveOperationException e) {
            throw new IOException("zstd compress failed", e);
        } catch (Throwable t) {
            // JNI 缺席：回退 JDK Deflate 独立压缩，保证流程不断
            return deflate(raw);
        }
    }

    /**
     * 以 base 为字典压缩 target（DELTA patch）。
     *
     * @param target 当前槽位未压缩 NBT
     * @param dict   父槽位未压缩 NBT（可为 null/空，此时退化为 FULL）
     */
    public static byte[] compressDelta(byte[] target, byte[] dict, int level) throws IOException {
        if (target == null) {
            throw new IOException("null target nbt");
        }
        if (dict == null || dict.length == 0) {
            return compressFull(target, level);
        }
        try {
            Class<?> zstd = Class.forName("com.github.luben.zstd.Zstd");
            // 优先：compressUsingDict(byte[] src, byte[] dict, int level) -> byte[]
            try {
                java.lang.reflect.Method m = zstd.getMethod("compressUsingDict",
                        byte[].class, byte[].class, int.class);
                return (byte[]) m.invoke(null, target, dict, level);
            } catch (NoSuchMethodException ignored) {
                // fall through
            }
            // 其次：compressUsingDict(byte[] dst, byte[] src, byte[] dict, int level) -> long
            try {
                java.lang.reflect.Method bound = zstd.getMethod("compressBound", long.class);
                long boundSize = ((Number) bound.invoke(null, (long) target.length)).longValue();
                byte[] dst = new byte[(int) Math.min(boundSize + 64, Integer.MAX_VALUE - 8)];
                java.lang.reflect.Method m = zstd.getMethod("compressUsingDict",
                        byte[].class, byte[].class, byte[].class, int.class);
                Number written = (Number) m.invoke(null, dst, target, dict, level);
                byte[] out = new byte[written.intValue()];
                System.arraycopy(dst, 0, out, 0, out.length);
                return out;
            } catch (NoSuchMethodException ignored) {
                // fall through
            }
            return compressFullStreamingDict(target, dict, level);
        } catch (ReflectiveOperationException e) {
            throw new IOException("zstd delta compress failed", e);
        } catch (Throwable t) {
            return deflate(target);
        }
    }

    /**
     * 以 base 为字典解压 patch，还原 target 未压缩 NBT。
     *
     * @param patch          DELTA 载荷（或 FULL 载荷当 dict 为空时）
     * @param dict           父槽位未压缩 NBT（FULL 模式传 null）
     * @param originalLength Entry 头记录的原始未压缩长度（用于分配输出）
     */
    public static byte[] decompress(byte[] patch, byte[] dict, int originalLength) throws IOException {
        if (patch == null) {
            throw new IOException("null patch");
        }
        if (originalLength < 0) {
            throw new IOException("negative original length: " + originalLength);
        }
        boolean hasDict = dict != null && dict.length > 0;
        try {
            Class.forName("com.github.luben.zstd.Zstd");
            if (hasDict) {
                byte[] out = tryDecompressDict(patch, dict, originalLength);
                if (out != null) {
                    return out;
                }
            } else {
                byte[] out = tryDecompressFull(patch, originalLength);
                if (out != null) {
                    return out;
                }
            }
        } catch (ClassNotFoundException e) {
            // JNI 缺席：尝试 JDK Inflater 解开回退 Deflate 载荷
            return inflate(patch, originalLength);
        } catch (Throwable t) {
            throw new IOException("zstd decompress failed", t);
        }
        throw new IOException("zstd decompress failed: no suitable api");
    }

    /**
     * 膨胀兜底判定（同算法同基线）：patch >= standaloneFull 时应存 FULL。
     *
     * @return true = 应回退存 FULL
     */
    public static boolean shouldStoreFull(int patchLength, int standaloneFullLength) {
        return patchLength >= standaloneFullLength;
    }

    /**
     * 载荷算法判别（写包时记录进 zpack 模式字节，跨环境自描述）。
     *
     * <p>zstd 帧以 4 字节魔数 0x28 0xB5 0x2F 0xFD 打头；其余一律按 JDK Deflate 兜底。
     * 判别仅用于标注，读取端另有换算法重试 + XXH64 校验兜底。
     */
    static int algorithmOf(byte[] payload) {
        if (payload != null && payload.length >= 4
                && (payload[0] & 0xFF) == 0x28 && (payload[1] & 0xFF) == 0xB5
                && (payload[2] & 0xFF) == 0x2F && (payload[3] & 0xFF) == 0xFD) {
            return ZPack.ALGO_ZSTD;
        }
        return ZPack.ALGO_DEFLATE;
    }

    // ------------------------------------------------------------- 内部

    private static byte[] tryDecompressFull(byte[] patch, int originalLength) {
        try {
            Class<?> zstd = Class.forName("com.github.luben.zstd.Zstd");
            // decompress(byte[] src, int originalSize) -> byte[]
            try {
                java.lang.reflect.Method m = zstd.getMethod("decompress", byte[].class, int.class);
                return (byte[]) m.invoke(null, patch, originalLength);
            } catch (NoSuchMethodException ignored) {
            }
            // decompress(byte[] dst, byte[] src) -> long
            try {
                byte[] dst = new byte[originalLength];
                java.lang.reflect.Method m = zstd.getMethod("decompress",
                        byte[].class, byte[].class);
                Number n = (Number) m.invoke(null, dst, patch);
                if (n.longValue() != originalLength) {
                    byte[] out = new byte[n.intValue()];
                    System.arraycopy(dst, 0, out, 0, out.length);
                    return out;
                }
                return dst;
            } catch (NoSuchMethodException ignored) {
            }
        } catch (Throwable ignored) {
            // fall through to null
        }
        return null;
    }

    private static byte[] tryDecompressDict(byte[] patch, byte[] dict, int originalLength) {
        try {
            Class<?> zstd = Class.forName("com.github.luben.zstd.Zstd");
            // 主分支（1.5.x 实测存在）：decompressUsingDict(byte[] dst, byte[] src, byte[] dict) -> long
            try {
                byte[] dst = new byte[originalLength];
                java.lang.reflect.Method m = zstd.getMethod("decompressUsingDict",
                        byte[].class, byte[].class, byte[].class);
                Number n = (Number) m.invoke(null, dst, patch, dict);
                if (n.longValue() != originalLength) {
                    byte[] out = new byte[n.intValue()];
                    System.arraycopy(dst, 0, out, 0, out.length);
                    return out;
                }
                return dst;
            } catch (NoSuchMethodException ignored) {
            }
            // 兼容分支：decompressUsingDict(byte[] src, byte[] dict, int originalSize) -> byte[]
            try {
                java.lang.reflect.Method m = zstd.getMethod("decompressUsingDict",
                        byte[].class, byte[].class, int.class);
                return (byte[]) m.invoke(null, patch, dict, originalLength);
            } catch (NoSuchMethodException ignored) {
            }
        } catch (Throwable ignored) {
            // fall through to null
        }
        return null;
    }

    private static byte[] compressFullStreaming(byte[] raw, int level) throws IOException {
        try {
            Class<?> cls = Class.forName("com.github.luben.zstd.ZstdOutputStream");
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream(raw.length / 2 + 64);
            java.lang.reflect.Constructor<?> init = cls.getConstructor(java.io.OutputStream.class, int.class);
            java.io.OutputStream out = (java.io.OutputStream) init.newInstance(bos, level);
            out.write(raw);
            out.close();
            return bos.toByteArray();
        } catch (ReflectiveOperationException e) {
            throw new IOException("zstd streaming compress failed", e);
        }
    }

    private static byte[] compressFullStreamingDict(byte[] raw, byte[] dict, int level) throws IOException {
        try {
            Class<?> cls = Class.forName("com.github.luben.zstd.ZstdOutputStream");
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream(raw.length / 2 + 64);
            try {
                java.lang.reflect.Constructor<?> init = cls.getConstructor(
                        java.io.OutputStream.class, byte[].class, int.class);
                java.io.OutputStream out = (java.io.OutputStream) init.newInstance(bos, dict, level);
                out.write(raw);
                out.close();
                return bos.toByteArray();
            } catch (NoSuchMethodException e) {
                return compressFullStreaming(raw, level);
            }
        } catch (ReflectiveOperationException e) {
            throw new IOException("zstd dict streaming compress failed", e);
        }
    }

    private static byte[] deflate(byte[] raw) throws IOException {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream(raw.length / 2 + 32);
        java.util.zip.Deflater def = new java.util.zip.Deflater(DEFAULT_LEVEL);
        java.util.zip.DeflaterOutputStream out = new java.util.zip.DeflaterOutputStream(bos, def);
        out.write(raw);
        out.close();
        return bos.toByteArray();
    }

    /** 解码 Deflate 兜底载荷（{@link ZPack#ALGO_DEFLATE} 自描述路径用；与 {@link #deflate} 互逆）。 */
    static byte[] inflate(byte[] compressed, int originalLength) throws IOException {
        java.io.ByteArrayInputStream bis = new java.io.ByteArrayInputStream(compressed);
        java.util.zip.InflaterInputStream in = new java.util.zip.InflaterInputStream(bis);
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream(
                originalLength > 0 ? originalLength : 8192);
        byte[] buf = new byte[8192];
        int read;
        while ((read = in.read(buf)) >= 0) {
            bos.write(buf, 0, read);
        }
        in.close();
        return bos.toByteArray();
    }
}
