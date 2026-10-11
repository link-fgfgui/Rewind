package cc.sighs.rewind.server;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.LockSupport;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import cc.sighs.mixin.RollbackAccessMixins.AttachmentHolderAccess;
import cc.sighs.mixin.RollbackAccessMixins.ChunkMapAccess;
import cc.sighs.mixin.RollbackAccessMixins.ChunkStorageAccess;
import cc.sighs.mixin.RollbackAccessMixins.DimensionDataStorageAccess;
import cc.sighs.mixin.RollbackAccessMixins.DistanceManagerAccess;
import cc.sighs.mixin.RollbackAccessMixins.EntitySectionManagerAccess;
import cc.sighs.mixin.RollbackAccessMixins.EntityStorageAccess;
import cc.sighs.mixin.RollbackAccessMixins.IOWorkerAccess;
import cc.sighs.mixin.RollbackAccessMixins.RegionFileStorageAccess;
import cc.sighs.mixin.RollbackAccessMixins.SectionStorageAccess;
import cc.sighs.mixin.RollbackAccessMixins.ServerChunkCacheAccess;
import cc.sighs.mixin.RollbackAccessMixins.ServerLevelAccess;
import cc.sighs.mixin.RollbackAccessMixins.SimpleRegionStorageAccess;
import cc.sighs.rewind.Rewind;
import cc.sighs.rewind.common.RewindState;
import cc.sighs.rewind.common.compat.SophisticatedCoreCompat;
import cc.sighs.rewind.common.core.SavedDataRollback;
import cc.sighs.rewind.common.spi.StorageKind;
import cc.sighs.rewind.common.spi.StorageOverlay;
import cc.sighs.rewind.common.store.SnapshotBlockIo;
import cc.sighs.rewind.snapshot.RegionHeader;
import cc.sighs.rewind.snapshot.SnapshotBlockStore;
import cc.sighs.rewind.snapshot.SnapshotChunkDecoder;
import cc.sighs.rewind.snapshot.SnapshotBlocks;
import cc.sighs.rewind.snapshot.SnapshotLayout;
import cc.sighs.rewind.snapshot.ZPack;
import cc.sighs.rewind.snapshot.ZPackCheckpoint;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.network.protocol.game.ClientboundSetCarriedItemPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.ServerScoreboard;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkLevel;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.raid.Raids;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.storage.EntityStorage;
import net.minecraft.world.level.chunk.storage.IOWorker;
import net.minecraft.world.level.chunk.storage.SimpleRegionStorage;
import net.minecraft.world.level.chunk.storage.RegionFileStorage;
import net.minecraft.world.level.entity.EntityAccess;
import net.minecraft.world.level.entity.EntitySectionStorage;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.storage.DimensionDataStorage;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.level.storage.ServerLevelData;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.ScoreboardSaveData;
import net.neoforged.neoforge.attachment.LevelAttachmentsSavedData;

/**
 * 原地回滚：不关世界、不重开世界，在活着的集成服务器里把世界倒回存档点。
 *
 * <p>整段逻辑必须在服务端线程上跑完（它是唯一能安全碰 ChunkMap 与存储层的线程）。
 *
 * <p>顺序与依据：
 * <ol>
 *   <li><b>找受影响区块</b>：直接用写盘监听账本的精确变更集（建点之后写过的坐标），
 *       加上内存判据（未落盘的改动 / 未保存的实体）与快照实体判据。对 {@code /saves}
 *       活文件 0 读取。代价与「真正改了多少区块」成正比，而不是与视距成正比。</li>
 *   <li><b>强制卸载</b>：把这些区块的 ticket level 顶到 {@code ChunkLevel.MAX_LEVEL + 1}，再推进
 *       原版的卸载流水线。此时回滚窗口是开着的，卸载触发的落盘会被跳过——内存里这些状态本来就要被覆盖；
 *       POI 脏标记先清后弃，不漏网。</li>
 *   <li><b>灌装覆盖层</b>：直接从快照块存储按坐标解出扇区（严格头截断与版本分派，含
 *       {@code .mcc} 外溢），写前冻结基底优先，存档数据/玩家数据从槽位目录读入。
 *       对 {@code /saves} 活文件 0 读取；存储句柄不关（Windows 文件锁问题自然消失）。</li>
 *   <li><b>重载</b>：给卸载掉的区块重建 holder，读拦截稀疏短路喂出快照数据
 *       （未覆盖的区域穿透走盘）；原版的发送流水线会自动把新数据推给客户端。
 *       全部同步读回，不设视野外延迟（覆盖层撤装即清，延迟会错过覆盖）。</li>
 *   <li><b>内存状态</b>：玩家 / 时间天气单独还原（纯内存，不碰盘）；每个维度的存档数据
 *       缓存整体作废、经读拦截从覆盖层重读，其中「被长生命周期对象强引用」的那几个
 *       （袭击、记分板、等级数据附件）显式重建。</li>
 *   <li><b>强制标脏</b>：遍历回滚目标显式标脏并刷新可访问性，存档数据全部标脏，
 *       再显式直写一遍——启动自然持久化收敛，退出世界时原版落盘自动带走快照内容。</li>
 * </ol>
 *
 * <p>已知边界（比「关世界再重开」那条路少了什么）：**磁盘**与「每次用时 {@code computeIfAbsent} 取一次」
 * 的存档数据都能回滚（含模组写在 {@code <维度>/data/*.dat} 里的那些）；但**模组自己缓存的解码结果**
 * 不归世界生命周期管，同一进程里还会被复用（典型：精妙核心的包装器缓存，见 {@link SophisticatedCoreCompat}），
 * 也没有通用手段能替它清掉。装了这类模组、数据看起来没回滚时，把 COMMON 配置 {@code rollback.inPlaceRollback}
 * 关掉，退回「关世界 → 覆盖 → 重开」那条路——服务端对象全部重建，模组跟着世界重载走一遍。
 */
public final class InPlaceRollback {
    /** 卸载流水线的推进轮数上限：正常 1-2 轮就收敛，余量留给「还有生成任务在飞」的少数情况。 */
    private static final int MAX_DRAIN_ROUNDS = 400;
    /** 实体重新读盘是异步的，重载之后给它几轮时间落地。 */
    private static final int ENTITY_SETTLE_ROUNDS = 40;
    /** 每一轮之间让出一点时间给世界生成 / 后台线程，否则空转推进不了。 */
    private static final long PARK_NANOS = 200_000L;
    /** 卸载流水线连续多少轮没有进展就放弃（剩下的交给重建那一步兜住）。 */
    private static final int STALL_ROUNDS = 40;

    /** 单次原地回滚的统计，用于日志与自测。 */
    public static final class Result {
        public int scannedChunks;
        public int affectedChunks;
        public int unloadedChunks;
        public int reloadedChunks;
        /** 视野外、交给原版流水线异步读回来的区块数（同步读回架构下恒为 0：全部同步读回）。 */
        public int deferredChunks;
        public int stuckChunks;
        public String mirror = "";
        public int mirrorCopied;
        public int mirrorSkipped;
        public int mirrorFiles;
        public int restoredPlayers;
        public boolean restoredWorldData;
        public long unloadMs;
        public long reloadMs;
        public long stateMs;
        public long totalMs;
        /** 细分子步骤，只进日志：用来定位下一次该优化哪一段。 */
        public long collectMs;
        public long unloadPumpMs;
        public long entityDrainMs;
        public long cacheMs;
        public long holdersMs;
        public long loadMs;
        public long settleMs;
        public long stateWorldMs;
        public long statePlayersMs;
        public long forceDirtyMs;
        /** 覆盖层：灌装成功的区块数。 */
        public int coverPopulated;
        /** 覆盖层：其中走 zpack 快路的条目数（余下走块存储旧路/冻结基底，上机验收对账用）。 */
        public int coverZpack;
        /** 覆盖层：灌装耗时。 */
        public long coverMs;
        /** 覆盖层：重载命中的读次数。 */
        public long coverHits;

        public String summary() {
            return "chunks=" + affectedChunks + "/" + scannedChunks
                    + " unloaded=" + unloadedChunks
                    + " reloaded=" + reloadedChunks
                    + (deferredChunks == 0 ? "" : " deferred=" + deferredChunks)
                    + (stuckChunks == 0 ? "" : " stuck=" + stuckChunks)
                    + " players=" + restoredPlayers
                    + " worldData=" + (restoredWorldData ? "yes" : "no")
                    + " unloadMs=" + unloadMs
                    + " reloadMs=" + reloadMs
                    + " stateMs=" + stateMs
                    + " totalMs=" + totalMs
                    + " sub[collect=" + collectMs
                    + " unloadPump=" + unloadPumpMs
                    + " entityDrain=" + entityDrainMs
                    + " cache=" + cacheMs
                    + " holders=" + holdersMs
                    + " load=" + loadMs
                    + " settle=" + settleMs
                    + " forceDirty=" + forceDirtyMs
                    + " stateWorld=" + stateWorldMs
                    + " statePlayers=" + statePlayersMs + "]"
                    + " mirror[" + mirror + "]";
        }
    }

    /** 一个待回滚的区块：卸载前的 ticket level 要留着，重载时还原成同一个值。 */
    private static final class Target {
        private final ServerLevel level;
        private final ChunkPos pos;
        private int ticketLevel;
        private boolean unloaded;

