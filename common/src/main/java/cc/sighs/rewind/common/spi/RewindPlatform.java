package cc.sighs.rewind.common.spi;

import java.nio.file.Path;
import java.util.List;
import cc.sighs.rewind.snapshot.SnapshotInventory;

/**
 * SPI：平台（加载器 + Minecraft 版本）向 common 暴露的能力。
 *
 * <p>每个 target 实现一份，并在模组构造阶段用 {@link RewindPlatforms#install} 装进来。common 侧所有
 * 需要碰 Minecraft 的地方（世界根目录、线程判定、服务器状态、强制落盘、原地回滚）都只经过这个接口，
 * 因此 common 本身不引用任何 {@code net.minecraft.*} / 加载器类型。
 *
 * <p>参数里的 {@code server} 是该平台自己的服务器对象（例如 NeoForge 1.21.1 的
 * {@code MinecraftServer}，Fabric 1.20.1 的 {@code MinecraftServer}）；common 只把它当作不透明句柄
 * 原样回传，实现方负责转型。common 里没有可用的公共父类型，所以这里只能用 {@code Object}。
 */
public interface RewindPlatform {
    /** 模组版本号，只用于元数据展示；取不到时返回 {@code "unknown"}。 */
    String modVersion();

    /** 活动存档根目录（{@code level.dat} 所在目录）。 */
    Path worldRoot(Object server);

    /** 当前线程是不是这个服务器的服务端线程。 */
    boolean isSameThread(Object server);

    /** 是不是专用服务器。 */
    boolean isDedicatedServer(Object server);

    /** 局域网是不是已开放。 */
    boolean isPublished(Object server);

    /** 世界里有没有玩家。 */
    boolean hasPlayers(Object server);

    /** 强制把世界落盘到「可以安全拷贝」的状态，并采集展示用的元数据与背包快照。 */
    FlushOutcome flushForCheckpoint(Object server, String slot, String source);

    /** 这个平台有没有原地回滚引擎；没有时 {@code RewindApi.rollbackInPlace} 直接返回失败。 */
    boolean supportsInPlaceRollback();

    /**
     * 原地回滚：世界不关、客户端不重登，在活着的集成服务器里把世界倒回存档点。
     *
     * <p>调用方已经把回滚窗口打开（{@code RewindState.beginDiscard()}）并在收尾时关掉；
     * 实现只管把世界倒回去。
     */
    RollbackOutcome rollbackInPlace(Object server, Path worldRoot, String slot) throws Exception;

    /**
     * 某个模组的类加载器；拿不到返回 null。
     *
     * <p>跨模组反射要用它：NeoForge / Forge 的模组是 JPMS 模块，各自的类加载器**只看得到自己读得到的
     * 模块**，拿 common 自己那个去 {@code Class.forName} 看不见别的模组（开发环境实测会
     * {@code ClassNotFoundException}）。Fabric 只有一个 KnotClassLoader，返回它自己那个即可。
     *
     * <p>用于可选模组适配，见 {@code cc.sighs.rewind.common.compat.SophisticatedCoreCompat}。
     */
    ClassLoader modClassLoader(String modId);

    /**
     * 把写账本里的不透明 NBT 句柄压缩成扇区负载（增量建点用）。
     *
     * <p>默认返回 null（该平台还没接增量建点，建点回退到全量磁盘扫描）。
     * 接入的版本用自己的 {@code NbtIo.write} + {@code RegionFileVersion} 实现，
     * 压缩算法固定用 DEFLATE（解码器按头版本分派，重压成别的版本依然合法）。
     *
     * @param tag 写账本里存的那个句柄（版本侧的 {@code CompoundTag}）
     */
    default EncodedSector encodeChunkSector(Object tag) {
        return null;
    }

    /**
     * 把解压后的 NBT 负载字节解成写账本能存的不透明句柄（回滚灌装用）。
     *
     * <p>默认返回 null（该平台还没接内存直解，回滚回退到读活文件）。
     * 接入的版本用自己的 {@code NbtIo.read} 实现。
     *
     * @param uncompressed 解码器解出来的未压缩 NBT 字节
     */
    default Object decodeChunkSector(byte[] uncompressed) {
        return null;
    }

    /**
     * 把写账本里的不透明 NBT 句柄编成未压缩 NBT 字节（zpack 字典差分建点用）。
     *
     * <p>与 {@link #decodeChunkSector} 互逆：本方法输出的字节交回去必须 round-trip 一致。
     * 默认返回 null（该槽位不进 zpack，回滚走块存储旧路）。接入的版本用自己的
     * {@code NbtIo.write}（不压缩，直出）实现。
     *
     * @param tag 写账本里存的那个句柄（版本侧的 {@code CompoundTag}）
     * @return 未压缩 NBT 字节；编不出返回 null
     */
    default byte[] encodeRawNbt(Object tag) {
        return null;
    }

    /**
     * 增量建点时“(种类, 维度) → region 目录相对路径”的映射（如
     * {@code region}、{@code DIM-1/region}、{@code entities}……，相对世界根，
     * {@code /} 分隔）。默认返回 null（未知布局，涉及该条目的文件回退到磁盘扫描；
     * 1.20.1 模组自定义维度布局即走此分支，绝不硬猜）。
     */
    default String chunkDirRelative(StorageKind kind, String dimensionId) {
        return null;
    }

    /**
     * 把账本里的打包坐标拆成 {@code {x, z}}（增量建点分组与 region 文件名用）。
     *
     * <p>默认按原版 `ChunkPos.toLong()` 布局（x 在低 32 位，z 在高 32 位）；
     * 打包方式不同的版本（如 26.1 的 `pack()`）覆盖它，一律以该版本实际 API 为准。
     */
    default int[] unpackChunkPos(long packed) {
        return new int[]{(int) packed, (int) (packed >> 32)};
    }
}
