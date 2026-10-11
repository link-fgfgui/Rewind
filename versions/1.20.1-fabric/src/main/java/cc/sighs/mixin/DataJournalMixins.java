package cc.sighs.mixin;

import java.io.File;
import java.util.Map;
import java.util.function.Function;
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
 *   <li>{@code DimensionDataStorage.get(Function, String)}：**必须挂这一层**——原版
 *       {@code readSavedData} 先做 {@code if (file1.exists())} 预检，快照有而盘上无文件时
 *       提前返回 null，底层 {@code readTagFromDisk} 根本不会触发。命中时喂快照拷贝、
 *       用传入的 factory 反序列化（1.20.1 是 {@code Function<CompoundTag,T>}，
 *       {@code DimensionDataStorage} 没有 {@code registries} 字段）并把实例回填缓存。</li>
 *   <li>{@code DimensionDataStorage.readTagFromDisk(String,int)}：
 *       底层直读同样短路（防已有实例绕过 {@code get} 直接调它，如
 *       {@code LegacyStructureDataHandler}）：命中喂快照拷贝；删除标记抛
 *       {@code IOException}（上层 {@code readSavedData} 抓到后按“文件不存在”处理，
 *       与它同构；<b>严禁包成 RuntimeException</b>，{@code LegacyStructureDataHandler}
 *       只抓 {@code IOException}）。</li>
 *   <li>{@code PlayerDataStorage.save(Player):33} 同步写：调用线程直接拿到 NBT 引用，
 *       拷一份即走。{@code load(Player)} 读拦截喂快照（1.20.1 的返回是
 *       {@code CompoundTag}、缺席即 null，删除标记喂 null 即“不存在”语义；原版内部会调
 *       {@code player.load}，短路时一并补上）。</li>
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
     * 存档数据读拦截：高层与底层两个入口都短路，命中喂快照。
     *
     * <p>高层 {@code get(Function, String)} 挂在磁盘预检之前——快照有但盘上无文件时，
     * 原版 {@code readSavedData} 会因 {@code file1.exists() == false} 提前返回 null、
     * 底层根本不触发，只挂底层会漏掉这类快照数据（1.20.1-forge 同款挂法）。
     * 反序列化用传进来的单参数 factory（无 registries 依赖），顺手把实例回填缓存
     * （与原版 {@code get} 的缓存语义一致）。
     *
     * <p>底层 {@code readTagFromDisk} 同样短路（防已有实例绕过 {@code get} 直接调它）：
     * 命中喂快照拷贝；删除标记抛 {@code IOException}（上层 {@code readSavedData} 与
     * {@code LegacyStructureDataHandler} 都只抓 {@code IOException}，与“文件不存在”同构；
     * <b>严禁包成 RuntimeException</b>，否则后者抓不住）。
     */
    @Mixin(DimensionDataStorage.class)
    public abstract static class DimensionDataStorageCover {
        @Inject(method = "get(Ljava/util/function/Function;Ljava/lang/String;)Lnet/minecraft/world/level/saveddata/SavedData;",
                at = @At("HEAD"),
                cancellable = true)
        private <T extends SavedData> void rewind$serveSavedData(Function<CompoundTag, T> loadFunction,
                String name, CallbackInfoReturnable<T> cir) {
            try {
                if (RewindState.isDiscarding() || !RewindState.isStorageCoverArmed()) {
                    return;
                }
                DimensionDataStorageAccess access = (DimensionDataStorageAccess) (Object) this;
                String key = savedataKey(access.rewind$dataFolder(), name);
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
                T instance = loadFunction.apply(full.getCompound("data"));
                cache.put(name, instance);
                cir.setReturnValue(instance);
            } catch (Throwable ignored) {
                // 拦截失败就当没拦住，走原版读盘
            }
        }

        @Inject(method = "readTagFromDisk(Ljava/lang/String;I)Lnet/minecraft/nbt/CompoundTag;",
                at = @At("HEAD"),
                cancellable = true)
        private void rewind$serveTagFromDisk(String filename, int version,
                CallbackInfoReturnable<CompoundTag> cir) throws java.io.IOException {
            if (RewindState.isDiscarding() || !RewindState.isStorageCoverArmed()) {
                return;
            }
            try {
                DimensionDataStorageAccess access = (DimensionDataStorageAccess) (Object) this;
                String key = savedataKey(access.rewind$dataFolder(), filename);
                if (!RewindState.dataCover().covers(key)) {
                    return;
                }
                Object tag = RewindState.dataCover().serve(key);
                if (tag == null) {
                    // 不能包成 RuntimeException：LegacyStructureDataHandler 只抓 IOException
                    throw new java.io.IOException("snapshot has no saved data: " + filename);
                }
                cir.setReturnValue(((CompoundTag) tag).copy());
            } catch (java.io.IOException e) {
                throw e;
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
                CompoundTag copy = ((CompoundTag) tag).copy();
                // 原版 load 自己调 player.load()：短路时补上，语义一致
                player.load(copy);
                cir.setReturnValue(copy);
            } catch (Throwable ignored) {
                // 拦截失败就当没拦住
            }
        }
    }
}