        Target(ServerLevel level, ChunkPos pos) {
            this.level = level;
            this.pos = pos;
        }
    }

    private InPlaceRollback() {
    }

    /**
     * 执行原地回滚。调用方必须已经在服务端线程上，并且已经打开回滚窗口（{@link Rewind#beginDiscard()}）。
     *
     * @param worldRoot 活动存档目录
     * @param slot      槽位名
     */
    public static Result run(MinecraftServer server, Path worldRoot, String slot) throws Exception {
        Result result = new Result();
        long startedNanos = System.nanoTime();
        Path slotDir = SnapshotLayout.slotDir(worldRoot, slot);
        // 覆盖层灌了多少区块（finally 里连命中数一起记一行日志，稀疏拦截的验收口）。
        int coveredChunks = 0;

        try {
            // 先把「已经排队但还没落盘」的写入落下去：这类区块在内存里已经不是 unsaved、磁盘上又还没变，
            // 两个判据都会漏掉它。这段时间世界不会 tick（我们就在服务端线程上），所以不会再有新的写入插进来。
            Rewind.endDiscard();
            flushWorkers(server);
            Rewind.beginDiscard();

            long stepNanos = System.nanoTime();
            SnapshotBlocks snapshotBlocks = SnapshotBlocks.load(SnapshotLayout.blockMapFile(worldRoot, slot));
            // 快照里块编码的 .mca 没有实体文件，读它的头部/扇区要过一个块存储会话；用完就关。
            // collectTargets 只读内存账本与快照（允许），对 /saves 活文件 0 读取。
            List<Target> targets;
            try (SnapshotBlockStore headerStore = SnapshotBlockIo.openStore(worldRoot)) {
                targets = collectTargets(server, slotDir, headerStore, snapshotBlocks, result);
            }
            result.collectMs = millisSince(stepNanos);

            long subNanos = System.nanoTime();
            unload(server, targets, result);
            drainUnloads(server);
            result.unloadPumpMs = millisSince(subNanos);

            subNanos = System.nanoTime();
            // 实体区块的卸载由区块状态驱动，靠 entityManager.tick() 推进，而且可能因为「实体还没读完」
            // 被推迟到下一 tick。必须在这一段里彻底排空，否则它会落到回滚窗口之外，把「改世界之后」的
            // 实体列表写回刚还原的文件。
            drainEntityUnloads(server);
            result.entityDrainMs = millisSince(subNanos);

            subNanos = System.nanoTime();
            // 到这里被回滚接管的区块都已经从内存里丢掉了；接下来由回滚自己重建 holder，不再需要挡重建
            Rewind.clearUnloadGuards();
            invalidateCaches(server, targets);
            result.cacheMs = millisSince(subNanos);
            result.unloadMs = millisSince(stepNanos);

            // 零文件覆写回滚：不关存储句柄（Windows 文件锁问题自然消失），不回拷文件。
            // 覆盖层在这里灌好并武装，后面重建与重载读到的就是检查点数据；
            // 未被备份/未改动的区域不在覆盖层内，自然穿透走盘，二者零冲突。
            stepNanos = System.nanoTime();
            List<String> dataFiles;
            try (SnapshotBlockStore coverStore = SnapshotBlockIo.openStore(worldRoot)) {
                dataFiles = populateCover(server, worldRoot, slot, slotDir, snapshotBlocks, coverStore, targets, result);
                coveredChunks = result.coverPopulated;
            }
            result.coverMs = millisSince(stepNanos);
            RewindState.armStorageCover();
            Rewind.LOGGER.info("Rewind: storage cover populated ({} chunks, {} via zpack) in {} ms",
                    coveredChunks, result.coverZpack, result.coverMs);

            stepNanos = System.nanoTime();
            recreateHolders(server, targets, result);
            result.holdersMs = millisSince(stepNanos);
            result.reloadMs = result.holdersMs;

            // 玩家 / 时间天气必须排在这里：holder 已经重建、但区块还没成批重发。
            // 位置包要排在区块包前面，否则客户端得先处理完上百个区块包才会回 ack，而服务端在收到 ack
            // 之前是关着右键交互的（handleUseItemOn 里那道 awaitingPositionFromClient 的门）。
            // 也不能更早：player.load() 会走 Entity.setPosRaw，NeoForge 在那里会 level.getChunk() 请求
            // 区块，holder 不在就会直接 NPE。
            stepNanos = System.nanoTime();
            restoreWorldAndPlayers(server, result);
            result.stateMs = millisSince(stepNanos);

            stepNanos = System.nanoTime();
            loadChunks(server, targets, result);
            result.reloadMs += millisSince(stepNanos);

            try {
                restoreSavedData(server, dataFiles);
            } catch (Throwable t) {
                Rewind.LOGGER.error("Rewind: failed to reload saved data in place", t);
            }

            // 命中数必须在撤装前取：撤装连账本一起清，等到 finally 再取就恒为 0（验收指标失效）。
            // 先撤装再开写：撤装卸下覆盖层，之后的第 13 步直写走原版管道，写监听把回滚后的状态记成新基线。
            result.coverHits = RewindState.storageJournal().serveHits() + RewindState.dataCover().serveHits();
            RewindState.disarmStorageCover();
            Rewind.LOGGER.info("Rewind: storage cover served {} reads ({} chunks covered)",
                    result.coverHits, coveredChunks);

            stepNanos = System.nanoTime();
            forceDirty(server, targets);
            result.forceDirtyMs = millisSince(stepNanos);
        } finally {
            // 异常路径兜底（正常路径上面已撤装，幂等）：卸下覆盖层。
            RewindState.disarmStorageCover();
            // 回滚窗口一直开到整段结束：这期间任何落盘都是要被丢弃的（实体卸载的收尾写入尤其危险）
            Rewind.clearUnloadGuards();
            Rewind.endDiscard();
        }

        result.totalMs = millisSince(startedNanos);
        return result;
    }

    // ------------------------------------------------------------------ 1. 找受影响区块

    /**
     * 精确变更集判据（任一成立就算受影响），对 {@code /saves} 活文件 0 读取：
     * <ul>
     *   <li>写盘监听账本覆盖了该（种类，维度，坐标）——建点之后改过且已落盘或排队中。
     *       这条替代了老路的 {@code .mca} 头部 8 KiB 比对。</li>
     *   <li>{@code isUnsaved()}：内存里改过还没落盘（方块 / 方块实体 / 光照 / 计划刻 / 结构都会置这个位）。</li>
     *   <li>内存里还有「会被保存的实体」，或者快照里这个区块本来有实体。
     *       这条是实体回滚的关键：实体存储没有 per-chunk 脏标记，新放的实体在自动保存之前
     *       磁盘上完全看不出来，只能靠内存状态判断。快照侧读的是槽位块存储（允许），
     *       不碰活文件。</li>
     * </ul>
     * 代价与「真正改了多少区块」成正比，与视距无关。账本里有、但此时已不在内存的坐标
     * （改过之后又被卸载）同样纳入——灌装时从快照解码，重建时强制读回。
     */
    private static List<Target> collectTargets(MinecraftServer server, Path slotDir,
            SnapshotBlockStore store, SnapshotBlocks blocks, Result result) throws IOException {
        List<Target> targets = new ArrayList<>();
        Set<String> added = new HashSet<>();
        Map<Path, int[]> headerCache = new HashMap<>();
        for (ServerLevel level : server.getAllLevels()) {
            String dimensionId = level.dimension().location().toString();
            ChunkMap chunkMap = level.getChunkSource().chunkMap;
            List<Path> snapshotDirs = snapshotRegionDirs(server, level, slotDir);
            EntitySectionStorage<Entity> sections = entityManagerAccess(level).rewind$sectionStorage();
            for (var holder : mapAccess(chunkMap).rewind$getChunks()) {
                if (holder.getTicketLevel() > ChunkLevel.MAX_LEVEL) {
                    // 已经在卸载路上：内存里的内容马上就会自己丢掉，不用管
                    continue;
                }
                if (!(holder.getLatestChunk() instanceof LevelChunk chunk)) {
                    continue;
                }
                result.scannedChunks++;
                ChunkPos pos = chunk.getPos();
                long key = pos.toLong();
                if (journalCovers(dimensionId, key)
                        || chunk.isUnsaved()
                        || hasSaveableEntities(sections, pos)
                        || snapshotHasEntities(store, slotDir, blocks, snapshotDirs, pos, headerCache)) {
                    targets.add(new Target(level, pos));
                    added.add(keyFor(dimensionId, key));
                }
            }
        }
        // 账本里有、但已不在内存的坐标（改后卸载）：同样接管，重建时强制读回
        for (StorageOverlay.Entry entry : RewindState.storageJournal().entries()) {
            ServerLevel level = levelOf(server, entry.dimensionId);
            if (level == null) {
                continue;
            }
            String mapKey = keyFor(entry.dimensionId, entry.chunkPos);
            if (added.contains(mapKey)) {
                continue;
            }
            ChunkMap chunkMap = level.getChunkSource().chunkMap;
            if (mapAccess(chunkMap).rewind$getUpdatingChunkIfPresent(entry.chunkPos) != null) {
                continue;
            }
            targets.add(new Target(level, new ChunkPos(entry.chunkPos)));
            added.add(mapKey);
            result.scannedChunks++;
        }
        result.affectedChunks = targets.size();
        return targets;
    }

