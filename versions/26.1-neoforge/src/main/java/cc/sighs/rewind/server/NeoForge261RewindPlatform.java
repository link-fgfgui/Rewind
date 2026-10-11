package cc.sighs.rewind.server;

import java.nio.file.Path;
import cc.sighs.rewind.common.spi.EncodedSector;
import cc.sighs.rewind.common.spi.FlushOutcome;
import cc.sighs.rewind.common.spi.RewindPlatform;
import cc.sighs.rewind.common.spi.RollbackOutcome;
import cc.sighs.rewind.common.spi.StorageKind;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.RegionFileVersion;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.fml.loading.FMLLoader;

/**
 * NeoForge 26.1 的 {@link RewindPlatform} 实现：把 common 需要知道的全部平台能力接进来。
 *
 * <p>这里是「版本差异」的唯一落点——common 侧的建点 / 查询 / 索引编排完全不知道自己在哪个
 * Minecraft 版本上跑。换一个版本时，改的就是这个类加上 {@code cc.sighs.mixin} 与 {@code client} 包。
 *
 * <p>参数在入口处统一转型：common 只把服务器对象当不透明句柄，传回来的就是这里的 {@link MinecraftServer}。
 *
 * <p>原地回滚（{@code InPlaceRollback}）已在 26.1 上接通：{@link #supportsInPlaceRollback()} 返回
 * {@code true}，{@link #rollbackInPlace} 走 26.1 版的引擎（区块卸载 / 重载与玩家、时间天气、
 * 存档数据的内存回灌都按 26.1 的原版内部结构重写过）。
 */
public final class NeoForge261RewindPlatform implements RewindPlatform {
    @Override
    public String modVersion() {
        return RewindVersion.of();
    }

    @Override
    public Path worldRoot(Object server) {
        return server(server).getWorldPath(LevelResource.LEVEL_DATA_FILE).getParent();
    }

    @Override
    public boolean isSameThread(Object server) {
        return server(server).isSameThread();
    }

    @Override
    public boolean isDedicatedServer(Object server) {
        return server(server).isDedicatedServer();
    }

    @Override
    public boolean isPublished(Object server) {
        return server(server).isPublished();
    }

    @Override
    public boolean hasPlayers(Object server) {
        return !server(server).getPlayerList().getPlayers().isEmpty();
    }

    @Override
    public FlushOutcome flushForCheckpoint(Object server, String slot, String source) {
        WorldFlush.Result flushed = WorldFlush.flush(server(server), slot, source);
        return new FlushOutcome(flushed.worldRoot, flushed.meta, flushed.inventory);
    }

    /**
     * 26.1 的原地回滚引擎已经接通（见 {@link InPlaceRollback}）。
     *
     * <p>返回 true 之后 {@code RewindApi.rollbackInPlace} 会真的在活着的服务器里把世界倒回去，
     * 客户端也不再回退到「关世界 → 覆盖 → 重开」那条路。
     */
    @Override
    public boolean supportsInPlaceRollback() {
        return true;
    }

    @Override
    public RollbackOutcome rollbackInPlace(Object server, Path worldRoot, String slot) throws Exception {
        InPlaceRollback.Result rolled = InPlaceRollback.run(server(server), worldRoot, slot);
        return new RollbackOutcome(rolled.summary(), rolled.mirrorCopied, rolled.mirrorSkipped,
                rolled.mirrorFiles, rolled.totalMs);
    }

    /**
     * 某个模组的类加载器：NeoForge 的模组是 JPMS 模块，模块名就是 modid。26.1 的 FML 把
     * {@code getGameLayer()} 改成了实例方法，所以先拿 {@code FMLLoader.getCurrentOrNull()}。
     * 跨模组反射要用它，见 {@code cc.sighs.rewind.common.compat.SophisticatedCoreCompat}。
     */
    @Override
    public ClassLoader modClassLoader(String modId) {
        try {
            FMLLoader fml = FMLLoader.getCurrentOrNull();
            ModuleLayer layer = fml == null ? null : fml.getGameLayer();
            return layer == null ? null : layer.findLoader(modId);
        } catch (Throwable t) {
            return null;
        }
    }

    private static MinecraftServer server(Object server) {
        return (MinecraftServer) server;
    }

    /**
     * 写账本 NBT → 扇区负载：DEFLATE 压缩（解码器按头版本分派，重压成 DEFLATE 依然合法）。
     *
     * <p>与原版 {@code RegionFileStorage.write} 的压缩路径同构（{@code NbtIo.write} 进
     * 版本包装流），只是不经过文件。要求入参是纯数据（调用方已 {@code copy()} 过）。
     */
    @Override
    public EncodedSector encodeChunkSector(Object tag) {
        if (!(tag instanceof CompoundTag)) {
            return null;
        }
        try {
            java.io.ByteArrayOutputStream raw = new java.io.ByteArrayOutputStream(8192);
            try (java.io.DataOutputStream out =
                    new java.io.DataOutputStream(RegionFileVersion.VERSION_DEFLATE.wrap(raw))) {
                NbtIo.write((CompoundTag) tag, out);
            }
            return new EncodedSector(RegionFileVersion.VERSION_DEFLATE.getId(), raw.toByteArray());
        } catch (java.io.IOException e) {
            return null;
        }
    }

    /** 扇区负载字节 → 写账本句柄（回滚灌装用）。 */
    @Override
    public Object decodeChunkSector(byte[] uncompressed) {
        if (uncompressed == null) {
            return null;
        }
        try (java.io.DataInputStream in = new java.io.DataInputStream(new java.io.ByteArrayInputStream(uncompressed))) {
            return NbtIo.read(in);
        } catch (java.io.IOException e) {
            return null;
        }
    }

    /**
     * （种类，维度）→ region 目录相对路径。26.1 的维度存储目录由
     * {@code DimensionType.getStorageFolder} 决定：{@code dimensions/<命名空间>/<路径>/}，
     * 下面再挂 {@code region/}、{@code entities/}、{@code poi/}。
     * 维度 id 不是 {@code <命名空间>:<路径>} 形状就返回 null
     * （增量建点对这些条目回退磁盘扫描，绝不硬猜）。
     */
    @Override
    public String chunkDirRelative(StorageKind kind, String dimensionId) {
        String leaf;
        switch (kind) {
            case CHUNK:
                leaf = "region";
                break;
            case ENTITY:
                leaf = "entities";
                break;
            case POI:
                leaf = "poi";
                break;
            default:
                return null;
        }
        int colon = dimensionId.indexOf(':');
        if (colon <= 0 || colon == dimensionId.length() - 1) {
            return null;
        }
        String namespace = dimensionId.substring(0, colon);
        String path = dimensionId.substring(colon + 1);
        return "dimensions/" + namespace + "/" + path + "/" + leaf;
    }

    /**
     * 26.1 打包布局以 {@code ChunkPos.unpack} 为准（与旧 `toLong` 同为 x 低 / z 高，
     * 但构造器已删，必须走官方逆运算，硬猜位移在未来版本会错位）。
     */
    @Override
    public int[] unpackChunkPos(long packed) {
        ChunkPos pos = ChunkPos.unpack(packed);
        return new int[]{pos.x(), pos.z()};
    }
}
