package cc.sighs.rewind.common.spi;

import java.nio.file.Path;
import java.util.List;
import javax.annotation.Nullable;
import cc.sighs.rewind.api.RewindResult;
import cc.sighs.rewind.api.RollbackRequest;
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
     * <p>接入的版本用自己的 {@code NbtIo.write} + {@code RegionFileVersion} 实现，
     * 压缩算法固定用 DEFLATE（解码器按头版本分派，重压成别的版本依然合法）。
     *
     * @param tag 写账本里存的那个句柄（版本侧的 {@code CompoundTag}）
     * @return 未知句柄/编不出返回 null（调用方回退磁盘扫描，不当删除）
     */
    EncodedSector encodeChunkSector(Object tag);

    /**
     * 把解压后的 NBT 负载字节解成写账本能存的不透明句柄（回滚灌装用）。
     *
     * <p>接入的版本用自己的 {@code NbtIo.read} 实现。
     *
     * @param uncompressed 解码器解出来的未压缩 NBT 字节
     * @return 解不出返回 null（调用方回退旧路，跳过该条目）
     */
    Object decodeChunkSector(byte[] uncompressed);

    /**
     * 把写账本里的不透明 NBT 句柄编成未压缩 NBT 字节（zpack 字典差分建点用）。
     *
     * <p>与 {@link #decodeChunkSector} 互逆：本方法输出的字节交回去必须 round-trip 一致。
     * 接入的版本用自己的 {@code NbtIo.write}（不压缩，直出）实现。
     *
     * @param tag 写账本里存的那个句柄（版本侧的 {@code CompoundTag}）
     * @return 未压缩 NBT 字节；未知句柄/编不出返回 null（该条目不进 zpack，回滚走块存储旧路）
     */
    byte[] encodeRawNbt(Object tag);

    /**
     * 增量建点时“(种类, 维度) → region 目录相对路径”的映射（如
     * {@code region}、{@code DIM-1/region}、{@code entities}……，相对世界根，
     * {@code /} 分隔）。
     *
     * <p>未知布局返回 null（涉及该条目的文件回退到磁盘扫描；1.20.1 模组自定义维度布局
     * 即走此分支，绝不硬猜）。
     */
    String chunkDirRelative(StorageKind kind, String dimensionId);

    /**
     * 把账本里的打包坐标拆成 {@code {x, z}}（增量建点分组与 region 文件名用）。
     *
     * <p>默认按原版 `ChunkPos.toLong()` 布局（x 在低 32 位，z 在高 32 位）；
     * 打包方式不同的版本（如 26.1 的 `pack()`）覆盖它，一律以该版本实际 API 为准。
     */
    default int[] unpackChunkPos(long packed) {
        return new int[]{(int) packed, (int) (packed >> 32)};
    }

    /**
     * 回溯开始前的一次通知：返回 {@code false} 表示**取消**这次回溯，什么都不做。
     *
     * <p>取消原因写在 {@link RollbackRequest#cancelReason} 里（可空），它会进日志，也会跟着
     * {@link cc.sighs.rewind.api.RollbackCanceledException} 回到调用方。
     *
     * <p>target 侧在这里发自己的加载器事件：Forge / NeoForge 各有一个可取消的
     * {@code cc.sighs.rewind.event.RollbackStartEvent}。**默认实现放行**——没有事件总线的加载器
     * （Fabric）就用默认的，别的地方想接就自己接那边的入口。
     *
     * <p><b>线程</b>：原地回滚那条路上它在服务端线程上被调用；只覆盖文件的那条路
     * （世界已经关了，没有服务端线程可言）在调用它的那条线程上。
     */
    default boolean beforeRollback(RollbackRequest request) {
        return true;
    }

    /**
     * 回溯结束后的一次通知——不管成功还是失败都会来一次，**不可取消**。
     *
     * <p>{@code request} 就是 {@link #beforeRollback} 收到的那一个（同一次回溯复用同一个对象），
     * {@code result} 是这次回溯的结果：{@code success} / {@code failure} / {@code summary} /
     * {@code millis} 都在里面。被取消的那次**没有**结束通知（它根本没开始）。
     *
     * <p>默认实现什么都不做（Fabric）。
     */
    default void afterRollback(RollbackRequest request, RewindResult result) {
    }

    /**
     * 这个平台能不能跑「跨 tick 的多人在线回滚」（TODO4 阶段 2 的切片化引擎）。
     *
     * <p>能跑时 {@link #beginRollback} 返回一个 {@link RollbackJob}，由 common 的
     * {@code MultiplayerRollback} 调度器每 tick 推一片。默认不支持。
     */
    default boolean supportsSlicedRollback() {
        return false;
    }

    /**
     * 建一个跨 tick 的回滚作业；不支持或当前建不出来（世界状态不对、slot 不可还原）时返回 null。
     *
     * <p>调用方（{@code MultiplayerRollback}）已经把回滚窗口打开、已经把
     * {@code RewindState.beginMultiplayerRollback()} 置位，并在作业结束时统一收尾，
     * 所以实现只管按 {@link RollbackJob} 的分片协议干活。
     */
    @Nullable
    default RollbackJob beginRollback(Object server, Path worldRoot, String slot) {
        return null;
    }

    /**
     * 多人在线回滚即将开始时的广播（可选）：往聊天栏发一条「服务器正在时间回溯…」。
     *
     * <p>必须在回滚窗口打开**之前**调用——窗口开着的时候不再发任何我们自己产生的包。
     */
    default void announceRollback(Object server, String slot, String source) {
    }

    /**
     * 多人在线回滚成功后的客户端重载：用原版的原生流程（{@code ClientboundRespawnPacket}
     * flags=0 + {@code sendLevelInfo} + 区块重跟踪）让全体在线客户端平滑重载世界与地形。
     *
     * <p>只在切片回滚这条路上调用（专用服务器 / 局域网上的服务端路径）；单人过渡路径不调，
     * 那边由客户端的过渡与快速重开负责。
     */
    default void reloadClientsAfterRollback(Object server) {
    }
}