    /** 写账本有没有记过该（维度，坐标）（任一种类都算）。 */
    private static boolean journalCovers(String dimensionId, long chunkPos) {
        return RewindState.storageJournal().covers(StorageKind.CHUNK, dimensionId, chunkPos)
                || RewindState.storageJournal().covers(StorageKind.ENTITY, dimensionId, chunkPos)
                || RewindState.storageJournal().covers(StorageKind.POI, dimensionId, chunkPos);
    }

    /** 同一坐标在不同维度是不同条目：去重键带上维度。 */
    private static String keyFor(String dimensionId, long chunkPos) {
        return dimensionId + "|" + chunkPos;
    }

    private static ServerLevel levelOf(MinecraftServer server, String dimensionId) {
        for (ServerLevel level : server.getAllLevels()) {
            if (level.dimension().location().toString().equals(dimensionId)) {
                return level;
            }
        }
        return null;
    }

    /**
     * 该维度三类存储在槽位里的目录（与 {@code liveRegionDirs} 同序：区块 / 实体 / POI，
     * 相对槽位根）。只做路径拼接，不读盘。
     */
    private static List<Path> snapshotRegionDirs(MinecraftServer server, ServerLevel level, Path slotDir) {
        Path worldRoot = server.getWorldPath(LevelResource.LEVEL_DATA_FILE).getParent();
        List<Path> liveDirs = liveRegionDirs(level);
        List<Path> snapshotDirs = new ArrayList<>(liveDirs.size());
        for (Path liveDir : liveDirs) {
            snapshotDirs.add(slotDir.resolve(SnapshotLayout.relativize(worldRoot, liveDir)));
        }
        return snapshotDirs;
    }

    /** 内存里这个区块还有没有会被保存的实体（玩家不算，{@code Player.shouldBeSaved()} 为 false）。 */
    private static boolean hasSaveableEntities(EntitySectionStorage<Entity> sections, ChunkPos pos) {
        return sections.getExistingSectionsInChunk(pos.toLong())
                .flatMap(section -> section.getEntities())
                .anyMatch(EntityAccess::shouldBeSaved);
    }

    /** 快照里的实体文件在这个区块有没有数据（{@code RegionFile.clear} 会把偏移归零，所以偏移非 0 就是有）。 */
    private static boolean snapshotHasEntities(SnapshotBlockStore store, Path slotDir, SnapshotBlocks blocks,
            List<Path> snapshotDirs, ChunkPos pos, Map<Path, int[]> cache) throws IOException {
        // liveRegionDirs 的顺序是「区块 / 实体 / POI」
        if (snapshotDirs.size() < 2) {
            return false;
        }
        // 块编码的快照 .mca 没有实体文件，RegionHeader 会从块存储拼出前 8 KiB（老格式槽位直接读文件）
        int[] header = RegionHeader.fromSnapshot(cache, store, slotDir, blocks, snapshotDirs.get(1), regionFileName(pos));
        return header[(pos.x & 31) + (pos.z & 31) * 32] != 0;
    }

    private static String regionFileName(ChunkPos pos) {
        return "r." + pos.getRegionX() + "." + pos.getRegionZ() + ".mca";
    }

    /**
     * 内存直解灌装：把受影响区块的检查点数据从快照直接解进账本。
     *
     * <p>数据源二选一（对 {@code /saves} 活文件 <b>0 读取</b>）：
     * <ol>
     *   <li><b>zpack 快路</b>（TODO2 Phase2）：该槽位有 {@code <slot>.zpack} 时一次解出全量表，
     *       命中即喂账本（空 raw=删除标记）；未命中或解码失败回退第 2 条——“zpack 里没有”
     *       永不等价于删除（链上可能有洞：老槽位无包、建点时个别编不出）。</li>
     *   <li><b>块存储旧路</b>：区块扇区走快照块存储（{@code SnapshotChunkDecoder}，
     *       严格按 5 字节头截断与版本分派，含 {@code .mcc} 外溢）。</li>
     * </ol>
     * 写前冻结基底优先（冷区块快照数据，未进块存储的不走解码），
     * 存档数据/玩家数据走槽位目录的 {@code .dat}（快照目录，允许）。
     * 老格式槽位（整文件、无块映射）回退读槽位整文件——同样是快照目录，不碰活文件。
     * 快照里不存在的区块记删除标记：读拦截短路喂空，不放行走盘（否则把删掉的区块复活）。
     * 单个区块解码失败只跳过该区块（记一行日志），不炸整段回滚。
     *
     * @return 本次覆盖到的存档数据文件相对路径（给存档数据回滚打日志用）
     */
    private static List<String> populateCover(MinecraftServer server, Path worldRoot, String slot,
            Path slotDir, SnapshotBlocks blocks,
            SnapshotBlockStore store, List<Target> targets, Result result) {
        int covered = 0;
        List<String> dataFiles = new ArrayList<>();
        // zpack 全量表：一次解出（无包/链损坏 → null，全员走旧路）。
        Map<String, byte[]> zpackCover = null;
        try {
            zpackCover = ZPackCheckpoint.tryResolve(worldRoot, slot);
        } catch (Throwable t) {
            Rewind.LOGGER.warn("Rewind: zpack cover for {} unreadable, falling back to block store", slot, t);
            zpackCover = null;
        }
        for (Map.Entry<ServerLevel, List<ChunkPos>> entry : targetsByLevel(targets).entrySet()) {
            covered += populateCoverForLevel(slotDir, blocks, store, zpackCover, entry.getKey(), entry.getValue(),
                    result);
        }
        covered += populateDataCover(server, worldRoot, zpackCover, targets, dataFiles);
        result.coverPopulated = covered;
        result.mirror = "cover=" + covered;
        result.mirrorCopied = covered;
        result.mirrorSkipped = 0;
        result.mirrorFiles = targets.size();
        return dataFiles;
    }

    private static Map<ServerLevel, List<ChunkPos>> targetsByLevel(List<Target> targets) {
        Map<ServerLevel, List<ChunkPos>> byLevel = new LinkedHashMap<>();
        for (Target target : targets) {
            byLevel.computeIfAbsent(target.level, level -> new ArrayList<>()).add(target.pos);
        }
        return byLevel;
    }

    private static int populateCoverForLevel(Path slotDir, SnapshotBlocks blocks,
            SnapshotBlockStore store, Map<String, byte[]> zpackCover, ServerLevel level, List<ChunkPos> poses,
            Result result) {
        NeoForgeRewindPlatform platform = new NeoForgeRewindPlatform();
        String dimensionId = level.dimension().location().toString();
        int covered = 0;
        StorageKind[] kinds = {StorageKind.CHUNK, StorageKind.ENTITY, StorageKind.POI};
        for (StorageKind kind : kinds) {
            String dir = platform.chunkDirRelative(kind, dimensionId);
            if (dir == null) {
                Rewind.LOGGER.warn("Rewind: unknown storage layout for {} in {}, skipping cover for it",
                        kind, dimensionId);
                continue;
            }
            for (ChunkPos pos : poses) {
                if (populateCoverForChunk(slotDir, blocks, store, zpackCover, platform,
                        dir, kind, dimensionId, pos, result)) {
                    covered++;
                }
            }
        }
        return covered;
    }

    private static boolean populateCoverForChunk(Path slotDir, SnapshotBlocks blocks,
            SnapshotBlockStore store, Map<String, byte[]> zpackCover, NeoForgeRewindPlatform platform,
            String dir, StorageKind kind, String dimensionId, ChunkPos pos, Result result) {
        // zpack 快路：命中即喂账本；未命中/解码失败一律回退旧路（链洞≠删除）。
        if (zpackCover != null) {
            String key = ZPack.keyOf(ZPackCheckpoint.kindToType(kind), dimensionId, pos.toLong());
            if (zpackCover.containsKey(key)) {
                try {
                    byte[] raw = zpackCover.get(key);
                    if (raw == null || raw.length == 0) {
                        RewindState.storageJournal().onWritten(kind, dimensionId, pos.toLong(), null);
                        result.coverZpack++;
                        return true;
                    }
                    Object tag = platform.decodeChunkSector(raw);
                    if (tag != null) {
                        RewindState.storageJournal().onWritten(kind, dimensionId, pos.toLong(), tag);
                        result.coverZpack++;
                        return true;
                    }
                    Rewind.LOGGER.warn("Rewind: zpack entry {} undecodable, falling back to block store", key);
                } catch (Throwable t) {
                    Rewind.LOGGER.warn("Rewind: zpack entry {} failed, falling back to block store", key, t);
                }
            }
        }
        String mcaRelative = SnapshotLayout.normalize(dir + "/" + regionFileName(pos));
        try {
            SnapshotChunkDecoder.Sector sector;
            byte[] externalPayload = null;
            SnapshotBlocks.Entry entry = blocks.get(mcaRelative);
            if (entry != null) {
                sector = SnapshotChunkDecoder.readSector(store, blocks, mcaRelative, pos.x, pos.z);
                if (sector != null && sector.external) {
                    String mccRelative = SnapshotChunkDecoder.mccRelative(mcaRelative, pos.x, pos.z);
                    externalPayload = SnapshotChunkDecoder.readExternalPayload(store, blocks, mccRelative);
                }
            } else {
                Path slotFile = slotDir.resolve(mcaRelative);
                sector = SnapshotChunkDecoder.readSectorFromFile(slotFile, pos.x, pos.z);
                if (sector != null && sector.external) {
                    Path slotMcc = slotDir.resolve(SnapshotChunkDecoder.mccRelative(mcaRelative, pos.x, pos.z));
                    externalPayload = SnapshotChunkDecoder.readExternalPayloadFromFile(slotMcc);
                }
            }
            if (sector == null) {
                // 快照里没有这个区块：记删除标记，读拦截短路喂空
                RewindState.storageJournal().onWritten(kind, dimensionId, pos.toLong(), null);
                return true;
            }
            byte[] uncompressed = sector.external
                    ? SnapshotChunkDecoder.decompress(sector.versionId, externalPayload)
                    : SnapshotChunkDecoder.decompress(sector.versionId, sector.compressed);
            Object tag = platform.decodeChunkSector(uncompressed);
            if (tag == null) {
                Rewind.LOGGER.warn("Rewind: cannot decode checkpoint chunk {} from {}, skipping cover for it",
                        pos, mcaRelative);
                return false;
            }
            RewindState.storageJournal().onWritten(kind, dimensionId, pos.toLong(), tag);
            return true;
        } catch (Throwable t) {
            Rewind.LOGGER.warn("Rewind: cannot read checkpoint chunk {} from {}, skipping cover for it",
                    pos, mcaRelative, t);
            return false;
        }
    }

