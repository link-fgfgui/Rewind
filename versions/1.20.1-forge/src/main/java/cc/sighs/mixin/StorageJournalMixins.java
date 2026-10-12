package cc.sighs.mixin;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import cc.sighs.mixin.RollbackAccessMixins.IOWorkerAccess;
import cc.sighs.mixin.RollbackAccessMixins.RegionFileStorageAccess;
import cc.sighs.rewind.common.JournalKeys;
import cc.sighs.rewind.common.RewindState;
import cc.sighs.rewind.common.spi.StorageKind;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.StreamTagVisitor;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.IOWorker;
import net.minecraft.world.level.chunk.storage.RegionFileStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 写拦截记账：`IOWorker.store` 进来就往 {@link RewindState#storageJournal()} 记一笔。
 * 读侧（同文件下半部分）按武装状态查账，在 P1 把存档点数据灌进来并武装之前永远走原版。
 *
 * <p>1.20.1 的 `IOWorker` 没有 `storageInfo()`（1.21.1 才有），种类与维度从存档文件夹惯例反推：
 * 文件夹末段 `region` / `entities` / `poi` 定种类；维度见 {@link JournalKeys#dimensionOfFolder(Path)}——
 * 只认标准布局（主世界 / `DIM-1` / `DIM1` / `dimensions/<ns>/<path>/`），**未识别返回 null**
 * （写侧跳过记账，回滚侧对这类维度走 `.mca` 头部比对回退）。绝不能猜成主世界：
 * 那会把自定义维度的写入错记到主世界、污染主世界快照，还会漏掉自定义维度自己的回滚。
 *
 * <p>文件夹经既有 accessor 拿（`IOWorkerAccess` / `RegionFileStorageAccess`，回滚引擎也在用同一套），
 * 这里不新增 `@Shadow`。删除类写入（`chunkData == null`）记删除标记，不断言。
 *
 * <p>线程与 1.21.1 那份一致：注入跑在调用方线程，账本线程安全，不加锁。
 */
public final class StorageJournalMixins {
    private StorageJournalMixins() {
    }

    /** 三类存储的写入口：只登记，不干预（含首次写前冻结）。 */
    @Mixin(IOWorker.class)
    public abstract static class IOWorkerJournal {
        @Inject(
                method = "store(Lnet/minecraft/world/level/ChunkPos;Lnet/minecraft/nbt/CompoundTag;)Ljava/util/concurrent/CompletableFuture;",
                at = @At("HEAD"))
        private void rewind$recordStore(ChunkPos chunkPos, CompoundTag chunkData,
                CallbackInfoReturnable<CompletableFuture<Void>> cir) {
            if (RewindState.isDiscarding()) {
                return;
            }
            Path folder = folderOf((IOWorker) (Object) this);
            StorageKind kind = kindOfFolder(folder);
            String dimension = JournalKeys.dimensionOfFolder(folder);
            if (kind == null || dimension == null) {
                return;
            }
            if (chunkData == null) {
                RewindState.recordChunkWrite(kind, dimension, chunkPos.toLong(), null);
                return;
            }
            RewindState.recordChunkWrite(kind, dimension, chunkPos.toLong(), chunkData.copy());
        }

        /**
         * 查账：武装且有覆盖就短路，没覆盖/没武装/回滚窗口内一律走原版。
         *
         * <p>返回的是账本里那份的拷贝——原版 `pendingWrites` 读时也拷贝，
         * 同一份 NBT 绝不能同时被读写两边拿着。
         */
        private CompoundTag serveCovered(ChunkPos chunkPos) {
            if (RewindState.isDiscarding() || !RewindState.isStorageCoverArmed()) {
                return null;
            }
            Path folder = folderOf((IOWorker) (Object) this);
            StorageKind kind = kindOfFolder(folder);
            String dimension = JournalKeys.dimensionOfFolder(folder);
            if (kind == null || dimension == null) {
                return null;
            }
            Object tag = RewindState.storageJournal().serve(kind, dimension, chunkPos.toLong());
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
            Path folder = folderOf((IOWorker) (Object) this);
            StorageKind kind = kindOfFolder(folder);
            String dimension = JournalKeys.dimensionOfFolder(folder);
            if (kind == null || dimension == null) {
                return false;
            }
            long pos = chunkPos.toLong();
            return RewindState.storageJournal().covers(kind, dimension, pos)
                    && RewindState.storageJournal().serve(kind, dimension, pos) == null;
        }

        /** 该 worker 负责的存档文件夹（`.../region`、`.../DIM-1/entities` 之类）。 */
        private static Path folderOf(IOWorker worker) {
            RegionFileStorage storage = ((IOWorkerAccess) (Object) worker).rewind$storage();
            if (storage == null) {
                return null;
            }
            return ((RegionFileStorageAccess) (Object) storage).rewind$folder();
        }

        /** 文件夹末段定种类：对不上就返回 null（调用方跳过，不断言）。 */
        private static StorageKind kindOfFolder(Path folder) {
            if (folder == null) {
                return null;
            }
            Path fileName = folder.getFileName();
            if (fileName == null) {
                return null;
            }
            switch (fileName.toString()) {
                case "region":
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
