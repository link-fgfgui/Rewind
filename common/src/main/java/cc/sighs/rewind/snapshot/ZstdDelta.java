package cc.sighs.rewind.snapshot;

import java.io.IOException;
import com.github.luben.zstd.Zstd;
import com.github.luben.zstd.ZstdCompressCtx;
import cc.sighs.rewind.common.config.RollbackSettings;

/**
 * Zstd 字典差分封装（TODO2 Phase2 §1.1：以旧代典 Reference Dictionary Delta）。
 *
 * <p>直接在未压缩原始 NBT 字节层做差分：父槽位 NBT 充当 dictionary，当前槽位 NBT 为 target，
 * 产出极小 patch（典型中位数 200B~1.5KiB）。解压时以 base 为字典传入 patch，微秒级还原
 * 100% 字节一致的 target。
 *
 * <p>运行库为 {@code com.github.luben:zstd-jni}（四个 target 的硬编译依赖，随包分发），
 * 直接静态调用，无反射。等级与长距离匹配（{@code --long}）经 {@link RollbackSettings}
 * 配置：等级对 FULL/DELTA 都生效；{@code --long} 只给 DELTA 路径（FULL 基线要能与历史
 * 包同口径比较，quick 槽位也不 diff 用不上）。
 *
 * <p>解压窗口：写入端窗口上限 27（见 {@link RollbackSettings#MAX_ZSTD_LONG_WINDOW_LOG}），
 * 帧头声明的窗口不会超过任何 zstd 默认解压端的上限，因此读侧无需额外配置。
 *
 * <p>Java 8 兼容、无 Minecraft 依赖。
 */
public final class ZstdDelta {
    /** 默认压缩等级：兼顾建点吞吐与补丁体积。 */
    public static final int DEFAULT_LEVEL = 3;

    private ZstdDelta() {
    }

    /** 独立无字典压缩（FULL 基线；同算法同基线比较用它）。 */
    public static byte[] compressFull(byte[] raw, int level) throws IOException {
        if (raw == null) {
            throw new IOException("null raw nbt");
        }
        try {
            return Zstd.compress(raw, level);
        } catch (RuntimeException e) {
            throw new IOException("zstd compress failed", e);
        }
    }

    /**
     * 以 base 为字典压缩 target（DELTA patch）。
     *
     * <p>{@code rollback.zstd.long} 开着时走 {@link ZstdCompressCtx} 开长距离匹配
     * （{@code ctx.setLong(windowLog)}）；关着走无状态静态入口，省一次上下文创建。
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
            if (!RollbackSettings.zstdLong()) {
                return Zstd.compressUsingDict(target, dict, level);
            }
            try (ZstdCompressCtx ctx = new ZstdCompressCtx()) {
                ctx.setLevel(level);
                ctx.setLong(RollbackSettings.zstdLongWindowLog());
                ctx.loadDict(dict);
                ctx.setPledgedSrcSize(target.length);
                return ctx.compress(target);
            }
        } catch (RuntimeException e) {
            throw new IOException("zstd delta compress failed", e);
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
            return hasDict
                    ? Zstd.decompress(patch, dict, originalLength)
                    : Zstd.decompress(patch, originalLength);
        } catch (RuntimeException e) {
            throw new IOException("zstd decompress failed", e);
        }
    }

    /**
     * 膨胀兜底判定（同算法同基线）：patch >= standaloneFull 时应存 FULL。
     *
     * @return true = 应回退存 FULL
     */
    public static boolean shouldStoreFull(int patchLength, int standaloneFullLength) {
        return patchLength >= standaloneFullLength;
    }
}