    /**
     * 非区块 NBT 灌装：把存档点的 {@code .dat} 从 zpack 统一解码装配进数据覆盖层（决策 2）。
     *
     * <p>数据源只有一个：{@code zpackCover}（链式差分解出的全量表，{@code TYPE_DATA_FILE}
     * 条目为未压缩 NBT 字节）。槽位松散 {@code .dat} 一律不再读取——回滚期间全部非区块
     * 状态由内存 {@link RewindState#dataCover()} 提供。该槽位无包（老槽位 / 链损坏）时
     * 不灌装任何条目也不猜删除（宁慢勿串）。
     *
     * <p>候选 id 取「zpack 里该数据目录前缀下的条目 ∪ 活目录枚举」：活目录枚举只做 readdir
     * （不读内容），用于识别「建点之后才出现的文件」并记删除标记，回滚后懒加载不再复活未来文件；
     * 链里没有该 key 而活文件存在即按此处理。玩家数据只灌在线玩家的（离线玩家走登录时读盘，
     * 届时覆盖已撤；缺席同样记删除标记）。{@code level.dat} 也入覆盖（供 {@link #restoreWorldData}
     * 纯内存读取世界时间/天气/出生点）。
     *
     * @return 灌装计数；dataFiles 收集本次覆盖到的存档数据文件相对路径（世界根基准，给日志用）
     */
    private static int populateDataCover(MinecraftServer server, Path worldRoot, Map<String, byte[]> zpackCover,
            List<Target> targets, List<String> dataFiles) {
        int covered = 0;
        if (server == null) {
            return 0;
        }
        if (zpackCover == null) {
            // 老槽位 / 包损坏：没有差分数据源，不灌装也不猜删除（决策 2：内容只认 zpack）
            Rewind.LOGGER.warn("Rewind: slot has no readable zpack, non-chunk data cover skipped");
            return 0;
        }
        NeoForgeRewindPlatform platform = new NeoForgeRewindPlatform();
        // zpack 的 DATA_FILE 条目按相对路径分组：相对目录 → 文件名（去 .dat）
        Map<String, Set<String>> idsByDir = new HashMap<>();
        for (String zpackKey : zpackCover.keySet()) {
            ZPack.ParsedKey parsed;
            try {
                parsed = ZPack.parseKey(zpackKey);
            } catch (Throwable ignored) {
                continue;
            }
            if (parsed.targetType != ZPack.TYPE_DATA_FILE) {
                continue;
            }
            String relative = SnapshotLayout.normalize(parsed.id);
            int slash = relative.lastIndexOf('/');
            if (slash <= 0) {
                continue; // level.dat / level.dat_old 另行处理
            }
            String dir = relative.substring(0, slash);
            String name = relative.substring(slash + 1);
            if (!name.endsWith(".dat")) {
                continue;
            }
            idsByDir.computeIfAbsent(dir, k -> new HashSet<>())
                    .add(name.substring(0, name.length() - 4));
        }
        Set<String> seenDataKeys = new HashSet<>();
        for (ServerLevel level : server.getAllLevels()) {
            DimensionDataStorageAccess access =
                    (DimensionDataStorageAccess) (Object) level.getDataStorage();
            File dataFolder = access.rewind$dataFolder();
            if (dataFolder == null) {
                continue;
            }
            Path liveDir = dataFolder.toPath();
            String dirRelative;
            try {
                dirRelative = SnapshotLayout.normalize(SnapshotLayout.relativize(worldRoot, liveDir));
            } catch (Throwable t) {
                continue;
            }
            Set<String> ids = new HashSet<>();
            Set<String> fromPack = idsByDir.get(dirRelative);
            if (fromPack != null) {
                ids.addAll(fromPack);
            }
            if (Files.isDirectory(liveDir)) {
                try (java.nio.file.DirectoryStream<Path> stream =
                        Files.newDirectoryStream(liveDir, "*.dat")) {
                    for (Path dat : stream) {
                        String name = dat.getFileName().toString();
                        if (name.endsWith(".dat")) {
                            ids.add(name.substring(0, name.length() - 4));
                        }
                    }
                } catch (Throwable ignored) {
                    // 枚举失败就当没有
                }
            }
            for (String id : ids) {
                File liveFile = new File(dataFolder, id + ".dat");
                String key = cc.sighs.mixin.DataJournalMixins.savedataKey(liveFile);
                if (!seenDataKeys.add(key)) {
                    continue;
                }
                String relative;
                try {
                    relative = SnapshotLayout.relativize(worldRoot, liveFile.toPath());
                } catch (Throwable t) {
                    continue;
                }
                byte[] raw = zpackCover.get(ZPack.keyOf(ZPack.TYPE_DATA_FILE, relative, 0L));
                if (raw == null) {
                    // 活文件有、链里没有：建点之后才出现的文件 → 删除标记（回滚后不复活未来文件）
                    RewindState.dataCover().onWritten(key, null);
                    covered++;
                    continue;
                }
                if (raw.length == 0) {
                    RewindState.dataCover().onWritten(key, null);
                    covered++;
                    continue;
                }
                try {
                    Object tag = platform.decodeChunkSector(raw);
                    if (tag instanceof CompoundTag) {
                        RewindState.dataCover().onWritten(key, ((CompoundTag) tag).copy());
                        covered++;
                        dataFiles.add(relative);
                    } else {
                        Rewind.LOGGER.warn("Rewind: zpack data entry {} undecodable, cover skipped", relative);
                    }
                } catch (Throwable t) {
                    Rewind.LOGGER.warn("Rewind: zpack data entry {} failed, cover skipped", relative, t);
                }
            }
        }
        // level.dat：世界时间 / 天气 / 出生点的纯内存还原源
        byte[] levelRaw = zpackCover.get(ZPack.keyOf(ZPack.TYPE_DATA_FILE, "level.dat", 0L));
        if (levelRaw != null && levelRaw.length > 0) {
            try {
                Object tag = platform.decodeChunkSector(levelRaw);
                if (tag instanceof CompoundTag) {
                    String key = cc.sighs.mixin.DataJournalMixins.savedataKey(
                            worldRoot.resolve("level.dat").toFile());
                    RewindState.dataCover().onWritten(key, ((CompoundTag) tag).copy());
                    covered++;
                }
            } catch (Throwable t) {
                Rewind.LOGGER.warn("Rewind: zpack level.dat entry failed, cover skipped", t);
            }
        }
        // 玩家数据：只灌在线玩家的；链里缺席记删除标记（离线玩家走登录时读盘，覆盖已撤）
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            String uuid = player.getStringUUID();
            String key = cc.sighs.mixin.DataJournalMixins.playerKey(uuid);
            String liveRelative;
            try {
                liveRelative = SnapshotLayout.relativize(
                        worldRoot, server.getWorldPath(LevelResource.PLAYER_DATA_DIR).resolve(uuid + ".dat"));
            } catch (Throwable t) {
                continue;
            }
            byte[] raw = zpackCover.get(ZPack.keyOf(ZPack.TYPE_DATA_FILE, liveRelative, 0L));
            if (raw == null) {
                RewindState.dataCover().onWritten(key, null);
                covered++;
                continue;
            }
            if (raw.length == 0) {
                RewindState.dataCover().onWritten(key, null);
                covered++;
                continue;
            }
            try {
                Object tag = platform.decodeChunkSector(raw);
                if (tag instanceof CompoundTag) {
                    RewindState.dataCover().onWritten(key, ((CompoundTag) tag).copy());
                    covered++;
                    dataFiles.add(liveRelative);
                } else {
                    Rewind.LOGGER.warn("Rewind: zpack player entry {} undecodable, cover skipped", liveRelative);
                }
            } catch (Throwable t) {
                Rewind.LOGGER.warn("Rewind: zpack player entry {} failed, cover skipped", liveRelative, t);
            }
        }
        return covered;
    }

    /** 这个维度里所有「按区块存 region 文件」的存储目录：区块 / 实体 / POI。 */
    private static List<Path> liveRegionDirs(ServerLevel level) {
        ServerChunkCache source = level.getChunkSource();
        List<Path> dirs = new ArrayList<>(3);
        addFolder(dirs, chunkStorageAccess(source.chunkMap).rewind$worker());
        Object permanent = entityManagerAccess(level).rewind$permanentStorage();
        if (permanent instanceof EntityStorage storage) {
            addFolder(dirs, workerOf(entityStorageAccess(storage).rewind$simpleRegionStorage()));
        }
        addFolder(dirs, workerOf(sectionStorageAccess(source.getPoiManager()).rewind$simpleRegionStorage()));
        return dirs;
    }

    private static void addFolder(List<Path> dirs, IOWorker worker) {
        if (worker == null) {
            return;
        }
        dirs.add(regionFileStorageAccess(ioWorkerAccess(worker).rewind$storage()).rewind$folder());
    }

    private static IOWorker workerOf(SimpleRegionStorage storage) {
        return storage == null ? null : simpleRegionStorageAccess(storage).rewind$worker();
    }

    // ------------------------------------------------------------------ 2. 强制卸载

    private static void unload(MinecraftServer server, List<Target> targets, Result result) {
        // 先按维度登记「本轮接管的区块」：ticket 系统仍然认为它们该加载，会通过邻居传播把 holder
        // 重建出来（进而拉起 worldgen 任务、把 refCount 钉住），这段时间必须挡住重建。
        Map<ServerLevel, LongSet> guards = new LinkedHashMap<>();
        for (Target target : targets) {
            guards.computeIfAbsent(target.level, level -> new LongOpenHashSet()).add(target.pos.toLong());
        }
        guards.forEach((level, positions) -> {
            long[] sorted = positions.toLongArray();
            Arrays.sort(sorted);
            Rewind.beginUnloadGuard(level.getChunkSource().chunkMap, sorted);
        });

        int unloadedLevel = ChunkLevel.MAX_LEVEL + 1;
        for (Target target : targets) {
            ChunkMap chunkMap = target.level.getChunkSource().chunkMap;
            ChunkMapAccess access = mapAccess(chunkMap);
            long key = target.pos.toLong();
            var holder = access.rewind$getUpdatingChunkIfPresent(key);
            if (holder == null) {
                // 改后卸载的坐标：内存里已经没有 holder，重建时强制读回（覆盖层已灌好）。
                // ticketLevel 保持默认 0（全加载），回滚后由距离管理器自然接管。
                target.unloaded = true;
                continue;
            }
            int old = holder.getTicketLevel();
            target.ticketLevel = old;
            if (old > ChunkLevel.MAX_LEVEL) {
                continue;
            }
            var updated = access.rewind$updateChunkScheduling(key, unloadedLevel, holder, old);
            if (updated != null) {
                distanceAccess(chunkMap.getDistanceManager()).rewind$chunksToUpdateFutures().add(updated);
                target.unloaded = true;
                result.unloadedChunks++;
            }
        }
    }

    /** 推进原版的卸载流水线，直到 toDrop / pendingUnloads / unloadQueue 都空。 */
    private static void drainUnloads(MinecraftServer server) {
        int lastPending = Integer.MAX_VALUE;
        int stalled = 0;
        for (int round = 0; round < MAX_DRAIN_ROUNDS; round++) {
            int pending = pumpUnloadsOnce(server);
            if (pending == 0) {
                return;
            }
            if (pending >= lastPending) {
                // 没进展就别空转了：`processUnloads` 会跳过 generationRefCount 非 0 的 holder，
                // 那说明它被某个 worldgen 任务钉着，等下去也不会自己好。
                if (++stalled > STALL_ROUNDS) {
                    Rewind.LOGGER.warn(
                            "Rewind: in-place rollback gave up draining chunk unloads ({} still pending, {} pinned by generation tasks)",
                            pending, countPinnedByGeneration(server));
                    return;
                }
                // 只有「这一轮没推进」时才让出时间给后台线程：还在推进就接着推。
                // 原来每轮无条件 park 200µs，几十轮下来光等待就十几毫秒。
                LockSupport.parkNanos(PARK_NANOS);
            } else {
                stalled = 0;
            }
            lastPending = pending;
        }
        Rewind.LOGGER.warn("Rewind: in-place rollback could not fully drain chunk unloads ({} still pending)", lastPending);
    }

    private static int pumpUnloadsOnce(MinecraftServer server) {
        int pending = 0;
        for (ServerLevel level : server.getAllLevels()) {
            ServerChunkCache source = level.getChunkSource();
            ChunkMapAccess access = mapAccess(source.chunkMap);
            access.rewind$processUnloads(() -> true);
            sourceAccess(source).rewind$runDistanceManagerUpdates();
            access.rewind$processUnloads(() -> true);
            pending += access.rewind$toDrop().size()
                    + access.rewind$pendingUnloads().size()
                    + access.rewind$unloadQueue().size();
        }
        return pending;
    }

    private static int countPinnedByGeneration(MinecraftServer server) {
        int pinned = 0;
        for (ServerLevel level : server.getAllLevels()) {
            ChunkMapAccess access = mapAccess(level.getChunkSource().chunkMap);
            for (long pos : access.rewind$toDrop()) {
                ChunkHolder holder = access.rewind$getUpdatingChunkIfPresent(pos);
                if (holder != null && holder.getGenerationRefCount() != 0) {
                    pinned++;
                }
            }
        }
        return pinned;
    }

    private static void tickEntityManagers(MinecraftServer server) {
        for (ServerLevel level : server.getAllLevels()) {
            levelAccess(level).rewind$entityManager().tick();
        }
    }

    /**
     * 把实体区块的卸载彻底排空。
     *
     * <p>卸载请求落在 {@code chunksToUnload} 上，只有 {@code entityManager.tick()} 会推进它；而
     * {@code storeChunkSections} 在实体还没读回来（status 不是 LOADED）时会直接放弃、留到下一 tick。
     * 所以单跑一次 tick 不够——那些被推迟的卸载会落到回滚窗口之外，把「改世界之后」的实体列表
     * 写回刚还原的文件。这里一直泵到没有待卸载的区块为止。
     *
     * <p>但**连续多轮没进展就放弃**，和区块卸载那条一样：区块本身没卸掉（被 worldgen 钉住）时，
     * 实体这边也就没得卸，再等下去只是空转——`PARK_NANOS` 写的是 200µs，Windows 上每次 park 实际
     * 要睡 1ms 出头，跑满 {@value #MAX_DRAIN_ROUNDS} 轮就是几百毫秒白等（实测 529ms）。
     */
    private static void drainEntityUnloads(MinecraftServer server) {
        int lastPending = Integer.MAX_VALUE;
        int stalled = 0;
        for (int round = 0; round < MAX_DRAIN_ROUNDS; round++) {
            int pending = 0;
            for (ServerLevel level : server.getAllLevels()) {
                levelAccess(level).rewind$entityManager().tick();
                pending += entityManagerAccess(level).rewind$chunksToUnload().size();
            }
            if (pending == 0) {
                return;
            }
            // 和区块卸载那条一样：还在推进就接着推，只有「这一轮没推进」时才让出时间。
            if (pending >= lastPending) {
                if (++stalled > STALL_ROUNDS) {
                    Rewind.LOGGER.warn(
                            "Rewind: in-place rollback gave up draining entity-chunk unloads ({} still pending)",
                            pending);
                    return;
                }
                LockSupport.parkNanos(PARK_NANOS);
            } else {
                stalled = 0;
            }
            lastPending = pending;
        }
        Rewind.LOGGER.warn("Rewind: in-place rollback could not fully drain entity-chunk unloads");
    }

    /**
     * 作废按区块缓存的派生数据，否则重载回来的区块会带着「改世界之后」的缓存。
     *
     * <ul>
     *   <li>实体存储的 {@code emptyChunks}：区块被卸载时如果当时没有可保存的实体，这个位置会被记成
     *       「空区块」，之后 {@code loadEntities} 直接返回空——快照里本来有实体的区块就再也读不出来。</li>
     *   <li>POI 的分段缓存：床位 / 工作站这类兴趣点按 16³ 分段缓存在内存里，不改就会留下幻影 POI。
     *       {@code storage} 与 {@code dirty} 必须一起删——只删 {@code storage} 的话这个键还留在
     *       {@code dirty} 里，下一 tick {@code tick()} 会把这段内存里已经没有了的内容照写一遍，
     *       正好盖掉刚还原好的 poi 文件。</li>
     * </ul>
     */
    private static void invalidateCaches(MinecraftServer server, List<Target> targets) {
        for (ServerLevel level : server.getAllLevels()) {
            Object permanent = entityManagerAccess(level).rewind$permanentStorage();
            var emptyChunks = permanent instanceof EntityStorage storage
                    ? entityStorageAccess(storage).rewind$emptyChunks()
                    : null;
            SectionStorageAccess poi = sectionStorageAccess(level.getChunkSource().getPoiManager());
            for (Target target : targets) {
                if (target.level != level) {
                    continue;
                }
                if (emptyChunks != null) {
                    emptyChunks.remove(target.pos.toLong());
                }
                for (int sectionY = level.getMinSection(); sectionY < level.getMaxSection(); sectionY++) {
                    long key = SectionPos.asLong(target.pos.x, sectionY, target.pos.z);
                    poi.rewind$sectionCache().remove(key);
                    poi.rewind$dirtySections().remove(key);
                }
            }
        }
    }

    // ------------------------------------------------------------------ 3. 排空写入（零文件覆写：句柄不关、文件不拷）

    private static IOWorker poiWorker(PoiManager poi) {
        SimpleRegionStorage storage = sectionStorageAccess(poi).rewind$simpleRegionStorage();
        return storage == null ? null : simpleRegionStorageAccess(storage).rewind$worker();
    }

    /** 排空三条存储链（区块 / POI / 实体）排队中的写入，不 fsync。 */
    private static void flushWorkers(MinecraftServer server) {
        for (ServerLevel level : server.getAllLevels()) {
            ServerChunkCache source = level.getChunkSource();
            drainWorker(chunkStorageAccess(source.chunkMap).rewind$worker());
            drainWorker(poiWorker(source.getPoiManager()));
            Object permanent = entityManagerAccess(level).rewind$permanentStorage();
            if (permanent instanceof EntityStorage storage) {
                drainWorker(simpleRegionStorageAccess(entityStorageAccess(storage).rewind$simpleRegionStorage()).rewind$worker());
            }
        }
    }

    private static void drainWorker(IOWorker worker) {
        if (worker == null) {
            return;
        }
        try {
            worker.synchronize(false).join();
        } catch (Throwable t) {
            Rewind.LOGGER.warn("Rewind: failed to drain pending chunk writes", t);
        }
    }

    // ------------------------------------------------------------------ 4. 重载

    /** 重建 holder 并推进流水线：让被卸载掉的区块重新排队，从（已还原的）磁盘读回来。 */
    private static void recreateHolders(MinecraftServer server, List<Target> targets, Result result) {
        for (Target target : targets) {
            ChunkMap chunkMap = target.level.getChunkSource().chunkMap;
            ChunkMapAccess access = mapAccess(chunkMap);
            long key = target.pos.toLong();
            if (!target.unloaded) {
                // 卸载时被跳过了（还有生成任务在飞）：保持原样，这个区块不会回滚，记一笔
                result.stuckChunks++;
                continue;
            }
            var holder = access.rewind$getUpdatingChunkIfPresent(key);
            if (holder != null) {
                // 没卸干净：直接顶回原来的 ticket level，区块保持内存里的旧内容
                var updated = access.rewind$updateChunkScheduling(key, target.ticketLevel, holder, holder.getTicketLevel());
                if (updated != null) {
                    distanceAccess(chunkMap.getDistanceManager()).rewind$chunksToUpdateFutures().add(updated);
                }
                result.stuckChunks++;
                continue;
            }
            var created = access.rewind$updateChunkScheduling(key, target.ticketLevel, null, ChunkLevel.MAX_LEVEL + 1);
            if (created != null) {
                distanceAccess(chunkMap.getDistanceManager()).rewind$chunksToUpdateFutures().add(created);
            }
            access.rewind$chunkTypeCache().remove(key);
        }

        for (ServerLevel level : server.getAllLevels()) {
            sourceAccess(level.getChunkSource()).rewind$runDistanceManagerUpdates();
        }
    }

    /**
     * 把重建好的区块读回来：全部同步读（这也是原版把新数据推给客户端的触发点）。
     *
     * <p>同步读回架构下不设“视野外延迟”：覆盖层在撤装时清空，延迟加载的区块会错过覆盖、
     * 穿透读到盘上的未来数据。受影响集与“建点后改了多少”成正比，同步读完才关窗口。
     */
    private static void loadChunks(MinecraftServer server, List<Target> targets, Result result) {
        long startedNanos = System.nanoTime();
        // 缓存里可能还留着「重建之前」的 ChunkAccess；每个维度清一次就够。
        Set<ServerLevel> cleared = new HashSet<>();
        for (Target target : targets) {
            if (!target.unloaded) {
                continue;
            }
            ServerChunkCache source = target.level.getChunkSource();
            if (cleared.add(target.level)) {
                sourceAccess(source).rewind$clearCache();
            }
            ChunkMapAccess access = mapAccess(source.chunkMap);
            // 区块类型缓存也要作废：不然异步读回来的区块可能沿用「改世界之后」的那份类型
            access.rewind$chunkTypeCache().remove(target.pos.toLong());
            try {
                source.getChunk(target.pos.x, target.pos.z, ChunkStatus.FULL, true);
                result.reloadedChunks++;
            } catch (Throwable t) {
                Rewind.LOGGER.error("Rewind: failed to reload chunk {} in {}",
                        target.pos, target.level.dimension().location(), t);
            }
        }
        result.loadMs = millisSince(startedNanos);

        // 实体重新读盘走的是后台队列，给它几轮时间落地（晚一两 tick 也无所谓，过渡还在盖着）。
        // 没有待卸载的区块时没什么可等；有的话也只在「这一轮没推进」时才让出时间——
        // 原来 40 轮无条件 park 200µs（光等待 8ms 起步），还每轮对三个维度各 tick 一次。
        long settleNanos = System.nanoTime();
        int lastPending = Integer.MAX_VALUE;
        for (int round = 0; round < ENTITY_SETTLE_ROUNDS; round++) {
            tickEntityManagers(server);
            int pending = 0;
            for (ServerLevel level : server.getAllLevels()) {
                pending += entityManagerAccess(level).rewind$chunksToUnload().size();
            }
            if (pending == 0 && round >= 1) {
                // 至少 tick 两轮，保留原来「晚一两 tick」的语义
                break;
            }
            if (pending >= lastPending) {
                LockSupport.parkNanos(PARK_NANOS);
            }
            lastPending = pending;
        }
        result.settleMs = millisSince(settleNanos);
    }

    /**
     * 强制标脏与直写保障（第 13 步）：启动自然持久化收敛。
     *
     * <p>原版只有 {@code isUnsaved()} 的区块才会落盘，且 {@code saveAllChunks} 只遍历
     * 可见集合并检查 {@code wasAccessibleSinceLastSave}（退出世界时 {@code flush=true} 也一样）。
     * 所以回滚重载完成后，对所有目标显式 {@code setUnsaved(true)} 并刷新可访问性；
     * 存档数据全部 {@code setDirty(true)}。
     *
     * <p>再在丢弃窗口之外显式直写一遍目标区块：盘面立即成为快照内容（进程崩溃也不丢回滚），
     * 不受 {@code saveAllChunks} 的可见性/冷却过滤影响。直写走原版标准管道、写监听照常记账——
     * 覆盖层已在前一步撤装清账，这些写入就是之后增量建点的「新基线」。
     * 目标区块在重载步骤已全部同步读回内存；这里仍拿不到内存区块的坐标（重载失败）只能记一笔，
     * 交给退出世界的生命周期落盘兜底。
     */
    private static void forceDirty(MinecraftServer server, List<Target> targets) {
        for (Target target : targets) {
            try {
                ChunkMap chunkMap = target.level.getChunkSource().chunkMap;
                ChunkMapAccess access = mapAccess(chunkMap);
                var holder = access.rewind$getUpdatingChunkIfPresent(target.pos.toLong());
                if (holder == null) {
                    continue;
                }
                if (holder.getLatestChunk() instanceof LevelChunk chunk) {
                    chunk.setUnsaved(true);
                    holder.refreshAccessibility();
                }
            } catch (Throwable t) {
                Rewind.LOGGER.warn("Rewind: failed to mark chunk {} dirty after rollback", target.pos, t);
            }
        }
        for (ServerLevel level : server.getAllLevels()) {
            try {
                for (SavedData data : dimensionStorageAccess(level.getDataStorage()).rewind$cache().values()) {
                    if (data != null) {
                        data.setDirty(true);
                    }
                }
            } catch (Throwable t) {
                Rewind.LOGGER.warn("Rewind: failed to mark saved data dirty after rollback", t);
            }
        }
        // 直写保障：丢弃窗口之外走一遍标准落盘（写监听记下回滚后的状态，时间线对齐）。
        // 显式落盘不受 saveAllChunks 的可见性/冷却过滤影响，退出世界前盘上已是快照内容。
        Rewind.endDiscard();
        int missing = 0;
        for (Target target : targets) {
            try {
                ChunkMap chunkMap = target.level.getChunkSource().chunkMap;
                ChunkMapAccess access = mapAccess(chunkMap);
                var holder = access.rewind$getUpdatingChunkIfPresent(target.pos.toLong());
                if (holder == null) {
                    // 重载失败的坐标：内存里没有区块可写，交给退出世界的生命周期落盘兜底
                    missing++;
                    continue;
                }
                if (holder.getLatestChunk() instanceof LevelChunk chunk) {
                    access.rewind$saveDirect(chunk);
                    holder.refreshAccessibility();
                }
            } catch (Throwable t) {
                Rewind.LOGGER.warn("Rewind: failed to directly save chunk {} after rollback", target.pos, t);
            }
        }
        if (missing > 0) {
            Rewind.LOGGER.warn(
                    "Rewind: {} target chunks are not in memory after rollback; leaving them to the world-exit save",
                    missing);
        }
        // 直写只是进队列：排空才真正落盘，否则崩在队列里就丢了
        try {
            flushWorkers(server);
        } finally {
            // 直写完成 = 新基线：写监听记下的就是之后的增量起点，撤装后重记
            RewindState.clearStorageJournal();
        }
    }

    // ------------------------------------------------------------------ 5. 内存状态

    /**
     * 玩家 / 时间天气。位置见 {@link #run} 里的注释：必须排在 holder 重建之后、区块成批重发之前。
     */
    private static void restoreWorldAndPlayers(MinecraftServer server, Result result) {
        long stepNanos = System.nanoTime();
        try {
            result.restoredWorldData = restoreWorldData(server);
        } catch (Throwable t) {
            Rewind.LOGGER.error("Rewind: failed to restore world data in place", t);
        }
        result.stateWorldMs = millisSince(stepNanos);
        stepNanos = System.nanoTime();
        try {
            result.restoredPlayers = restorePlayers(server);
        } catch (Throwable t) {
            Rewind.LOGGER.error("Rewind: failed to restore players in place", t);
        }
        result.statePlayersMs = millisSince(stepNanos);
    }

    /** 时间 / 天气 / 出生点来自 level.dat；原地回滚不改磁盘上的 level.dat，直接把值写进活着的 WorldData。 */
    private static boolean restoreWorldData(MinecraftServer server) {
        String key = cc.sighs.mixin.DataJournalMixins.savedataKey(
                server.getWorldPath(LevelResource.LEVEL_DATA_FILE).toFile());
        if (!RewindState.dataCover().covers(key)) {
            return false;
        }
        Object served = RewindState.dataCover().serve(key);
        if (!(served instanceof CompoundTag levelTag)) {
            return false;
        }
        CompoundTag data = levelTag.getCompound("Data");
        ServerLevel overworld = server.overworld();
        if (overworld.getLevelData() instanceof ServerLevelData levelData) {
            // 游戏刻在 level.dat 里的键是 "Time"，不是 "GameTime"——后者只是 getGameTime() 那个 getter
            // 的名字，磁盘上从来不存在这个键（PrimaryLevelData.createTag 写的是 putLong("Time", ...)）。
            // 读错键会让这个 if 永不成立，回滚就静默不还原世界时间。
            if (data.contains("Time", 99)) {
                levelData.setGameTime(data.getLong("Time"));
            }
            if (data.contains("DayTime", 99)) {
                levelData.setDayTime(data.getLong("DayTime"));
            }
        }
        overworld.setWeatherParameters(
                data.getInt("clearWeatherTime"),
                data.getInt("rainTime"),
                data.getBoolean("raining"),
                data.getBoolean("thundering"));
        if (data.contains("SpawnX", 99) && data.contains("SpawnY", 99) && data.contains("SpawnZ", 99)) {
            overworld.setDefaultSpawnPos(
                    new BlockPos(data.getInt("SpawnX"), data.getInt("SpawnY"), data.getInt("SpawnZ")),
                    data.getFloat("SpawnAngle"));
        }
        server.forceTimeSynchronization();
        return true;
    }

    /** 玩家状态从快照的 playerdata 读回来；原版没有「重读玩家」的入口，只能 load 之后手动补齐客户端同步。 */
    private static int restorePlayers(MinecraftServer server) {
        int restored = 0;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            String key = cc.sighs.mixin.DataJournalMixins.playerKey(player.getStringUUID());
            if (!RewindState.dataCover().covers(key)) {
                continue;
            }
            Object served = RewindState.dataCover().serve(key);
            if (!(served instanceof CompoundTag tag)) {
                continue;
            }
            restorePlayer(server, player, tag.copy());
            restored++;
        }
        return restored;
    }

    private static void restorePlayer(MinecraftServer server, ServerPlayer player, CompoundTag tag) {
        // 先把「改世界之后加的 buff」清掉：客户端会收到对应的移除包
        player.removeAllEffects();
        if (!RewindServerConfig.syncRecipeBook()) {
            // 配方书默认不管：ServerPlayer.readAdditionalSaveData 会把 NBT 里的 recipeBook 读回来
            // （ServerRecipeBook.fromNbt 要按名字逐条查配方表），实测这一下就是 150–600ms。
            // 关着时连读都不读——服务端与客户端两边都保持回溯前的状态，等于完全不管这东西。
            tag.remove("recipeBook");
        }
        player.load(tag);
        if (tag.contains("playerGameType", 99)) {
            player.setGameMode(GameType.byId(tag.getInt("playerGameType")));
        }

        // 维度可能与建点时不在一起：那种情况要走跨维度传送，否则客户端的世界不会跟着换
        ServerLevel savedLevel = dimensionOf(server, tag);
        Vec3 position = player.position();
        if (savedLevel != null && savedLevel != player.serverLevel()) {
            player.teleportTo(savedLevel, position.x, position.y, position.z, player.getYRot(), player.getXRot());
        } else {
            player.connection.teleport(position.x, position.y, position.z, player.getYRot(), player.getXRot(), Set.of());
        }

        // 快捷栏选中槽位也得推给客户端：load() 只改了服务端这一份，客户端还停在它自己的槽位上。
        // 物品栏内容是一起回滚的，两边槽位不一致就直接表现为「客户端拿着 A、服务端用的是 B」。
        player.connection.send(new ClientboundSetCarriedItemPacket(player.getInventory().selected));

        // load() 只把 NBT 读进内存，效果列表不会自己同步；清一遍再逐个 addEffect 才会发给客户端
        List<MobEffectInstance> effects = new ArrayList<>(player.getActiveEffects());
        player.removeAllEffects();
        for (MobEffectInstance effect : effects) {
            player.addEffect(effect);
        }

        player.inventoryMenu.broadcastFullState();
        if (player.containerMenu != player.inventoryMenu) {
            player.containerMenu.broadcastFullState();
        }
        player.onUpdateAbilities();
        player.setExperienceLevels(player.experienceLevel);
        player.setExperiencePoints(player.totalExperience);
        // 血量 / 饥饿 / 经验值靠 ServerPlayer.tick() 里「和上次发的比」的逻辑重发，这里把基线清掉
        player.resetSentInfo();
        // 配方书默认不管（RewindServerConfig.syncRecipeBook()，默认 false）：重发整份配方书在大整合包里
        // 可能几百毫秒，而它只影响客户端那份显示——服务端那份已经随 playerdata 一起回滚了。
        if (RewindServerConfig.syncRecipeBook()) {
            player.getRecipeBook().sendInitialRecipeBook(player);
        }
    }

    private static ServerLevel dimensionOf(MinecraftServer server, CompoundTag tag) {
        Tag dimension = tag.get("Dimension");
        if (dimension == null) {
            return null;
        }
        return Level.RESOURCE_KEY_CODEC.parse(NbtOps.INSTANCE, dimension)
                .result()
                .map(server::getLevel)
                .orElse(null);
    }

    /** NeoForge 等级数据附件的存档 id（= {@code <维度>/data/neoforge_data_attachments.dat}）。 */
    private static final String LEVEL_ATTACHMENTS_FILE_ID = "neoforge_data_attachments";

    /**
     * 原版（含 NeoForge 自己）会往 {@code <维度>/data/} 里写的那些存档数据 id。
     * 不在这份名单里的就算「模组数据」——原地回滚只能保证它们的**磁盘文件**被还原。
     */
    private static final Set<String> VANILLA_SAVED_DATA = Set.of(
            ScoreboardSaveData.FILE_ID, "raids", "raids_end", "random_sequences", "chunks", "idcounts",
            LEVEL_ATTACHMENTS_FILE_ID);

    /**
     * 不该被「作废缓存」摘掉的存档数据 id。
     *
     * <p>{@code DimensionDataStorage.save()} **只遍历它自己的缓存**，所以条目一旦被摘掉又没人重新登记，
     * 那个 {@code .dat} 就再也不会被写（静默丢数据）。下面这些正是被长生命周期对象用**字段**（或脏标记回调）
     * 钉住的——不会再有人来 {@code computeIfAbsent} 一次：
     *
     * <ul>
     *   <li>{@code raids} / {@code raids_end}：{@code ServerLevel.raids}（final 字段）。</li>
     *   <li>{@code scoreboard}：{@code ServerScoreboard.dirtyListeners} 里存着它的脏标记回调。</li>
     *   <li>{@code random_sequences}：{@code ServerLevel.randomSequences}（final 字段；几个维度共用主世界那一份）。</li>
     *   <li>{@code neoforge_data_attachments}：{@code init()} 只调一次，之后没人再取它。</li>
     * </ul>
     *
     * <p>其中袭击 / 记分板 / 等级附件下面会**显式重建**，内容照样回滚；剩下的（随机序列、以及老存档的结构
     * 索引）只保住「继续落盘」，内容不回滚——归入「已知边界」。
     */
    private static final Set<String> PROTECTED_SAVED_DATA = Set.of(
            "raids", "raids_end", ScoreboardSaveData.FILE_ID, "random_sequences", LEVEL_ATTACHMENTS_FILE_ID);

    /**
     * 把「内存里的存档数据」拉回存档点那一刻。
     *
     * <p>做法是**先作废缓存、再从覆盖层重读**：把每个维度的 {@code DimensionDataStorage} 缓存里
     * 「没被强引用」的条目摘掉（见 {@link #PROTECTED_SAVED_DATA}），之后任何 {@code computeIfAbsent}
     * 都会经读拦截拿到快照内容（覆盖层武装中；对 {@code /saves} 活文件 0 读取）。
     * 这样所有「每次用时取一次」的存档数据——**包括模组写在 {@code <维度>/data/*.dat} 里的那些**——
     * 都会自动拿到快照内容，不需要认识任何具体模组。
     *
     * <p>被强引用的那几个要单独处理，因为它们手里是旧实例、而且不能一摘了事：
     *
     * <ul>
     *   <li><b>袭击</b>：{@code ServerLevel.raids} 是 final 字段；摘掉缓存条目再 {@code computeIfAbsent}
     *       换一个新实例（经覆盖层读快照）。</li>
     *   <li><b>等级数据附件</b>：那个 SavedData 只是个壳子，真数据挂在 {@code Level} 自己的附件表上，
     *       由它的反序列化器灌回去；而反序列化是「按 key 覆盖」而不是替换，所以要先清空那张表，
     *       快照里没有的附件才不会留下来。同样必须先摘掉缓存条目，否则 {@code computeIfAbsent}
     *       直接命中旧壳子、反序列化器根本不会跑。</li>
     *   <li><b>记分板</b>：{@code MinecraftServer.createLevels} 拿的就是**主世界**那份
     *       {@code DimensionDataStorage}（{@code readScoreboard(serverlevel.getDataStorage())}）。
     *       它的缓存条目要**留着**（被脏标记回调钉着，摘掉就再也不落盘），只把内容灌回活着的记分板：
     *       先清空 objective / team，再按快照文件 {@code load} 一遍。{@code Scoreboard.addObjective} 对重名
     *       会抛异常（原版日志里那句 {@code Error loading saved data: scoreboard}），所以必须先清空；
     *       也没走 {@code dataFactory().deserializer()}——那条路每次都会 {@code createData()} 出一个新实例、
     *       往 {@code dirtyListeners} 里多加一条回调。</li>
     * </ul>
     *
     * <p>最后通知模组侧丢掉它们自己缓存的解码结果（见 {@link SophisticatedCoreCompat}）。
     *
     * <p>骨架（固定的「作废 → 重建 → 记日志 → 通知模组侧丢缓存」顺序、日志文案、{@code isSavedDataFile}
     * 判据）在 common 的 {@link SavedDataRollback}；本类只提供下面 {@link SavedDataHook} 那四处版本钩子。
     *
     * @param dataFiles 本次覆盖到的存档数据文件相对路径（灌装时收集，给日志用）
     */
    private static void restoreSavedData(MinecraftServer server, List<String> dataFiles) {
        SavedDataRollback.restore(new SavedDataHook(server), dataFiles);
    }

    /**
     * 这次回滚里与版本相关的那几处（见 {@link SavedDataRollback.Hook}）：缓存藏在哪个字段、怎么重建、
     * 文件名怎么映射成 id、原版 id 白名单各有哪些。
     */
    private static final class SavedDataHook implements SavedDataRollback.Hook {
        private final MinecraftServer server;

        SavedDataHook(MinecraftServer server) {
            this.server = server;
        }

        /** 作废「没被强引用」的条目：下一次访问经覆盖层读快照；返回摘掉的条数。 */
        @Override
        public int purge() {
            int removed = 0;
            for (ServerLevel level : server.getAllLevels()) {
                Map<String, SavedData> cache = cacheOf(level.getDataStorage());
                int before = cache.size();
                cache.keySet().removeIf(id -> !isProtectedSavedData(id));
                removed += before - cache.size();
            }
            return removed;
        }

        /** 重建被强引用、又能从覆盖层读快照内容的那两类（袭击 / 等级数据附件），外加记分板。 */
        @Override
        public void rebuild() {
            // 袭击 / 等级数据附件：摘掉缓存条目，再让它们经覆盖层读快照
            for (ServerLevel level : server.getAllLevels()) {
                DimensionDataStorage storage = level.getDataStorage();
                String raidsId = Raids.getFileId(level.dimensionTypeRegistration());
                cacheOf(storage).remove(raidsId);
                levelAccess(level).rewind$setRaids(storage.computeIfAbsent(Raids.factory(level), raidsId));
                cacheOf(storage).remove(LEVEL_ATTACHMENTS_FILE_ID);
                clearLevelAttachments(level);
                LevelAttachmentsSavedData.init(level);
            }
            // 记分板：缓存条目留着，只把内容灌回活着的记分板（数据来自内存覆盖层）
            restoreScoreboard(server);
        }

        @Override
        public String idOf(String mirroredFile) {
            return savedDataId(mirroredFile);
        }

        @Override
        public boolean isVanillaId(String id) {
            return isVanillaSavedData(id);
        }
    }

    /**
     * 记分板：先清空活着的记分板，再把（已被快照覆盖的）文件灌回去。
     *
     * <p>灌的是**活着的那个记分板对象**——缓存里那条 {@code ScoreboardSaveData} 包的就是它，
     * 所以内容一改，下一次自动保存写出去的就是快照内容；反之缓存条目不能摘，摘了就不再落盘。
     * 这里故意 {@code new} 一个临时实例来 {@code load}：构造函数不加脏标记回调（加回调的是
     * {@code ServerScoreboard.createData()}），不会随着回滚次数越攒越多。
     */
    private static void restoreScoreboard(MinecraftServer server) {
        DimensionDataStorageAccess access =
                (DimensionDataStorageAccess) (Object) server.overworld().getDataStorage();
        File dataFolder = access.rewind$dataFolder();
        if (dataFolder == null) {
            return;
        }
        String key = cc.sighs.mixin.DataJournalMixins.savedataKey(new File(dataFolder, "scoreboard.dat"));
        if (!RewindState.dataCover().covers(key)) {
            return;
        }
        Object served = RewindState.dataCover().serve(key);
        if (!(served instanceof CompoundTag full)) {
            return;
        }
        CompoundTag data = full.getCompound("data");
        ServerScoreboard scoreboard = server.getScoreboard();
        for (Objective objective : new ArrayList<>(scoreboard.getObjectives())) {
            scoreboard.removeObjective(objective);
        }
        for (PlayerTeam team : new ArrayList<>(scoreboard.getPlayerTeams())) {
            scoreboard.removePlayerTeam(team);
        }
        new ScoreboardSaveData(scoreboard).load(data, server.registryAccess());
    }

    /**
     * 见 {@link #PROTECTED_SAVED_DATA}；另外老世界里那份**结构索引**的 id 是 {@code *_index}
     * （旧版结构存档留下的，见类注释的「已知边界」），它也得保住缓存条目，用后缀兜住。
     */
    private static boolean isProtectedSavedData(String id) {
        return SavedDataRollback.matchesSuffixes(id, PROTECTED_SAVED_DATA, Set.of("_index"));
    }

    /** 清掉 {@code Level} 内存里那张附件表（反序列化器只按 key 覆盖，不清的话快照里没有的附件会留下来）。 */
    private static void clearLevelAttachments(ServerLevel level) {
        Map<?, ?> attachments = ((AttachmentHolderAccess) (Object) level).rewind$attachments();
        if (attachments != null) {
            attachments.clear();
        }
    }

    /** 文件名去掉目录与 {@code .dat} 就是 id（见 {@code DimensionDataStorage.getDataFile}）。 */
    /** 存档数据文件（相对世界根）→ id：1.20.1 / 1.21.1 的扁平布局映射在 common，这里只转发。 */
    private static String savedDataId(String relative) {
        return SavedDataRollback.flatIdOf(relative);
    }

    /** 白名单之外还要按前缀认的两类：地图（{@code map_<n>}）与每个维度一份的袭击（{@code raids_<...>}）。 */
    private static boolean isVanillaSavedData(String id) {
        return SavedDataRollback.matchesPrefixes(id, VANILLA_SAVED_DATA, Set.of("map_", "raids_"));
    }

    private static Map<String, SavedData> cacheOf(DimensionDataStorage storage) {
        return dimensionStorageAccess(storage).rewind$cache();
    }

    // ------------------------------------------------------------------ 访问器包装

    private static long millisSince(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }

    private static ChunkMapAccess mapAccess(ChunkMap chunkMap) {
        return (ChunkMapAccess) (Object) chunkMap;
    }

    private static ChunkStorageAccess chunkStorageAccess(ChunkMap chunkMap) {
        return (ChunkStorageAccess) (Object) chunkMap;
    }

    private static DistanceManagerAccess distanceAccess(net.minecraft.server.level.DistanceManager manager) {
        return (DistanceManagerAccess) (Object) manager;
    }

    private static ServerChunkCacheAccess sourceAccess(ServerChunkCache source) {
        return (ServerChunkCacheAccess) (Object) source;
    }

    private static ServerLevelAccess levelAccess(ServerLevel level) {
        return (ServerLevelAccess) (Object) level;
    }

    private static EntitySectionManagerAccess entityManagerAccess(ServerLevel level) {
        return (EntitySectionManagerAccess) (Object) levelAccess(level).rewind$entityManager();
    }

    private static EntityStorageAccess entityStorageAccess(EntityStorage storage) {
        return (EntityStorageAccess) (Object) storage;
    }

    private static SectionStorageAccess sectionStorageAccess(PoiManager poi) {
        return (SectionStorageAccess) (Object) poi;
    }

    private static SimpleRegionStorageAccess simpleRegionStorageAccess(SimpleRegionStorage storage) {
        return (SimpleRegionStorageAccess) (Object) storage;
    }

    private static IOWorkerAccess ioWorkerAccess(IOWorker worker) {
        return (IOWorkerAccess) (Object) worker;
    }

    private static RegionFileStorageAccess regionFileStorageAccess(RegionFileStorage storage) {
        return (RegionFileStorageAccess) (Object) storage;
    }

    private static DimensionDataStorageAccess dimensionStorageAccess(DimensionDataStorage storage) {
        return (DimensionDataStorageAccess) (Object) storage;
    }
}
