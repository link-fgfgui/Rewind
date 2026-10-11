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
 * 非区块 NBT 的旁路监听与稀疏读拦截（SavedData / 玩家数据 / level.dat）。
 *
 * <p>写侧只听不拦：原版落盘流程原样放行，这里只把 NBT 拷一份登记进
 * {@link RewindState#dataCover()}（精确变更集，给时间线与回滚对账用）。
 * 读侧只在覆盖武装窗口内短路：命中喂快照，未命中不作任何操作直接返回
 * （严禁返回 null 冒充正常值）。
 *
 * <p>挂点选择（1.20.1 基准，反编译核过）：
 * <ul>
 *   <li>{@code SavedData.save(File)}：只在 {@code isDirty()} 时写，
 *       NBT 副本在调用线程同步获取。实现是“再调一次”
 *       {@code save(CompoundTag)}——要求实现是纯写函数（原版传的都是这类函数）；
 *       取到即拷走，原版随后照常走自己的那份。1.20.1 还没有
 *       {@code HolderLookup.Provider} 参数，签名就是 {@code save(File)}。</li>
 *   <li>{@code DimensionDataStorage.get(Function, String)}：在磁盘预检之前挂载——
 *       快照有但盘上无文件时，直挂底层 {@code readTagFromDisk} 根本不会触发，
 *       必须挂高层。顺手把实例回填缓存（与原版 {@code get} 的缓存语义一致）。
 *       1.20.1 的 {@code DimensionDataStorage} 只有
 *       {@code cache / fixerUpper / dataFolder} 三个字段，没有 {@code registries}；
 *       反序列化直接用传进来的 {@code Function<CompoundTag, T>}，不需要 Provider。</li>
 *   <li>{@code DimensionDataStorage.readTagFromDisk(String, int)}：
 *       底层直读同样短路（防已有实例绕过 {@code get} 直接调它）：命中喂快照拷贝，
 *       删除标记抛 {@code IOException}（上层 {@code readSavedData} 与
 *       {@code LegacyStructureDataHandler} 都抓 {@code IOException}，与“文件不存在”同构；
 *       特别注意不能包成 {@code RuntimeException}，否则后者抓不住）。</li>
 *   <li>{@code PlayerDataStorage.save(Player)} 同步写：调用线程直接拿到 NBT 引用，
 *       拷一份即走。{@code load(Player)} 读拦截喂快照：1.20.1 的 {@code load} 返回
 *       {@code @Nullable CompoundTag}（不是 {@code Optional}），而且原版自己会在里面
 *       调 {@code player.load(...)}——短路时把这一步也补上，语义与原版一致；
 *       删除标记返回 {@code null}（与“文件不存在”同构）。</li>
 *   <li>level.dat 写监听：1.20.1 的 {@code LevelStorageAccess} 里<b>没有</b>
 *       私有的 {@code saveLevelData(CompoundTag)}（反编译确认过），唯一的写入口是
 *       公开的 {@code saveDataTag(RegistryAccess, WorldData, CompoundTag)}。
 *       在它的 TAIL 挂监听需要重读刚落盘的文件——读快照目录不允许（此时覆盖还没灌好），
 *       读活文件违反 0 读，所以这里<b>故意不监听 level.dat 写</b>，只留这个说明。
 *       时间天气回滚走纯内存（{@code InPlaceRollback.restoreWorldData}），不依赖写监听。</li>
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
        @Inject(method = "save(Ljava/io/File;)V", at = @At("HEAD"))
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
     * 存档数据读拦截：在磁盘预检之前挂载，命中喂快照实例（含“快照有、盘上无”的情形）。
     *
     * <p>顺手把实例回填缓存（与原版 {@code get} 的缓存语义一致），之后同 tick 的访问不再拦截。
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
                T instance = loadFunction.apply(full.getCompound("data"));
                cache.put(name, instance);
                cir.setReturnValue(instance);
            } catch (Throwable ignored) {
                // 拦截失败就当没拦住，走原版读盘
            }
        }

        /**
         * 底层直读同样短路（防已有实例绕过 {@code get} 直接调它）：命中喂快照拷贝，
         * 删除标记抛 {@code IOException}（上层抓到后按“文件不存在”处理）。
         */
        @Inject(method = "readTagFromDisk(Ljava/lang/String;I)Lnet/minecraft/nbt/CompoundTag;",
                at = @At("HEAD"),
                cancellable = true)
        private void rewind$serveTagFromDisk(String name, int version,
                CallbackInfoReturnable<CompoundTag> cir) throws java.io.IOException {
            if (RewindState.isDiscarding() || !RewindState.isStorageCoverArmed()) {
                return;
            }
            try {
                DimensionDataStorageAccess access = (DimensionDataStorageAccess) (Object) this;
                String key = savedataKey(access.rewind$dataFolder(), name);
                if (!RewindState.dataCover().covers(key)) {
                    return;
                }
                Object tag = RewindState.dataCover().serve(key);
                if (tag == null) {
                    // 不能包成 RuntimeException：LegacyStructureDataHandler 只抓 IOException
                    throw new java.io.IOException("snapshot has no saved data: " + name);
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

    /**
     * level.dat 写监听：1.20.1 无私有 {@code saveLevelData(CompoundTag)}，故意留空。
     *
     * <p>挂 {@code saveDataTag} 的 TAIL 需要重读刚落盘的文件（违反 0 读），
     * 所以不监听；时间天气回滚走纯内存。保留这个空类只为占住注册位，
     * 说明“不是漏了，是查过原版之后决定不挂”。
     */
    @Mixin(net.minecraft.world.level.storage.LevelStorageSource.LevelStorageAccess.class)
    public abstract static class LevelDataJournal {
    }
}
