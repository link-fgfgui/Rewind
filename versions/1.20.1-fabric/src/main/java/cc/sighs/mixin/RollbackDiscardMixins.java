package cc.sighs.mixin;

import cc.sighs.rewind.Rewind;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.storage.RegionFileStorage;
import net.minecraft.world.level.entity.PersistentEntitySectionManager;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.storage.DimensionDataStorage;
import net.minecraft.world.level.storage.LevelStorageSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 回滚窗口内跳过「写了也会被覆盖」的世界落盘。
 *
 * <p>F8 走的原地回滚不关世界：「强制卸载 → 内存直解灌装 → 重建重载 → 内存状态还原 → 标脏直写」。
 * 卸载与重载这段窗口里，原版流水线仍会把「马上要被覆盖掉的未来脏状态」往盘上刷
 * （卸载收尾的 {@code ChunkMap.save}、POI flush、实体/玩家收尾写入），所以窗口期内这些落盘
 * 要整段跳过——不拦内存变更，只拦「写了也会被丢弃」的磁盘写入；被丢弃的写入也不进写账本。
 * 卸载路径自己调 {@code ChunkMap.save(ChunkAccess)} 那一下整段跳过，还省掉
 * {@code ChunkSerializer.write} 的序列化开销。「关世界 → 覆盖 → 重开」那条回退路径复用同一个窗口。
 *
 * <p>窗口由回滚编排显式开关：开始前 {@link Rewind#beginDiscard()} 打开，整段收尾（或任何失败路径）
 * {@link Rewind#endDiscard()} 关掉，否则后续正常游玩的世界保存会被一起跳过。
 * 这些注入只跳过写盘，不阻止关句柄：{@code IOWorker.close()} / {@code RegionFile.close()} 照常执行
 * （关世界那条路覆盖 .mca 依然安全）。
 *
 * <h2>1.20.1 与 NeoForge 1.21.1 那份实现的差异</h2>
 * <ul>
 *   <li><b>{@code SavedData.save}</b>：1.20.1 的签名是 {@code save(java.io.File)}，没有
 *       {@code HolderLookup.Provider} 参数（那个参数是 1.20.5 之后加上的）。其余注入点的描述符
 *       逐个在 1.20.1 的 mojmap 上核对过，与 1.21.1 完全一致（{@code ChunkMap.saveAllChunks(Z)V} /
 *       {@code hasWork()Z} / {@code save(ChunkAccess)Z}、{@code ServerChunkCache.save(Z)V}、
 *       {@code PlayerList.saveAll()V} / {@code save(ServerPlayer)V}、
 *       {@code PersistentEntitySectionManager.saveAll()V} / {@code autoSave()V}、
 *       {@code DimensionDataStorage.save()V}、{@code RegionFileStorage.write(ChunkPos,CompoundTag)V}、
 *       {@code LevelStorageAccess.saveDataTag(RegistryAccess,WorldData,CompoundTag)V}）。</li>
 * </ul>
 */
public final class RollbackDiscardMixins {
    private RollbackDiscardMixins() {
    }

    /** 区块：saveAllChunks(true/false) 两种路径都会经过这里（ServerChunkCache.close 的 save(true) 也是）。 */
    @Mixin(ChunkMap.class)
    public abstract static class ChunkMapDiscard {
        @Shadow
        private PoiManager poiManager;

        @Inject(method = "saveAllChunks(Z)V", at = @At("HEAD"), cancellable = true)
        private void rewind$skipChunkSaveDuringRollback(boolean flush, CallbackInfo ci) {
            if (Rewind.isDiscarding()) {
                ci.cancel();
            }
        }

        /**
         * 关世界时 {@code MinecraftServer.stopServer} 会先跑一个
         * {@code while (chunkMap.hasWork()) { removeTicketsOnClosing(); chunkSource.tick(...); waitUntilNextTick(); }}
         * 的排空循环，每轮卸载区块都会序列化并落盘。回滚时这些内存里的区块本来就要整份丢掉，
         * 所以直接让这个循环的条件为假——省掉的是纯浪费的序列化 + I/O。
         */
        @Inject(method = "hasWork()Z", at = @At("HEAD"), cancellable = true)
        private void rewind$skipDrainLoopDuringRollback(CallbackInfoReturnable<Boolean> cir) {
            if (Rewind.isDiscarding()) {
                cir.setReturnValue(false);
            }
        }

        /**
         * 单个区块的落盘（{@code scheduleUnload} / {@code saveChunkIfNeeded} / {@code saveAllChunks} 都汇到这里）。
         * 原地回滚要主动卸载区块，卸载路径本身会调 {@code save}——这里整段跳过，省掉
         * {@code ChunkSerializer.write} 的序列化开销（写下去的也马上会被快照覆盖）。
         *
         * <p>双路细节：原版 {@code save} 先无条件 {@code poiManager.flush(pos)} 再判
         * {@code isUnsaved}——头部早退会漏掉 POI 脏标记，下一 tick 就把未来的 POI
         * 照写一遍盖掉刚还原的文件。所以早退前先 {@code flush} 清脏：它的异步写会被
         * 底层的 {@code RegionFileStorage.write} 丢弃窗口挡掉（只清脏、不污染盘面）。
         * （1.20.1 的 {@code flush} 在父类 {@code SectionStorage} 上，语义与 1.21.1 一致。）
         */
        @Inject(method = "save(Lnet/minecraft/world/level/chunk/ChunkAccess;)Z", at = @At("HEAD"), cancellable = true)
        private void rewind$skipSingleChunkSaveDuringRollback(ChunkAccess chunk, CallbackInfoReturnable<Boolean> cir) {
            if (Rewind.isDiscarding()) {
                try {
                    this.poiManager.flush(chunk.getPos());
                } catch (Throwable ignored) {
                    // POI 清脏失败不影响丢弃本身
                }
                cir.setReturnValue(false);
            }
        }
    }

    /** {@code ServerChunkCache.close()} 第一件事就是 {@code save(true)}，这里挡掉。 */
    @Mixin(ServerChunkCache.class)
    public abstract static class ServerChunkCacheDiscard {
        @Inject(method = "save(Z)V", at = @At("HEAD"), cancellable = true)
        private void rewind$skipChunkSaveDuringRollback(boolean flush, CallbackInfo ci) {
            if (Rewind.isDiscarding()) {
                ci.cancel();
            }
        }
    }

    /** 玩家数据 / 统计 / 进度都在这里落盘，回滚后由快照恢复。 */
    @Mixin(PlayerList.class)
    public abstract static class PlayerListDiscard {
        @Inject(method = "saveAll()V", at = @At("HEAD"), cancellable = true)
        private void rewind$skipPlayerSaveDuringRollback(CallbackInfo ci) {
            if (Rewind.isDiscarding()) {
                ci.cancel();
            }
        }
    }

    /**
     * 实体：{@code ServerLevel.close()} → {@code entityManager.close()} → {@code saveAll()}；
     * 另外进入暂停的那一 tick 原版会做一次弱保存（{@code saveEverything(false,false,false)}），
     * 实体侧走的是 {@code autoSave()}，所以两条都要挡。
     */
    @Mixin(PersistentEntitySectionManager.class)
    public abstract static class EntityDiscard {
        @Inject(method = "saveAll()V", at = @At("HEAD"), cancellable = true)
        private void rewind$skipEntitySaveDuringRollback(CallbackInfo ci) {
            if (Rewind.isDiscarding()) {
                ci.cancel();
            }
        }

        @Inject(method = "autoSave()V", at = @At("HEAD"), cancellable = true)
        private void rewind$skipEntityAutoSaveDuringRollback(CallbackInfo ci) {
            if (Rewind.isDiscarding()) {
                ci.cancel();
            }
        }
    }

    /** 维度数据（raids / random_sequences / scoreboard 等）。 */
    @Mixin(DimensionDataStorage.class)
    public abstract static class DataStorageDiscard {
        @Inject(method = "save()V", at = @At("HEAD"), cancellable = true)
        private void rewind$skipDataSaveDuringRollback(CallbackInfo ci) {
            if (Rewind.isDiscarding()) {
                ci.cancel();
            }
        }
    }

    // ---------------------------------------------------------------- 下面几层扎在更低的写入汇点上。
    // 光挡高层方法不够：关世界时 IOWorker.close() 会把「标记打开之前就已经排队」的写入全部执行完。
    // 这些写入虽然随后会被快照覆盖，但会把文件 mtime 改掉，让反向增量还原误判成「变了」，于是白拷一遍。
    // 堵在写入汇点上才是干净的。

    /** 所有 region 文件（chunk / poi / entities）的唯一写入口，含 IOWorker 排空时的补写。 */
    @Mixin(RegionFileStorage.class)
    public abstract static class RegionFileDiscard {
        @Inject(method = "write(Lnet/minecraft/world/level/ChunkPos;Lnet/minecraft/nbt/CompoundTag;)V",
                at = @At("HEAD"), cancellable = true)
        private void rewind$skipRegionWriteDuringRollback(CallbackInfo ci) {
            if (Rewind.isDiscarding()) {
                ci.cancel();
            }
        }
    }

    /**
     * {@code <维度>/data/*.dat} 的实际写盘点（DimensionDataStorage.save 最终都会走到这里）。
     *
     * <p>1.20.1 的签名是 {@code save(File)}：{@code HolderLookup.Provider} 那个参数是 1.20.5 才加的。
     */
    @Mixin(SavedData.class)
    public abstract static class SavedDataDiscard {
        @Inject(method = "save(Ljava/io/File;)V", at = @At("HEAD"), cancellable = true)
        private void rewind$skipSavedDataWriteDuringRollback(CallbackInfo ci) {
            if (Rewind.isDiscarding()) {
                ci.cancel();
            }
        }
    }

    /** 玩家数据的单条写盘：saveAll / removeAll / remove 以及玩家 tick 期间的保存都会经过这里。 */
    @Mixin(PlayerList.class)
    public abstract static class SinglePlayerSaveDiscard {
        @Inject(method = "save(Lnet/minecraft/server/level/ServerPlayer;)V", at = @At("HEAD"), cancellable = true)
        private void rewind$skipSinglePlayerSaveDuringRollback(CallbackInfo ci) {
            if (Rewind.isDiscarding()) {
                ci.cancel();
            }
        }
    }

    /**
     * level.dat：回滚期间不该写（马上要被快照的 level.dat 覆盖）。
     * 注意标记必须在重新开世界之前清掉——{@code Minecraft.doWorldLoad} 自己会调这个方法
     * 把「回溯后」的 WorldData 写回 level.dat，那一次是必须发生的。
     */
    @Mixin(LevelStorageSource.LevelStorageAccess.class)
    public abstract static class LevelDataDiscard {
        @Inject(method = "saveDataTag(Lnet/minecraft/core/RegistryAccess;Lnet/minecraft/world/level/storage/WorldData;Lnet/minecraft/nbt/CompoundTag;)V",
                at = @At("HEAD"), cancellable = true)
        private void rewind$skipLevelDataWriteDuringRollback(CallbackInfo ci) {
            if (Rewind.isDiscarding()) {
                ci.cancel();
            }
        }
    }
}
