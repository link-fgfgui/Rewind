package cc.sighs.mixin;

import java.io.File;
import cc.sighs.mixin.RollbackAccessMixins.DimensionDataStorageAccess;
import cc.sighs.rewind.common.RewindState;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.storage.DimensionDataStorage;
import net.minecraft.world.level.storage.PlayerDataStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 非区块 NBT 的旁路监听与稀疏读拦截（SavedData / 玩家数据）。
 *
 * <p>写侧只听不拦：原版落盘流程原样放行，这里只把 NBT 拷一份登记进
 * {@link RewindState#dataCover()}（精确变更集，给时间线与回滚对账用）。
 * 读侧只在覆盖武装窗口内短路：命中喂快照，未命中不作任何操作直接返回
 * （严禁返回 null 冒充正常值）。
 *
 * <p>挂点选择（1.20.1 基准，反编译逐个核对过）：
 * <ul>
 *   <li>{@code SavedData.save(File):35}：只在 {@code isDirty()} 时写，
 *       NBT 副本在返回前于调用线程同步获取。实现是“再调一次”
 *       {@code save(CompoundTag)}——要求实现是纯写函数（1.20.1 的签名是单参数的，
 *       没有 {@code HolderLookup.Provider}）；取到即拷走，原版随后照常走自己的那份。</li>
 *   <li>{@code DimensionDataStorage.readTagFromDisk(String,int)}：
 *       1.20.1 的 {@code get(Function,String)} → {@code readSavedData} →
 *       {@code readTagFromDisk} 是一条单漏斗（{@code readSavedData} 抓 {@code Exception}
 *       返回 null），所以只拦这一层就够了：命中喂快照拷贝，反序列化交给原版自己的
 *       factory（1.20.1 的 factory 是 {@code Function<CompoundTag,T>}，不需要 registries——
 *       1.20.1 的 {@code DimensionDataStorage} 根本没有 {@code registries} 字段，
 *       这里不做反序列化）。{@code LegacyStructureDataHandler} 直接调底层，
 *       挂这里与它同构、无干扰。</li>
 *   <li>{@code PlayerDataStorage.save(Player):33} 同步写：调用线程直接拿到 NBT 引用，
 *       拷一份即走。{@code load(Player)} 读拦截喂快照（1.20.1 的返回是
 *       {@code CompoundTag}、缺席即 null，所以删除标记喂 null 即“不存在”语义）。</li>
 * </ul>
 *
 * <p>没有 level.dat 写监听：1.20.1 的 {@code LevelStorageAccess} 里不存在私有的
 * {@code saveLevelData(CompoundTag)}（只有公开的 {@code saveDataTag(RegistryAccess,WorldData[,CompoundTag])}，
 * 它的 {@code CompoundTag} 参数是玩家 NBT、不是完整 level.dat，硬拦会记错账），
 * 所以宁可不监听。level.dat 回滚走 {@code InPlaceRollback.restoreWorldData} 的纯内存路径，
 * 不依赖覆盖层——行为与改前一致。
 */
public final class DataJournalMixins {
    private DataJournalMixins() {
    }

    /** 存档数据覆盖键：写侧（保存文件）与读侧（数据目录 + id）拼出同一把键。 */
    public static String savedataKey(File file) {
        return "savedata|" + file.toPath().toAbsolutePath().normalize().toString();
    }

    public static String savedataKey(File dataFolder, String id) {
        return "savedata|" + new File(dataFolder, id + ".dat").toPath().toAbsolutePath().normalize().toString();
    }

    public static String playerKey(String uuid) {
        return "player|" + uuid;
    }

    /** {@code <维度>/data/*.dat} 的实际写盘点：同步拷一份 NBT 即走。 */
    @Mixin(SavedData.class)
    public abstract static class SavedDataJournal {
        @Inject(method = "save(Ljava/io/File;)V",
                at = @At("HEAD"))
        private void rewind$recordSavedData(File file, CallbackInfo ci) {
            try {
                SavedData self = (SavedData) (Object) this;
                if (!self.isDirty() || file == null) {
                    return;
                }
                CompoundTag data = self.save(new CompoundTag());
                CompoundTag full = new CompoundTag();
                full.put("data", data);
                NbtUtils.addCurrentDataVersion(full);
                RewindState.dataCover().onWritten(savedataKey(file), full.copy());
            } catch (Throwable ignored) {
                // 监听绝不能污染原版写盘
            }
        }
    }

    /**
     * 存档数据读拦截：在唯一的磁盘漏斗处短路，命中喂快照拷贝。
     *
     * <p>反序列化交给原版 {@code readSavedData} 自己的 factory（单参数
     * {@code Function<CompoundTag,T>}，无 registries 依赖）；删除标记抛
     * {@code IOException}（上层 {@code readSavedData} 抓到后返回 null，
     * 与“文件不存在”同构，顺带把缓存记成“无”）。
     */
    @Mixin(DimensionDataStorage.class)
    public abstract static class DimensionDataStorageCover {
        @Inject(method = "readTagFromDisk(Ljava/lang/String;I)Lnet/minecraft/nbt/CompoundTag;",
                at = @At("HEAD"),
                cancellable = true)
        private void rewind$serveTagFromDisk(String filename, int version,
                CallbackInfoReturnable<CompoundTag> cir) {
            try {
                if (RewindState.isDiscarding() || !RewindState.isStorageCoverArmed()) {
                    return;
                }
                DimensionDataStorageAccess access = (DimensionDataStorageAccess) (Object) this;
                String key = savedataKey(access.rewind$dataFolder(), filename);
                if (!RewindState.dataCover().covers(key)) {
                    return;
                }
                Object tag = RewindState.dataCover().serve(key);
                if (tag == null) {
                    cir.cancel();
                    throw new java.io.IOException("snapshot has no saved data: " + filename);
                }
                cir.setReturnValue(((CompoundTag) tag).copy());
            } catch (java.io.IOException e) {
                throw new RuntimeException(e);
            } catch (Throwable ignored) {
                // 非 IO 异常：当没拦住
            }
        }
    }

    /** 玩家数据：写侧同步拷一份，读侧武装窗口内喂快照。 */
    @Mixin(PlayerDataStorage.class)
    public abstract static class PlayerDataJournal {
        @Inject(method = "save(Lnet/minecraft/world/entity/player/Player;)V", at = @At("HEAD"))
        private void rewind$recordPlayer(Player player, CallbackInfo ci) {
            try {
                CompoundTag tag = player.saveWithoutId(new CompoundTag());
                RewindState.dataCover().onWritten(playerKey(player.getStringUUID()), tag.copy());
            } catch (Throwable ignored) {
                // 监听绝不能污染原版写盘
            }
        }

        @Inject(method = "load(Lnet/minecraft/world/entity/player/Player;)Lnet/minecraft/nbt/CompoundTag;",
                at = @At("HEAD"),
                cancellable = true)
        private void rewind$servePlayer(Player player, CallbackInfoReturnable<CompoundTag> cir) {
            try {
                if (RewindState.isDiscarding() || !RewindState.isStorageCoverArmed()) {
                    return;
                }
                String key = playerKey(player.getStringUUID());
                if (!RewindState.dataCover().covers(key)) {
                    return;
                }
                Object tag = RewindState.dataCover().serve(key);
                if (tag == null) {
                    // 1.20.1 的 load 缺席即返回 null：删除标记喂 null 就是“不存在”语义
                    cir.setReturnValue(null);
                    return;
                }
                cir.setReturnValue(((CompoundTag) tag).copy());
            } catch (Throwable ignored) {
                // 拦截失败就当没拦住
            }
        }
    }
}
