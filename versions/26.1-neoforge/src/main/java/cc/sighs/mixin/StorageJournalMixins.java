package cc.sighs.mixin;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import cc.sighs.rewind.common.RewindState;
import cc.sighs.rewind.common.spi.StorageKind;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.StreamTagVisitor;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.IOWorker;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 写拦截记账：`IOWorker.store` 进来就往 {@link RewindState#storageJournal()} 记一笔。
 * 读侧（同文件下半部分）按武装状态查账，在 P1 把存档点数据灌进来并武装之前永远走原版。
 *
 * <p>26.1 只有 `store(ChunkPos, Supplier)` 一个真入口（tag 版只是包一层转调它，
 * 所以拦这一个就全覆盖；`SimpleRegionStorage` 与 chunk 存储都走这里）。
 * 这里在调用方线程就把 supplier 求值——多一次序列化开销，且序列化函数必须是纯的
 * （原版传进来的都是 `ChunkSerializer` 这类纯写函数）；求值抛异常就当没拦住，
 * 原版随后在 mailbox 线程里自己会抓会记。
 *
 * <p>求值时机的说明：调用方线程求到的比 mailbox 线程里求到的**不会更新**（中间若有改动，
 * 原版一定会再调一次 `store` 把账刷新，因为改动必先标脏）。种类与维度走 `storageInfo()`，
 * 与 1.21.1 那份同构（`ChunkPos.pack()` 代替 `toLong()`，维度键用 `identifier()`）。
 *
 * <p>`data == null` 的删除类写入记删除标记，不断言；`type()` 不认识的存储跳过。
 * 丢弃窗口（回滚卸载期）内的写入是“卸掉的未来”，不记账不冻结（撤装时本来就要清）。
 * 账本线程安全，两边都不加锁。
 */
public final class StorageJournalMixins {
    private StorageJournalMixins() {
    }

    /** 三类存储的写入口：只登记，不干预（含首次写前冻结）。 */
    @Mixin(IOWorker.class)
    public abstract static class IOWorkerJournal {
        @Shadow
        public abstract RegionStorageInfo storageInfo();

        @Inject(
                method = "store(Lnet/minecraft/world/level/ChunkPos;Ljava/util/function/Supplier;)Ljava/util/concurrent/CompletableFuture;",
                at = @At("HEAD"))
        private void rewind$recordStore(ChunkPos chunkPos, Supplier<CompoundTag> supplier,
                CallbackInfoReturnable<CompletableFuture<Void>> cir) {
            if (RewindState.isDiscarding()) {
                return;
            }
            CompoundTag chunkData;
            try {
                chunkData = supplier.get();
            } catch (Exception e) {
                return;
            }
            RegionStorageInfo info = this.storageInfo();
            StorageKind kind = kindOf(info.type());
            if (kind == null) {
                return;
            }
            String dimension = info.dimension().identifier().toString();
            long pos = chunkPos.pack();
            if (chunkData == null) {
                RewindState.recordChunkWrite(kind, dimension, pos, null);
                return;
            }
            RewindState.recordChunkWrite(kind, dimension, pos, chunkData.copy());
        }

        /**
         * 查账：武装且有覆盖就短路，没覆盖/没武装/回滚窗口内一律走原版。
         *
         * <p>返回的是账本里那份的引用——调用方必须 {@code copy()} 后再交出去
         * （原版 `pendingWrites` 读时也拷贝，同一份 NBT 绝不能同时被读写两边拿着）。
         * 删除标记与缺席统一返回 null，调用方用 {@link #isCoveredDeleted} 区分
         * （删除必须短路喂空，缺席才放行走盘）。
         */
        private CompoundTag serveCovered(ChunkPos chunkPos) {
            if (RewindState.isDiscarding() || !RewindState.isStorageCoverArmed()) {
                return null;
            }
            RegionStorageInfo info = this.storageInfo();
            StorageKind kind = kindOf(info.type());
            if (kind == null) {
                return null;
            }
            Object tag = RewindState.storageJournal().serve(
                    kind, info.dimension().identifier().toString(), chunkPos.pack());
            return (CompoundTag) tag;
        }

        /**
         * 该坐标在变更集里、但覆盖的是删除标记（快照里这个区块不存在）。
         *
         * <p>必须短路喂空（{@code Optional.empty()}），不能放行走盘——盘上是“改世界之后”
         * 的未来数据，放行就把删掉的区块复活了。
         */
        private boolean isCoveredDeleted(ChunkPos chunkPos) {
            if (RewindState.isDiscarding() || !RewindState.isStorageCoverArmed()) {
                return false;
            }
            RegionStorageInfo info = this.storageInfo();
            StorageKind kind = kindOf(info.type());
            if (kind == null) {
                return false;
            }
            String dimension = info.dimension().identifier().toString();
            long pos = chunkPos.pack();
            return RewindState.storageJournal().covers(kind, dimension, pos)
                    && RewindState.storageJournal().serve(kind, dimension, pos) == null;
        }

        private static StorageKind kindOf(String type) {
            switch (type) {
                case "chunk":
                    return StorageKind.CHUNK;
                case "entities":
                    return StorageKind.ENTITY;
                case "poi":
                    return StorageKind.POI;
                default:
                    return null;
            }
        }

        /**
         * 读入口：有覆盖直接给（含删除喂空），不再读盘（`pendingWrites` 优先内存的同构做法）。
         *
         * <p>平时（没武装）这个注入什么都不做——原版 `loadAsync` 先查 `pendingWrites` 再读盘，
         * 行为与改前逐行一致。未命中时<b>不作任何操作直接返回</b>（严禁返回 null，
         * 否则 `ChunkMap.readChunk` 的链式调用 NPE）。
         */
        @Inject(
                method = "loadAsync(Lnet/minecraft/world/level/ChunkPos;)Ljava/util/concurrent/CompletableFuture;",
                at = @At("HEAD"),
                cancellable = true)
        private void rewind$serveCoveredStore(
                ChunkPos chunkPos, CallbackInfoReturnable<CompletableFuture<Optional<CompoundTag>>> cir) {
            CompoundTag covered = serveCovered(chunkPos);
            if (covered != null) {
                cir.setReturnValue(CompletableFuture.completedFuture(Optional.of(covered.copy())));
                return;
            }
            if (isCoveredDeleted(chunkPos)) {
                cir.setReturnValue(CompletableFuture.completedFuture(Optional.empty()));
            }
        }

        /**
         * 扫描入口：blending / `isOldChunkAround` 走这里，不过它就看到不一致的数据。
         *
         * <p>有覆盖时把账本那份喂给 visitor（与原版喂 `pendingWrites` 那份同构），
         * 删除标记直接完成（缺席区块本来就扫不出东西），否则走原版扫盘。
         * visitor 抛异常就当没拦住（原版自己会抓会记）。
         */
        @Inject(
                method = "scanChunk(Lnet/minecraft/world/level/ChunkPos;Lnet/minecraft/nbt/StreamTagVisitor;)Ljava/util/concurrent/CompletableFuture;",
                at = @At("HEAD"),
                cancellable = true)
        private void rewind$serveCoveredScan(
                ChunkPos chunkPos, StreamTagVisitor visitor, CallbackInfoReturnable<CompletableFuture<Void>> cir) {
            CompoundTag covered = serveCovered(chunkPos);
            if (covered == null) {
                if (isCoveredDeleted(chunkPos)) {
                    cir.setReturnValue(CompletableFuture.completedFuture(null));
                }
                return;
            }
            try {
                covered.copy().acceptAsRoot(visitor);
            } catch (Exception e) {
                return;
            }
            cir.setReturnValue(CompletableFuture.completedFuture(null));
        }
    }
}
