package cc.sighs.mixin;

import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import cc.sighs.mixin.RollbackAccessMixins.SavedDataStorageAccess;
import cc.sighs.rewind.common.JournalKeys;
import cc.sighs.rewind.common.RewindState;
import com.mojang.serialization.Codec;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.players.NameAndId;
import net.minecraft.util.ProblemReporter;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.minecraft.world.level.storage.PlayerDataStorage;
import net.minecraft.world.level.storage.SavedDataStorage;
import net.minecraft.world.level.storage.TagValueOutput;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 非区块 NBT 的旁路监听与稀疏读拦截（SavedData / 玩家数据 / level.dat，NeoForge 26.1 版）。
 *
 * <p>写侧只听不拦：原版落盘流程原样放行，这里只把 NBT 拷一份登记进
 * {@link RewindState#dataCover()}（精确变更集，给时间线与回滚对账用）。
 * 读侧只在覆盖武装窗口内短路：命中喂快照，未命中不作任何操作直接返回
 * （严禁返回 null 冒充正常值）。
 *
 * <h2>26.1 的挂点变化（相对 1.21.1 那份）</h2>
 * <ul>
 *   <li>{@code SavedData.save(File, Provider)} 已删除——存档数据写盘的唯一汇点是
 *       {@code SavedDataStorage}：{@code scheduleSave()} 收集脏条目，{@code tryWrite} 在 ioPool
 *       线程上逐个落盘。这里挂在私有的 {@code tryWrite(SavedDataType, CompoundTag)} 上：
 *       入参即该文件的完整 NBT（含 {@code data} 与数据版本），拷一份即走；
 *       键按 {@code getDataFile(type.id())} 的绝对路径拼，与读拦截的键一致。</li>
 *   <li>读拦截挂两层：高层的 {@code get(SavedDataType)}（在磁盘预检之前挂载——
 *       快照有但盘上无文件时，只挂 {@code readTagFromDisk} 根本不会触发）与底层的
 *       {@code readTagFromDisk(Path, DataFixTypes, int)}（防已有实例绕过 {@code get} 直接调它）。
 *       {@code get} 命中时按原版 {@code readSavedData} 同款解码（同一份 level / registries），
 *       并顺手回填缓存（与原版 {@code get} 的缓存语义一致）。</li>
 *   <li>{@code PlayerDataStorage.save(Player)} 签名不变，但 NBT 改走 {@code ValueOutput}：
 *       {@code player.saveWithoutId} 现在吃 {@code TagValueOutput} 而不是 {@code CompoundTag}。
 *       读拦截挂 {@code load(NameAndId)}（26.1 按 {@code NameAndId} 而不是 {@code Player} 索引，
 *       键用其中的 uuid，与写侧 {@code getStringUUID()} 同一把）。</li>
 *   <li>{@code LevelStorageSource$LevelStorageAccess#saveLevelData(CompoundTag)} 私有方法：
 *       入参即要写的完整 {@code level.dat} NBT，直接拷一份。回滚走内存
 *       WorldData，不读盘，故无读拦截。</li>
 * </ul>
 */
public final class DataJournalMixins {
    private static final Logger LOGGER = LoggerFactory.getLogger("rewind");

    private DataJournalMixins() {
    }

    /**
     * {@code .dat} 的实际写盘点：{@code scheduleSave()} 收集完脏条目后逐个调它（ioPool 线程）。
     * 同步拷一份 NBT 即走；脏过滤上游已经做过，这里不再判 {@code isDirty()}。
     */
    @Mixin(SavedDataStorage.class)
    public abstract static class SavedDataJournal {
        @Inject(method = "tryWrite(Lnet/minecraft/world/level/saveddata/SavedDataType;Lnet/minecraft/nbt/CompoundTag;)V",
                at = @At("HEAD"))
        private void rewind$recordSavedData(SavedDataType<?> type, CompoundTag tag, CallbackInfo ci) {
            try {
                if (type == null || tag == null) {
                    return;
                }
                SavedDataStorageAccess access = (SavedDataStorageAccess) (Object) this;
                Path file = access.rewind$getDataFile(type.id());
                RewindState.dataCover().onWritten(JournalKeys.savedataKey(file), tag.copy());
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
    @Mixin(SavedDataStorage.class)
    public abstract static class SavedDataStorageCover {
        @Inject(method = "get(Lnet/minecraft/world/level/saveddata/SavedDataType;)Lnet/minecraft/world/level/saveddata/SavedData;",
                at = @At("HEAD"),
                cancellable = true)
        private <T extends SavedData> void rewind$serveSavedData(SavedDataType<T> type,
                CallbackInfoReturnable<T> cir) {
            try {
                if (RewindState.isDiscarding() || !RewindState.isStorageCoverArmed()) {
                    return;
                }
                if (type == null) {
                    return;
                }
                SavedDataStorageAccess access = (SavedDataStorageAccess) (Object) this;
                String key = JournalKeys.savedataKey(access.rewind$getDataFile(type.id()));
                if (!RewindState.dataCover().covers(key)) {
                    return;
                }
                Object tag = RewindState.dataCover().serve(key);
                Map<SavedDataType<?>, Optional<SavedData>> cache = access.rewind$cache();
                if (tag == null) {
                    cache.put(type, Optional.empty());
                    cir.setReturnValue(null);
                    return;
                }
                // 与原版 readSavedData 同款解码：同一份 level / registries
                CompoundTag full = ((CompoundTag) tag).copy();
                ServerLevel level = access.rewind$level();
                RegistryOps<Tag> ops = access.rewind$registries().createSerializationContext(NbtOps.INSTANCE);
                T instance = ((Codec<T>) type.codecFactory().create(level))
                        .parse(ops, full.get("data"))
                        .resultOrPartial(error -> LOGGER.error("Failed to parse saved data for '{}': {}", type, error))
                        .orElse(null);
                cache.put(type, Optional.ofNullable(instance));
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
        @Inject(method = "readTagFromDisk(Ljava/nio/file/Path;Lnet/minecraft/util/datafix/DataFixTypes;I)Lnet/minecraft/nbt/CompoundTag;",
                at = @At("HEAD"),
                cancellable = true)
        private void rewind$serveTagFromDisk(Path dataFile, DataFixTypes dataFixType, int version,
                CallbackInfoReturnable<CompoundTag> cir) {
            try {
                if (RewindState.isDiscarding() || !RewindState.isStorageCoverArmed()) {
                    return;
                }
                if (dataFile == null) {
                    return;
                }
                String key = JournalKeys.savedataKey(dataFile);
                if (!RewindState.dataCover().covers(key)) {
                    return;
                }
                Object tag = RewindState.dataCover().serve(key);
                if (tag == null) {
                    cir.cancel();
                    throw new java.io.IOException("snapshot has no saved data: " + dataFile);
                }
                cir.setReturnValue(((CompoundTag) tag).copy());
            } catch (java.io.IOException e) {
                throw new RuntimeException(e);
            } catch (Throwable ignored) {
                // 非 IO 异常：当没拦住
            }
        }
    }

    /** 玩家数据：写侧经 {@code ValueOutput} 重序列化后拷一份，读侧武装窗口内喂快照。 */
    @Mixin(PlayerDataStorage.class)
    public abstract static class PlayerDataJournal {
        @Inject(method = "save(Lnet/minecraft/world/entity/player/Player;)V", at = @At("HEAD"))
        private void rewind$recordPlayer(Player player, CallbackInfo ci) {
            try {
                // 26.1 的 saveWithoutId 吃 ValueOutput：与原版 save 同款序列化出一份
                TagValueOutput output =
                        TagValueOutput.createWithContext(ProblemReporter.DISCARDING, player.registryAccess());
                player.saveWithoutId(output);
                CompoundTag tag = output.buildResult();
                RewindState.dataCover().onWritten(JournalKeys.playerKey(player.getStringUUID()), tag.copy());
            } catch (Throwable ignored) {
                // 监听绝不能污染原版写盘
            }
        }

        @Inject(method = "load(Lnet/minecraft/server/players/NameAndId;)Ljava/util/Optional;",
                at = @At("HEAD"),
                cancellable = true)
        private void rewind$servePlayer(NameAndId nameAndId, CallbackInfoReturnable<Optional<CompoundTag>> cir) {
            try {
                if (RewindState.isDiscarding() || !RewindState.isStorageCoverArmed()) {
                    return;
                }
                if (nameAndId == null) {
                    return;
                }
                String key = JournalKeys.playerKey(nameAndId.id().toString());
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
