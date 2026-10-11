package cc.sighs.rewind.server;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import cc.sighs.rewind.common.spi.EncodedSector;
import cc.sighs.rewind.common.spi.FlushOutcome;
import cc.sighs.rewind.common.spi.RewindPlatform;
import cc.sighs.rewind.common.spi.RollbackOutcome;
import cc.sighs.rewind.common.spi.StorageKind;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.chunk.storage.RegionFileVersion;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraftforge.fml.loading.FMLLoader;

/**
 * Forge 1.20.1 的 {@link RewindPlatform} 实现：把 common 需要知道的全部平台能力接进来。
 *
 * <p>这里是「版本差异」的唯一落点——common 侧的建点 / 查询 / 索引编排完全不知道自己在哪个
 * Minecraft 版本上跑。换一个版本时，改的就是这个类加上 {@code cc.sighs.mixin} 与 {@code client} 包。
 *
 * <p>参数在入口处统一转型：common 只把服务器对象当不透明句柄，传回来的就是这里的 {@link MinecraftServer}。
 *
 * <p>与 NeoForge 1.21.1 那份在能力上完全对齐，包括原地回滚：{@link InPlaceRollback} 已按 1.20.1 的
 * 原版内部结构移植（只有写法上的替换，顺序与保护条件一字未动，差别逐条写在那个类的类注释里），
 * 支撑它的原版内部入口在 {@code cc.sighs.mixin.RollbackAccessMixins}，
 * 回滚窗口内的写盘拦截在 {@code cc.sighs.mixin.RollbackDiscardMixins}。
 */
public final class ForgeRewindPlatform implements RewindPlatform {
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
     * 某个模组的类加载器：Forge / NeoForge 的模组都是 JPMS 模块，模块名就是 modid（崩溃报告里那串
     * {@code TRANSFORMER/rewind@1.0.0} 就是它的模块名）。跨模组反射要用它，见
     * {@code cc.sighs.rewind.common.compat.SophisticatedCoreCompat}——common 自己那个加载器看不见别的模组。
     */
    @Override
    public ClassLoader modClassLoader(String modId) {
        try {
            ModuleLayer layer = FMLLoader.getGameLayer();
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
            ByteArrayOutputStream raw = new ByteArrayOutputStream(8192);
            try (DataOutputStream out =
                    new DataOutputStream(RegionFileVersion.VERSION_DEFLATE.wrap(raw))) {
                NbtIo.write((CompoundTag) tag, out);
            }
            return new EncodedSector(RegionFileVersion.VERSION_DEFLATE.getId(), raw.toByteArray());
        } catch (IOException e) {
            return null;
        }
    }

    /** 扇区负载字节 → 写账本句柄（回滚灌装用）。 */
    @Override
    public Object decodeChunkSector(byte[] uncompressed) {
        if (uncompressed == null) {
            return null;
        }
        try (DataInputStream in = new DataInputStream(new java.io.ByteArrayInputStream(uncompressed))) {
            return NbtIo.read(in);
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * （种类，维度）→ region 目录相对路径。1.20.1 的维度存储目录惯例与 1.21.1 一致：
     * 主世界直接是 {@code region/}，下界/末地是 {@code DIM-1/region/}、{@code DIM1/region/}；
     * 实体/POI 同理。模组自定义维度若用了别的布局，存档文件夹惯例对不上就返回 null
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
        if ("minecraft:overworld".equals(dimensionId)) {
            return leaf;
        }
        if ("minecraft:the_nether".equals(dimensionId)) {
            return "DIM-1/" + leaf;
        }
        if ("minecraft:the_end".equals(dimensionId)) {
            return "DIM1/" + leaf;
        }
        // 自定义维度（数据包 / 模组）：原版目录规范 dimensions/<ns>/<path>/，path 段可带 `/`
        int colon = dimensionId.indexOf(':');
        if (colon <= 0 || colon == dimensionId.length() - 1) {
            return null;
        }
        return "dimensions/" + dimensionId.substring(0, colon) + "/" + dimensionId.substring(colon + 1) + "/" + leaf;
    }
}
