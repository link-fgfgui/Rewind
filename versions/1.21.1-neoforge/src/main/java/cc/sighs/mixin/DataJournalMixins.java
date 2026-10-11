package cc.sighs.mixin;

import java.io.File;
import java.util.Map;
import java.util.Optional;
import cc.sighs.mixin.RollbackAccessMixins.DimensionDataStorageAccess;
import cc.sighs.rewind.common.RewindState;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.storage.DimensionDataStorage;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.minecraft.world.level.storage.PlayerDataStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 非区块 NBT 的旁路监听与稀疏读拦截（SavedData / 玩家数据 / level.dat）。
 *
 * <p>写侧只听不拦：原版落盘流程原样放行，这里只把 NBT 拷一份登记进
 * {@link RewindState#dataCover()}（精确变更集，给时间线与回滚对账用）。
 * 读侧只在覆盖武装窗口内短路：命中喂快照，未命中不作任何操作直接返回
 * （严禁返回 null 冒充正常值）。
 *
 * <p>挂点选择（行号为 1.21.1 基准）：
 * <ul>
 *   <li>{@code SavedData.save(File, Provider):36}：只在 {@code isDirty()} 时写，
 *       NBT 副本在分派入线程池前于调用线程同步获取。实现是“再调一次”
 *       {@code save(CompoundTag, Provider)}——要求实现是纯写函数（与 26.1
 *       supplier 求值的纯度假设一致，原版传的都是这类函数）；取到即拷走，
 *       原版随后照常走自己的那份。</li>
 *   <li>{@code DimensionDataStorage.get:55 / readSavedData:66}：在
 *       {@code readSavedData:69 if (file1.exists())} 磁盘预检之前挂载——
 *       快照有但盘上无文件时，直挂底层 {@code readTagFromDisk:84} 根本不会触发，
 *       必须挂高层。{@code LegacyStructureDataHandler:178} 直接调底层，
 *       挂高层与它互不干扰。</li>
 *   <li>{@code PlayerDataStorage.save:33} 同步写：调用线程直接拿到 NBT 引用，
 *       拷一份即走。{@code load:74} 读拦截喂快照。</li>
 *   <li>{@code LevelStorageSource$LevelStorageAccess#saveLevelData:519} 私有方法：
 *       入参即要写的完整 {@code level.dat} NBT，直接拷一份。回滚走内存
 *       WorldData，不读盘，故无读拦截。</li>
 * </ul>
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
        @Inject(method = "save(Ljava/io/File;Lnet/minecraft/core/HolderLookup$Provider;)V",
                at = @At("HEAD"))
        private void rewind$recordSavedData(File file, HolderLookup.Provider registries, CallbackInfo ci) {
            try {
                SavedData self = (SavedData) (Object) this;
                if (!self.isDirty() || file == null) {
                    return;
                }
                CompoundTag data = self.save(new CompoundTag(), registries);
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
     * 存档数据读拦截：在磁盘预检之前挂载，命中喂快照实例（含“快照有、盘上无”的情形）。
     *
     * <p>顺手把实例回填缓存（与原版 {@code get} 的缓存语义一致），之后同 tick 的访问不再拦截。
     */
    @Mixin(DimensionDataStorage.class)
    public abstract static class DimensionDataStorageCover {
        @Inject(method = "get(Lnet/minecraft/world/level/saveddata/SavedData$Factory;Ljava/lang/String;)Lnet/minecraft/world/level/saveddata/SavedData;",
                at = @At("HEAD"),
                cancellable = true)
        private <T extends SavedData> void rewind$serveSavedData(SavedData.Factory<T> factory, String name,
                CallbackInfoReturnable<T> cir) {
            try {
                if (RewindState.isDiscarding() || !RewindState.isStorageCoverArmed()) {
                    return;
                }
                DimensionDataStorageAccess access = (DimensionDataStorageAccess) (Object) this;
                File folder = access.rewind$dataFolder();
                String key = savedataKey(folder, name);
                if (!RewindState.dataCover().covers(key)) {
                    return;
                }
                Object tag = RewindState.dataCover().serve(key);
                Map<String, SavedData> cache = access.rewind$cache();
                if (tag == null) {
                    cache.put(name, null);
                    cir.setReturnValue(null);
                    return;
                }
                CompoundTag full = ((CompoundTag) tag).copy();
                T instance = factory.deserializer().apply(full.getCompound("data"), access.rewind$registries());
                cache.put(name, instance);
                cir.setReturnValue(instance);
            } catch (Throwable ignored) {
                // 拦截失败就当没拦住，走原版读盘
            }
        }

        /**
         * 底层直读同样短路（防已有实例绕过 {@code get} 直接调它）：命中喂快照拷贝，
         * 删除标记抛 {@code IOException}（上层 {@code readSavedData} 抓到后返回 null，
         * 与“文件不存在”同构）。
         */
        @Inject(method = "readTagFromDisk(Ljava/lang/String;Lnet/minecraft/util/datafix/DataFixTypes;I)Lnet/minecraft/nbt/CompoundTag;",
                at = @At("HEAD"),
                cancellable = true)
        private void rewind$serveTagFromDisk(String filename, DataFixTypes dataFixType, int version,
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

        @Inject(method = "load(Lnet/minecraft/world/entity/player/Player;)Ljava/util/Optional;",
                at = @At("HEAD"),
                cancellable = true)
        private void rewind$servePlayer(Player player, CallbackInfoReturnable<Optional<CompoundTag>> cir) {
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
                    cir.setReturnValue(Optional.empty());
                    return;
                }
                cir.setReturnValue(Optional.of(((CompoundTag) tag).copy()));
            } catch (Throwable ignored) {
                // 拦截失败就当没拦住
            }
        }
    }

    /** level.dat 写监听：入参即完整 NBT，拷一份即走（回滚走内存 WorldData，无读拦截）。 */
    @Mixin(LevelStorageSource.LevelStorageAccess.class)
    public abstract static class LevelDataJournal {
        @Inject(method = "saveLevelData(Lnet/minecraft/nbt/CompoundTag;)V", at = @At("HEAD"))
        private void rewind$recordLevelData(CompoundTag tag, CallbackInfo ci) {
            try {
                if (tag != null) {
                    RewindState.dataCover().onWritten("leveldat", tag.copy());
                }
            } catch (Throwable ignored) {
                // 监听绝不能污染原版写盘
            }
        }
    }
}
