package cc.sighs.rewind.server;

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
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.locks.LockSupport;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import cc.sighs.mixin.RollbackAccessMixins.AttachmentHolderAccess;
import cc.sighs.mixin.RollbackAccessMixins.ChunkMapAccess;
import cc.sighs.mixin.RollbackAccessMixins.DistanceManagerAccess;
import cc.sighs.mixin.RollbackAccessMixins.EntitySectionManagerAccess;
import cc.sighs.mixin.RollbackAccessMixins.EntityStorageAccess;
import cc.sighs.mixin.RollbackAccessMixins.GenerationChunkHolderAccess;
import cc.sighs.mixin.RollbackAccessMixins.IOWorkerAccess;
import cc.sighs.mixin.RollbackAccessMixins.RegionFileStorageAccess;
import cc.sighs.mixin.RollbackAccessMixins.SavedDataStorageAccess;
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
import cc.sighs.rewind.snapshot.SnapshotBlocks;
import cc.sighs.rewind.snapshot.SnapshotChunkDecoder;
import cc.sighs.rewind.snapshot.SnapshotLayout;
import cc.sighs.rewind.snapshot.ZPack;
import cc.sighs.rewind.snapshot.ZPackCheckpoint;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.network.protocol.game.ClientboundSetHeldSlotPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.ServerScoreboard;
import net.minecraft.server.bossevents.CustomBossEvents;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkLevel;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.RandomSequences;
import net.minecraft.world.Stopwatches;
import net.minecraft.world.clock.ServerClockManager;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.raid.Raids;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.TicketStorage;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.storage.EntityStorage;
import net.minecraft.world.level.chunk.storage.IOWorker;
import net.minecraft.world.level.chunk.storage.RegionFileStorage;
import net.minecraft.world.level.chunk.storage.SimpleRegionStorage;
import net.minecraft.world.level.dimension.end.EnderDragonFight;
import net.minecraft.world.level.entity.EntityAccess;
import net.minecraft.world.level.entity.EntitySectionStorage;
import net.minecraft.world.level.gamerules.GameRuleMap;
import net.minecraft.world.level.levelgen.WorldGenSettings;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import net.minecraft.world.level.saveddata.WanderingTraderData;
import net.minecraft.world.level.saveddata.WeatherData;
import net.minecraft.world.level.storage.LevelData;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.level.storage.SavedDataStorage;
import net.minecraft.world.level.storage.ServerLevelData;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.timers.TimerQueue;
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
 *   <li><b>内存状态</b>：玩家 / 时间天气单独还原（纯内存，不碰盘）；每个维度与服务器级的存档数据
 *       缓存整体作废、经读拦截从覆盖层重读，其中「被长生命周期对象强引用」的那几个
 *       （袭击、记分板、天气、等级数据附件）显式重建。</li>
 *   <li><b>强制标脏</b>：遍历回滚目标显式标脏并刷新可访问性，存档数据全部标脏，
 *       再显式直写一遍——启动自然持久化收敛，退出世界时原版落盘自动带走快照内容。</li>
 * </ol>
 *
 * <p>已知边界（比「关世界再重开」那条路少了什么）：**磁盘**与「每次用时 {@code SavedDataStorage.get}
 * 取一次」的存档数据都能回滚（含模组写在 {@code <维度>/data/<namespace>/<path>.dat} 里的那些）；
 * 但**模组自己缓存的解码结果**不归世界生命周期管，同一进程里还会被复用（典型：精妙核心的包装器缓存，
 * 见 {@link SophisticatedCoreCompat}），也没有通用手段能替它清掉。
 *
 * <p>另一条取舍写在这里：26.1 有一批原版存档数据被长生命周期对象用字段强引用（游戏规则、命令存储、
 * 流浪商人、区块票据、随机序列、世界时钟、自定义 boss 条、计划事件、秒表、末地龙战），而
 * {@code SavedDataStorage.scheduleSave()} 只遍历缓存——所以它们**故意不清缓存**（见
 * {@link #PROTECTED_SAVED_DATA}）：内容不参与回滚、但会继续正常落盘。真正回到存档点内容的只有能重新
 * 读盘的那几类——袭击、记分板、天气、等级数据附件。装了这类模组、数据看起来没回滚时，把 COMMON 配置
 * {@code rollback.inPlaceRollback} 关掉，退回「关世界 → 覆盖 → 重开」那条路——服务端对象全部重建，
 * 模组跟着世界重载走一遍。
 *
 * <h2>26.1 的实现差异（相对 {@code targets/neoforge-1.21.1}）</h2>
 * <ul>
 *   <li>{@code DimensionDataStorage} → {@code SavedDataStorage}，缓存键由 {@code String} 变成
 *       {@code SavedDataType<?>}；{@code Raids.getFileId()/factory()} 变成了 {@code Raids.TYPE}。</li>
 *   <li>记分板：{@code ServerScoreboard.dataFactory().deserializer()} 没了，改成
 *       {@code ServerScoreboard.load(ScoreboardSaveData.Packed)}，数据从
 *       {@code ScoreboardSaveData.Packed.CODEC} 反序列化。</li>
 *   <li>玩家：{@code player.load(CompoundTag)} → {@code player.load(ValueInput)}（NBT 走
 *       {@code TagValueInput}），维度从 {@code ServerPlayer.SavedPosition} 读；
 *       {@code PlayerList.placeNewPlayer} 的登录流程就是这么做的。</li>
 *   <li>时间 / 出生点：26.1 的 level.dat 里时间叫 {@code Time}（没有 {@code DayTime} 了），出生点是一个
 *       {@code LevelData.RespawnData} 编解码的 {@code spawn} 子标签。</li>
 *   <li>天气：不再是 level.dat 的字段，而是服务器级 {@code WeatherData} 这个 SavedData
 *       （{@code <存档>/data/minecraft/weather.dat}）。</li>
 *   <li>存档数据的位置：26.1 的 {@code SavedDataStorage} 按 {@code Identifier} 落成
 *       {@code <dataFolder>/<namespace>/<path>.dat}，所以这里不再硬编码 {@code data/scoreboard.dat}，
 *       一律「作废缓存 → 让存储层从已还原的磁盘重新读」，路径长什么样都无所谓。</li>
 * </ul>
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
        /** 视野外、交给原版流水线异步读回来的区块数（零磁盘架构下恒为 0：全部同步读回）。 */
        public int deferredChunks;
        public int stuckChunks;
        /** 对 `/saves` 的读/写次数（零磁盘架构的验收口：恒为 0）。 */
        public int closedRegionFiles;
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
            // 回滚窗口先关掉，而且要在做任何别的事之前。RewindApi 在调进来之前就把它打开了，而窗口开着的时候
            // RegionFileStorage.write 会被整段取消。26.1 的 ChunkMap.saveChunksEagerly
            // 会先把区块的 unsaved 标记清掉、再异步落盘，如果这次异步写正好落在这个窗口里，它就被静默丢掉：
            // 内存里「已经不脏」、磁盘上「也没变」，这个区块从两个判据里同时消失，回滚当它没改过——表现为
            // 「世界回滚成功了，但某个区块原样留在改后的状态」。
            // 所以窗口只留到真正开始覆盖文件之前（下面的 beginDiscard 重新打开），中间这一段
            // （读槽位清单 / 追平写入 / 找受影响区块）必须让写入正常落地。
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

            // 零磁盘回滚：不关存储句柄（Windows 文件锁问题自然消失），不回拷文件。
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

            // 玩家 / 世界时间必须排在这里：holder 已经重建、但区块还没成批重发。
            // 位置包要排在区块包前面，否则客户端得先处理完上百个区块包才会回 ack，而服务端在收到 ack
            // 之前是关着右键交互的（handleUseItemOn 里那道 awaitingPositionFromClient 的门）。
            // 也不能更早：player.load() 会走 Entity.setPosRaw，NeoForge 在那里会 level.getChunk() 请求
            // 区块，holder 不在就会直接 NPE。
            stepNanos = System.nanoTime();
            restoreWorldAndPlayers(server, slotDir, result);
            result.stateMs = millisSince(stepNanos);

            stepNanos = System.nanoTime();
            loadChunks(server, targets, result);
            result.reloadMs += millisSince(stepNanos);

            try {
                restoreSavedData(server, dataFiles);
            } catch (Throwable t) {
                Rewind.LOGGER.error("Rewind: failed to reload saved data in place", t);
            }

            stepNanos = System.nanoTime();
            forceDirty(server, targets);
            result.forceDirtyMs = millisSince(stepNanos);
        } finally {
            // 先撤装再开写：撤装连账本一起清（回滚完成=新基线，之后重记）。
            // 命中数是稀疏拦截的验收口——大于 0 说明重载真的走了覆盖层。
            long coverHits = RewindState.storageJournal().serveHits() + RewindState.dataCover().serveHits();
            result.coverHits = coverHits;
            RewindState.disarmStorageCover();
            Rewind.LOGGER.info("Rewind: storage cover served {} reads ({} chunks covered)",
                    coverHits, coveredChunks);
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
            String dimensionId = level.dimension().identifier().toString();
            ChunkMap chunkMap = level.getChunkSource().chunkMap;
            List<Path> snapshotDirs = snapshotRegionDirs(server, level, slotDir);
            EntitySectionStorage<Entity> sections = entityManagerAccess(level).rewind$sectionStorage();
            // 26.1 删掉了 ChunkMap.getChunks()，直接读 visibleChunkMap（它原本就是那个方法的数据源）
            for (ChunkHolder holder : mapAccess(chunkMap).rewind$visibleChunks().values()) {
                if (holder.getTicketLevel() > ChunkLevel.MAX_LEVEL) {
                    // 已经在卸载路上：内存里的内容马上就会自己丢掉，不用管
                    continue;
                }
                if (!(holder.getLatestChunk() instanceof LevelChunk chunk)) {
                    continue;
                }
                result.scannedChunks++;
                ChunkPos pos = chunk.getPos();
                long key = pos.pack();
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
            if (chunkMap.getUpdatingChunkIfPresent(entry.chunkPos) != null) {
                continue;
            }
            targets.add(new Target(level, ChunkPos.unpack(entry.chunkPos)));
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
            if (level.dimension().identifier().toString().equals(dimensionId)) {
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
        return sections.getExistingSectionsInChunk(pos.pack())
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
        int[] header = RegionHeader.fromSnapshot(cache, store, slotDir, blocks, snapshotDirs.get(1), regionFileName(pos));
        return header[(pos.x() & 31) + (pos.z() & 31) * 32] != 0;
    }

    private static String regionFileName(ChunkPos pos) {
        return "r." + pos.getRegionX() + "." + pos.getRegionZ() + ".mca";
    }

    /** 这个维度里所有「按区块存 region 文件」的存储目录：区块 / 实体 / POI。 */
    private static List<Path> liveRegionDirs(ServerLevel level) {
        ServerChunkCache source = level.getChunkSource();
        List<Path> dirs = new ArrayList<>(3);
        // 26.1：ChunkMap 自己继承了 SimpleRegionStorage，worker 字段就在那里
        addFolder(dirs, simpleRegionStorageAccess(source.chunkMap).rewind$worker());
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
        covered += populateDataCover(server, worldRoot, slotDir, zpackCover, dataFiles);
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
        NeoForge261RewindPlatform platform = new NeoForge261RewindPlatform();
        String dimensionId = level.dimension().identifier().toString();
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
            SnapshotBlockStore store, Map<String, byte[]> zpackCover, NeoForge261RewindPlatform platform,
            String dir, StorageKind kind, String dimensionId, ChunkPos pos, Result result) {
        // 写前冻结优先：冷区块在建点后第一次被改写前留下的基底，本身就是快照数据
        Object frozen = RewindState.frozenBase(kind, dimensionId, pos.pack());
        if (frozen instanceof CompoundTag) {
            RewindState.storageJournal().onWritten(kind, dimensionId, pos.pack(), ((CompoundTag) frozen).copy());
            return true;
        }
        // zpack 快路：命中即喂账本；未命中/解码失败一律回退旧路（链洞≠删除）。
        // 26.1 的账本坐标是 pack() 布局（见平台 unpackChunkPos 覆盖），键必须同形。
        if (zpackCover != null) {
            String key = ZPack.keyOf(ZPackCheckpoint.kindToType(kind), dimensionId, pos.pack());
            if (zpackCover.containsKey(key)) {
                try {
                    byte[] raw = zpackCover.get(key);
                    if (raw == null || raw.length == 0) {
                        RewindState.storageJournal().onWritten(kind, dimensionId, pos.pack(), null);
                        result.coverZpack++;
                        return true;
                    }
                    Object tag = platform.decodeChunkSector(raw);
                    if (tag != null) {
                        RewindState.storageJournal().onWritten(kind, dimensionId, pos.pack(), tag);
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
                sector = SnapshotChunkDecoder.readSector(store, blocks, mcaRelative, pos.x(), pos.z());
                if (sector != null && sector.external) {
                    String mccRelative = SnapshotChunkDecoder.mccRelative(mcaRelative, pos.x(), pos.z());
                    externalPayload = SnapshotChunkDecoder.readExternalPayload(store, blocks, mccRelative);
                }
            } else {
                Path slotFile = slotDir.resolve(mcaRelative);
                sector = SnapshotChunkDecoder.readSectorFromFile(slotFile, pos.x(), pos.z());
                if (sector != null && sector.external) {
                    Path slotMcc = slotDir.resolve(SnapshotChunkDecoder.mccRelative(mcaRelative, pos.x(), pos.z()));
                    externalPayload = SnapshotChunkDecoder.readExternalPayloadFromFile(slotMcc);
                }
            }
            if (sector == null) {
                // 快照里没有这个区块：记删除标记，读拦截短路喂空
                RewindState.storageJournal().onWritten(kind, dimensionId, pos.pack(), null);
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
            RewindState.storageJournal().onWritten(kind, dimensionId, pos.pack(), tag);
            return true;
        } catch (Throwable t) {
            Rewind.LOGGER.warn("Rewind: cannot read checkpoint chunk {} from {}, skipping cover for it",
                    pos, mcaRelative, t);
            return false;
        }
    }

    /**
     * 非区块 NBT 灌装：把槽位的 {@code .dat} 读进数据覆盖层（快照目录，允许）。
     *
     * <p>数据源二选一：优先 zpack 快路（按世界根相对路径匹配 {@code TYPE_DATA_FILE}，
     * 载荷为未压缩 NBT 字节，命中即解码灌装）；未命中或解码失败回退读槽位磁盘文件——
     * “zpack 里没有”永不等价于删除（链上可能有洞/老槽位无包）。
     *
     * <p>存档数据按“活文件绝对路径”拼键（与读写两端的键一致）：每个存储的
     * {@code SavedDataStorage} 数据目录（含服务器级那一份，26.1 落成
     * {@code <dataFolder>/<命名空间>/<路径>.dat}）在槽位里找同名 {@code .dat}，
     * 有就读入覆盖，没有就记删除标记（回滚后懒加载不再复活未来文件）。
     * 玩家数据只灌在线玩家的（离线玩家走登录时读盘，届时覆盖已撤——与区块冷加载不同，
     * 玩家登录必经服务端线程，可单独对账；缺席同样记删除标记）。
     *
     * @return 灌装计数；dataFiles 收集本次覆盖到的存档数据文件相对路径（世界根基准，给日志用）
     */
    private static int populateDataCover(MinecraftServer server, Path worldRoot, Path slotDir,
            Map<String, byte[]> zpackCover, List<String> dataFiles) {
        int covered = 0;
        NeoForge261RewindPlatform platform = zpackCover == null ? null : new NeoForge261RewindPlatform();
        Set<String> seenDataKeys = new HashSet<>();
        List<SavedDataStorage> storages = new ArrayList<>();
        for (ServerLevel level : server.getAllLevels()) {
            storages.add(level.getDataStorage());
        }
        storages.add(server.getDataStorage());
        for (SavedDataStorage storage : storages) {
            Path liveDir = storageAccess(storage).rewind$dataFolder();
            if (liveDir == null) {
                continue;
            }
            Path slotDataDir;
            try {
                slotDataDir = slotDir.resolve(SnapshotLayout.relativize(worldRoot, liveDir));
            } catch (Throwable t) {
                continue;
            }
            // 槽位里有的 .dat：读入覆盖；活文件有、槽位没有的：同样要处理（删除标记），
            // 否则回滚后懒加载读到未来文件。目录枚举只做 readdir（不读内容），代价可忽略。
            // 26.1 是嵌套布局（<命名空间>/<路径>.dat，可再深），递归枚举。
            Set<String> relatives = new HashSet<>();
            collectDatRelatives(slotDataDir, relatives);
            collectDatRelatives(liveDir, relatives);
            for (String relative : relatives) {
                Path slotFile = slotDataDir.resolve(relative);
                Path liveFile = liveDir.resolve(relative);
                String key = cc.sighs.mixin.DataJournalMixins.savedataKey(liveFile);
                if (!seenDataKeys.add(key)) {
                    continue;
                }
                String zpackRelative = null;
                try {
                    zpackRelative = SnapshotLayout.relativize(worldRoot, liveFile);
                } catch (Throwable ignored) {
                    // 相对化失败只影响 zpack 快路与日志，不影响槽位盘读
                }
                // zpack 快路：命中即灌装；未命中/解码失败一律回退槽位盘读（链洞≠删除）
                if (zpackCover != null && zpackRelative != null) {
                    byte[] raw = zpackCover.get(ZPack.keyOf(ZPack.TYPE_DATA_FILE, zpackRelative, 0L));
                    if (raw != null) {
                        try {
                            if (raw.length == 0) {
                                RewindState.dataCover().onWritten(key, null);
                                covered++;
                                continue;
                            }
                            Object tag = platform.decodeChunkSector(raw);
                            if (tag instanceof CompoundTag) {
                                RewindState.dataCover().onWritten(key, ((CompoundTag) tag).copy());
                                covered++;
                                dataFiles.add(zpackRelative);
                                continue;
                            }
                            Rewind.LOGGER.warn("Rewind: zpack data entry {} undecodable, falling back to slot file",
                                    zpackRelative);
                        } catch (Throwable t) {
                            Rewind.LOGGER.warn("Rewind: zpack data entry {} failed, falling back to slot file",
                                    zpackRelative, t);
                        }
                    }
                }
                if (!Files.isRegularFile(slotFile)) {
                    RewindState.dataCover().onWritten(key, null);
                    continue;
                }
                try {
                    CompoundTag full = NbtIo.readCompressed(slotFile, NbtAccounter.unlimitedHeap());
                    RewindState.dataCover().onWritten(key, full.copy());
                    covered++;
                    if (zpackRelative != null) {
                        dataFiles.add(zpackRelative);
                    }
                } catch (Throwable t) {
                    Rewind.LOGGER.warn("Rewind: cannot read checkpoint data file {}, skipping cover for it",
                            slotFile, t);
                }
            }
        }
        // 玩家数据：在线的按槽位读入，缺席记删除标记
        Path relativePlayerData = worldRoot.relativize(server.getWorldPath(LevelResource.PLAYER_DATA_DIR));
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            String key = cc.sighs.mixin.DataJournalMixins.playerKey(player.getStringUUID());
            Path slotFile = slotDir.resolve(relativePlayerData).resolve(player.getStringUUID() + ".dat");
            String liveRelative = SnapshotLayout.relativize(worldRoot,
                    server.getWorldPath(LevelResource.PLAYER_DATA_DIR)
                            .resolve(player.getStringUUID() + ".dat"));
            // zpack 快路（世界根相对路径键）；.dat_old 不在 zpack 内，未命中照旧回退盘读
            if (zpackCover != null) {
                byte[] raw = zpackCover.get(ZPack.keyOf(ZPack.TYPE_DATA_FILE, liveRelative, 0L));
                if (raw != null) {
                    try {
                        if (raw.length == 0) {
                            RewindState.dataCover().onWritten(key, null);
                            covered++;
                            continue;
                        }
                        Object tag = platform.decodeChunkSector(raw);
                        if (tag instanceof CompoundTag) {
                            RewindState.dataCover().onWritten(key, ((CompoundTag) tag).copy());
                            covered++;
                            continue;
                        }
                        Rewind.LOGGER.warn("Rewind: zpack player entry {} undecodable, falling back to slot file",
                                liveRelative);
                    } catch (Throwable t) {
                        Rewind.LOGGER.warn("Rewind: zpack player entry {} failed, falling back to slot file",
                                liveRelative, t);
                    }
                }
            }
            if (!Files.isRegularFile(slotFile)) {
                Path oldFile = slotDir.resolve(relativePlayerData).resolve(player.getStringUUID() + ".dat_old");
                if (Files.isRegularFile(oldFile)) {
                    slotFile = oldFile;
                } else {
                    RewindState.dataCover().onWritten(key, null);
                    continue;
                }
            }
            try {
                CompoundTag tag = NbtIo.readCompressed(slotFile, NbtAccounter.unlimitedHeap());
                RewindState.dataCover().onWritten(key, tag.copy());
                covered++;
            } catch (Throwable t) {
                Rewind.LOGGER.warn("Rewind: cannot read checkpoint player data {}, skipping cover for it",
                        slotFile, t);
            }
        }
        return covered;
    }

    /** 递归收集该目录下所有 {@code .dat} 相对路径（26.1 嵌套布局用；目录不存在即空）。 */
    private static void collectDatRelatives(Path dir, Set<String> relatives) {
        if (dir == null || !Files.isDirectory(dir)) {
            return;
        }
        try (java.util.stream.Stream<Path> stream = Files.walk(dir)) {
            stream.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".dat"))
                    .forEach(path -> relatives.add(dir.relativize(path).toString().replace('\\', '/')));
        } catch (Throwable ignored) {
            // 枚举失败就当没有
        }
    }

    // ------------------------------------------------------------------ 2. 强制卸载

    private static void unload(MinecraftServer server, List<Target> targets, Result result) {
        // 先按维度登记「本轮接管的区块」：ticket 系统仍然认为它们该加载，会通过邻居传播把 holder
        // 重建出来（进而拉起 worldgen 任务、把 refCount 钉住），这段时间必须挡住重建。
        Map<ServerLevel, LongSet> guards = new LinkedHashMap<>();
        for (Target target : targets) {
            guards.computeIfAbsent(target.level, level -> new LongOpenHashSet()).add(target.pos.pack());
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
            long key = target.pos.pack();
            var holder = chunkMap.getUpdatingChunkIfPresent(key);
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
            ChunkMap chunkMap = level.getChunkSource().chunkMap;
            ChunkMapAccess access = mapAccess(chunkMap);
            for (long pos : access.rewind$toDrop()) {
                ChunkHolder holder = chunkMap.getUpdatingChunkIfPresent(pos);
                // 26.1：这一计数移到了父类 GenerationChunkHolder 上，没有公开 getter
                if (holder != null && generationAccess(holder).rewind$generationRefCount().get() != 0) {
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
     *       26.1 的 {@code SectionStorage.remove(long)} 换成了 {@code remove(ChunkPos)}，而且它会把整列
     *       分段（以及 {@code loadedChunks} 标记）一次清掉，正是这里要的粒度。</li>
     * </ul>
     */
    private static void invalidateCaches(MinecraftServer server, List<Target> targets) {
        for (ServerLevel level : server.getAllLevels()) {
            Object permanent = entityManagerAccess(level).rewind$permanentStorage();
            var emptyChunks = permanent instanceof EntityStorage storage
                    ? entityStorageAccess(storage).rewind$emptyChunks()
                    : null;
            PoiManager poi = level.getChunkSource().getPoiManager();
            for (Target target : targets) {
                if (target.level != level) {
                    continue;
                }
                if (emptyChunks != null) {
                    emptyChunks.remove(target.pos.pack());
                }
                poi.remove(target.pos);
            }
        }
    }

    // ------------------------------------------------------------------ 3. 排空写入（零磁盘：句柄不关、文件不拷）

    private static IOWorker poiWorker(PoiManager poi) {
        SimpleRegionStorage storage = sectionStorageAccess(poi).rewind$simpleRegionStorage();
        return storage == null ? null : simpleRegionStorageAccess(storage).rewind$worker();
    }

    /** 排空三条存储链（区块 / POI / 实体）排队中的写入，不 fsync。 */
    private static void flushWorkers(MinecraftServer server) {
        for (ServerLevel level : server.getAllLevels()) {
            ServerChunkCache source = level.getChunkSource();
            drainWorker(simpleRegionStorageAccess(source.chunkMap).rewind$worker());
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
            long key = target.pos.pack();
            if (!target.unloaded) {
                // 卸载时被跳过了（还有生成任务在飞）：保持原样，这个区块不会回滚，记一笔
                result.stuckChunks++;
                continue;
            }
            var holder = chunkMap.getUpdatingChunkIfPresent(key);
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
     * <p>零磁盘架构下不设“视野外延迟”：覆盖层在撤装时清空，延迟加载的区块会错过覆盖、
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
            access.rewind$chunkTypeCache().remove(target.pos.pack());
            try {
                source.getChunk(target.pos.x(), target.pos.z(), ChunkStatus.FULL, true);
                result.reloadedChunks++;
            } catch (Throwable t) {
                Rewind.LOGGER.error("Rewind: failed to reload chunk {} in {}",
                        target.pos, target.level.dimension().identifier(), t);
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
     * 强制标脏与直写保障：启动自然持久化收敛。
     *
     * <p>原版只有 {@code isUnsaved()} 的区块才会落盘，且 {@code saveAllChunks} 只遍历
     * 可见集合并检查 {@code wasAccessibleSinceLastSave}（退出世界时 {@code flush=true} 也一样）。
     * 所以回滚重载完成后，对所有目标显式 {@code setUnsaved(true)} 并刷新可访问性；
     * 存档数据全部 {@code setDirty(true)}。再在丢弃窗口之外显式直写一遍目标区块——
     * 即使之后 filtered 漏写，盘上也已经是快照内容；直写走原版标准管道，
     * 写监听自然捕获（窗口已关，记的是快照态基线），状态机自动对齐。
     */
    private static void forceDirty(MinecraftServer server, List<Target> targets) {
        for (Target target : targets) {
            try {
                ChunkMap chunkMap = target.level.getChunkSource().chunkMap;
                ChunkMapAccess access = mapAccess(chunkMap);
                var holder = chunkMap.getUpdatingChunkIfPresent(target.pos.pack());
                if (holder == null) {
                    continue;
                }
                if (holder.getLatestChunk() instanceof LevelChunk chunk) {
                    // 26.1：setUnsaved(boolean) 改成无参 markUnsaved()
                    chunk.markUnsaved();
                    holder.refreshAccessibility();
                }
            } catch (Throwable t) {
                Rewind.LOGGER.warn("Rewind: failed to mark chunk {} dirty after rollback", target.pos, t);
            }
        }
        for (ServerLevel level : server.getAllLevels()) {
            try {
                for (Optional<SavedData> data : storageAccess(level.getDataStorage()).rewind$cache().values()) {
                    data.ifPresent(d -> d.setDirty(true));
                }
            } catch (Throwable t) {
                Rewind.LOGGER.warn("Rewind: failed to mark saved data dirty after rollback", t);
            }
        }
        try {
            for (Optional<SavedData> data : storageAccess(server.getDataStorage()).rewind$cache().values()) {
                data.ifPresent(d -> d.setDirty(true));
            }
        } catch (Throwable t) {
            Rewind.LOGGER.warn("Rewind: failed to mark server saved data dirty after rollback", t);
        }
        // 直写保障：丢弃窗口之外走一遍标准落盘（写监听记下快照态，时间线对齐）。
        // 显式落盘不受 saveAllChunks 的可见性/冷却过滤影响，退出世界前盘上已是快照内容。
        Rewind.endDiscard();
        try {
            for (Target target : targets) {
                try {
                    ChunkMap chunkMap = target.level.getChunkSource().chunkMap;
                    ChunkMapAccess access = mapAccess(chunkMap);
                    var holder = chunkMap.getUpdatingChunkIfPresent(target.pos.pack());
                    if (holder == null) {
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
            // 直写只是进队列：排空才真正落盘，否则崩在队列里就丢了
            flushWorkers(server);
        } finally {
            RewindState.clearStorageJournal();
        }
    }

    // ------------------------------------------------------------------ 5. 内存状态

    /**
     * 玩家 / 时间天气。位置见 {@link #run} 里的注释：必须排在 holder 重建之后、区块成批重发之前。
     */
    private static void restoreWorldAndPlayers(MinecraftServer server, Path slotDir, Result result) {
        long stepNanos = System.nanoTime();
        try {
            result.restoredWorldData = restoreWorldData(server, slotDir);
        } catch (Throwable t) {
            Rewind.LOGGER.error("Rewind: failed to restore world data in place", t);
        }
        result.stateWorldMs = millisSince(stepNanos);
        stepNanos = System.nanoTime();
        try {
            result.restoredPlayers = restorePlayers(server, slotDir);
        } catch (Throwable t) {
            Rewind.LOGGER.error("Rewind: failed to restore players in place", t);
        }
        result.statePlayersMs = millisSince(stepNanos);
    }

    /**
     * 世界时间 / 出生点来自 level.dat。这里不改磁盘上的任何文件，直接把快照值写进活着的
     * {@code WorldData}。
     *
     * <p>天气不在这里：26.1 把它挪进了服务器级 {@code WeatherData} SavedData，由 {@link #restoreWeather}
     * 在存档数据重建那一步统一处理（必须排在那一步的缓存作废之后，否则刚重建好的条目会被清掉）。
     */
    private static boolean restoreWorldData(MinecraftServer server, Path slotDir) throws IOException {
        boolean restored = false;
        Path file = slotDir.resolve("level.dat");
        if (Files.isRegularFile(file)) {
            CompoundTag data = NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap()).getCompoundOrEmpty("Data");
            // 用 WorldData.overworldData() 而不是 level.getLevelData()：非主世界拿到的是
            // DerivedLevelData，它的 setGameTime 是个空实现
            ServerLevelData levelData = server.getWorldData().overworldData();
            if (data.contains("Time")) {
                // 26.1 删掉了 DayTime，世界时间就是 level.dat 里的 Time
                levelData.setGameTime(data.getLongOr("Time", levelData.getGameTime()));
            }
            Tag spawn = data.get("spawn");
            if (spawn != null) {
                LevelData.RespawnData respawn = LevelData.RespawnData.CODEC
                        .parse(NbtOps.INSTANCE, spawn)
                        .result()
                        .orElse(null);
                if (respawn != null) {
                    levelData.setSpawn(respawn);
                }
            }
            restored = true;
        }
        // 26.1：forceTimeSynchronization() 改名成 forceGameTimeSynchronization()，顺带带上 clocks
        server.forceGameTimeSynchronization();
        return restored;
    }

    /**
     * 天气：作废服务器级 {@code SavedDataStorage} 里的 {@code WeatherData} 缓存，让它从（已被快照
     * 覆盖的）磁盘重新读，再把值灌回活着的实例。
     *
     * <p>不硬编码 {@code data/weather.dat}：26.1 的 SavedData 落盘路径是
     * {@code <dataFolder>/<namespace>/<path>.dat}，走存储层就与路径规则无关。
     *
     * <p>{@code MinecraftServer.weatherData} 是 final 字段，读（{@code getWeatherData}）与每一 tick 的
     * 天气推进都用那个活着的实例，所以内容灌回去之后还得把**那个实例**登记进缓存——只 {@code get} 的话
     * 缓存里放的是刚从磁盘读出来的新实例，活着的那个反而没人遍历，之后天气变化就再也不落盘。
     *
     * <p>调用点：{@link #restoreSavedData} 清空缓存之后。
     */
    private static void restoreWeather(MinecraftServer server) {
        SavedDataStorage storage = server.getDataStorage();
        WeatherData saved = storage.get(WeatherData.TYPE);
        if (saved == null) {
            return;
        }
        WeatherData live = server.getWeatherData();
        live.setClearWeatherTime(saved.getClearWeatherTime());
        live.setRainTime(saved.getRainTime());
        live.setThunderTime(saved.getThunderTime());
        live.setRaining(saved.isRaining());
        live.setThundering(saved.isThundering());
        cacheOf(storage).put(WeatherData.TYPE, Optional.of((SavedData) live));
    }

    /**
     * 玩家状态从快照的玩家数据文件读回来；原版没有「重读玩家」的入口，只能 load 之后手动补齐客户端同步。
     *
     * <p>26.1 把玩家数据从 {@code playerdata/<uuid>.dat} 搬到了 {@code players/data/<uuid>.dat}
     * （{@code LevelResource.PLAYER_DATA_DIR}）。这里不写死目录名：按原版那套算出玩家数据目录，
     * 再换算成快照槽位里的相对路径，免得版本一挪位置就整段静默失效（{@code restored=0}，
     * 表现就是「世界回去了、玩家自己的位置和背包没回去」）。
     */
    private static int restorePlayers(MinecraftServer server, Path slotDir) throws IOException {
        int restored = 0;
        Path worldRoot = server.getWorldPath(LevelResource.LEVEL_DATA_FILE).getParent();
        Path relativePlayerData = worldRoot.relativize(server.getWorldPath(LevelResource.PLAYER_DATA_DIR));
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            Path file = slotDir.resolve(relativePlayerData).resolve(player.getUUID() + ".dat");
            if (!Files.isRegularFile(file)) {
                continue;
            }
            restorePlayer(server, player, NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap()));
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
            // 26.1 走 ValueInput，摘除必须在 TagValueInput.create 之前对 CompoundTag 做。
            tag.remove("recipeBook");
        }

        // 26.1：player.load(CompoundTag) 换成了 Entity.load(ValueInput)；维度从 SavedPosition 读
        // （原版登录流程 PrepareSpawnTask 也是这么做的），坐标 / 朝向由 load 自己从 Pos/Rotation 读
        ServerPlayer.SavedPosition savedPosition = ServerPlayer.SavedPosition.MAP_CODEC.codec()
                .parse(NbtOps.INSTANCE, tag)
                .result()
                .orElse(ServerPlayer.SavedPosition.EMPTY);
        ValueInput input = TagValueInput.create(ProblemReporter.DISCARDING, player.level().registryAccess(), tag);
        player.load(input);
        // 游戏模式不用单独写：ServerPlayer.readAdditionalSaveData 会从 playerGameType 读回来

        // 维度可能与建点时不在一起：那种情况要走跨维度传送，否则客户端的世界不会跟着换
        ServerLevel savedLevel = savedPosition.dimension().map(server::getLevel).orElse(null);
        Vec3 position = player.position();
        if (savedLevel != null && savedLevel != player.level()) {
            player.teleportTo(savedLevel, position.x, position.y, position.z, Set.of(), player.getYRot(), player.getXRot(), false);
        } else {
            player.connection.teleport(position.x, position.y, position.z, player.getYRot(), player.getXRot());
        }

        // 快捷栏选中槽位也得推给客户端：load() 只改了服务端这一份，客户端还停在它自己的槽位上。
        // 物品栏内容是一起回滚的，两边槽位不一致就直接表现为「客户端拿着 A、服务端用的是 B」。
        // 26.1：ClientboundSetCarriedItemPacket → ClientboundSetHeldSlotPacket，
        // 读的也从 Inventory.selected 字段换成了 getSelectedSlot()
        player.connection.send(new ClientboundSetHeldSlotPacket(player.getInventory().getSelectedSlot()));

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

    /**
     * 把「内存里的存档数据」拉回存档点那一刻。
     *
     * <p>做法是**排除法**：只把不在 {@link #PROTECTED_SAVED_DATA} 里的缓存条目作废掉，之后任何
     * {@code get} / {@code computeIfAbsent} 都会经读拦截从覆盖层重读（覆盖层武装中；
     * 对 {@code /saves} 活文件 0 读取）。
     * 这样所有「每次用时取一次」的存档数据——**包括模组写在
     * {@code <维度>/data/<namespace>/<path>.dat} 里的那些**——都会自动拿到快照内容，不需要认识任何具体模组；
     * 而被长生命周期对象用字段强引用的条目原样留着，继续正常落盘（内容不参与回滚，见类注释的「已知边界」）。
     *
     * <p>作废之后还要显式重建**能从覆盖层读快照**的那几类（它们也正是不能进 {@link #PROTECTED_SAVED_DATA} 的）：
     *
     * <ul>
     *   <li><b>袭击</b>：{@code ServerLevel.raids} 是 final 字段，直接换实例；用它的 {@code SavedDataType}
     *       走一次 {@code computeIfAbsent}，就会经覆盖层重新读到快照内容。</li>
     *   <li><b>等级数据附件</b>（NeoForge 的 {@code neoforge:data_attachments}）：那个 SavedData 只是个
     *       壳子，真数据挂在 {@code Level} 自己的附件表上，由它的反序列化器灌回去；而反序列化是「按 key 覆盖」
     *       而不是替换，所以要先清空那张表，快照里没有的附件才不会留下来。</li>
     *   <li><b>记分板</b>：26.1 里它挂在**服务器级**存储上（{@code server.getDataStorage()}），
     *       {@code MinecraftServer.saveAllChunks} 也是用 {@code computeIfAbsent(ScoreboardSaveData.TYPE)}
     *       拿它——作废缓存会连它一起摘掉，所以必须重建，否则 {@code SavedDataStorage.scheduleSave()} 再也
     *       遍历不到它，scoreboard.dat 从此不再落盘。{@code storage.get} 既经覆盖层读出快照内容、
     *       又把条目登记回去。</li>
     *   <li><b>天气</b>：26.1 把天气从 level.dat 搬成了服务器级 {@code WeatherData}，见
     *       {@link #restoreWeather}。必须排在缓存作废之后，否则刚重建的条目会被清掉。</li>
     * </ul>
     *
     * <p>骨架（作废 → 重建 → 记日志 → 通知模组丢缓存）在 {@link SavedDataRollback#restore}，
     * 版本相关的四处收在 {@link SavedDataHook}。
     *
     * @param dataFiles 本次覆盖到的存档数据文件相对路径（灌装时收集，给日志用）
     */
    private static void restoreSavedData(MinecraftServer server, List<String> dataFiles) {
        SavedDataRollback.restore(new SavedDataHook(server), dataFiles);
    }

    /**
     * 26.1 的四点版本相关实现：缓存是 {@code SavedDataStorage.cache}（作废时按 {@link SavedDataType} 排除
     * {@link #PROTECTED_SAVED_DATA}）、重建走 26.1 的 API、id 是 {@code <namespace>:<path>}。
     */
    private static final class SavedDataHook implements SavedDataRollback.Hook {
        private final MinecraftServer server;

        SavedDataHook(MinecraftServer server) {
            this.server = server;
        }

        @Override
        public int purge() {
            // 作废每个维度与服务器级那一份里「能重新读盘」的存档数据缓存
            int removed = 0;
            for (ServerLevel level : server.getAllLevels()) {
                removed += clearReloadable(level.getDataStorage());
            }
            return removed + clearReloadable(server.getDataStorage());
        }

        @Override
        public void rebuild() {
            // 重建能被重新读盘的那几个
            for (ServerLevel level : server.getAllLevels()) {
                SavedDataStorage storage = level.getDataStorage();
                levelAccess(level).rewind$setRaids(storage.computeIfAbsent(Raids.TYPE));
                clearLevelAttachments(level);
                LevelAttachmentsSavedData.init(level);
            }
            restoreScoreboard(server);
            restoreWeather(server);
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
     * 摘掉 {@code storage} 缓存里不属于 {@link #PROTECTED_SAVED_DATA} 的条目。
     *
     * <p>只有「能重新读盘」的条目才能摘：{@code scheduleSave()} 只遍历缓存，受保护的那些被长生命周期对象
     * 用字段强引用、摘掉就再也没人登记回去，那个 {@code .dat} 从此不再落盘。
     *
     * @return 摘掉的条数，只用于日志
     */
    private static int clearReloadable(SavedDataStorage storage) {
        Map<SavedDataType<?>, Optional<SavedData>> cache = cacheOf(storage);
        int before = cache.size();
        cache.keySet().removeIf(type -> !isProtectedSavedData(type));
        return before - cache.size();
    }

    /** 清掉 {@code Level} 内存里那张附件表（反序列化器只按 key 覆盖，不清的话快照里没有的附件会留下来）。 */
    private static void clearLevelAttachments(ServerLevel level) {
        Map<?, ?> attachments = ((AttachmentHolderAccess) (Object) level).rewind$attachments();
        if (attachments != null) {
            attachments.clear();
        }
    }

    /**
     * 记分板：从（已还原的）服务器级磁盘读回快照内容，灌进活着的 {@code ServerScoreboard}。
     *
     * <p>先清掉现有 objective / team：{@code Scoreboard.addObjective} 对重名会抛异常，直接 load 到活着的
     * 记分板上必然失败（原版日志里那句 {@code Error loading saved data: scoreboard}）。{@code storage.get}
     * 既把快照数据读出来、又把缓存条目重新登记回去（{@code saveAllChunks} 随后会用 {@code computeIfAbsent}
     * 拿同一个条目），所以它不会像被摘掉的条目那样再也不落盘。
     */
    private static void restoreScoreboard(MinecraftServer server) {
        ScoreboardSaveData saved = server.getDataStorage().get(ScoreboardSaveData.TYPE);
        if (saved == null) {
            return;
        }
        ServerScoreboard scoreboard = server.getScoreboard();
        for (Objective objective : new ArrayList<>(scoreboard.getObjectives())) {
            scoreboard.removeObjective(objective);
        }
        for (PlayerTeam team : new ArrayList<>(scoreboard.getPlayerTeams())) {
            scoreboard.removePlayerTeam(team);
        }
        scoreboard.load(saved.getData());
    }

    /**
     * 26.1 原版（含 NeoForge 自己）会写进存档数据目录的那些 id。
     *
     * <p>26.1 的落盘路径是 {@code <dataFolder>/<namespace>/<path>.dat}（{@code SavedDataStorage.getDataFile}
     * + {@code Identifier.resolveAgainst}），所以 id 是 {@code <namespace>:<path>}。不在这份名单里的就算
     * 「模组数据」——原地回滚只能保证它们的**磁盘文件**被还原。
     */
    private static final Set<String> VANILLA_SAVED_DATA = Set.of(
            "minecraft:scoreboard", "minecraft:weather", "minecraft:random_sequences",
            "minecraft:game_rules", "minecraft:world_gen_settings", "minecraft:custom_boss_events",
            "minecraft:scheduled_events", "minecraft:world_clocks", "minecraft:stopwatches",
            "minecraft:maps/last_id", "minecraft:command_storage",
            "minecraft:raids", "minecraft:chunk_tickets", "minecraft:world_border",
            "minecraft:ender_dragon_fight", "minecraft:wandering_trader",
            "neoforge:data_attachments");

    /** 原版白名单里按前缀认的那部分：地图的 id 是 {@code minecraft:maps/<n>}。 */
    private static final Set<String> VANILLA_SAVED_DATA_PREFIXES = Set.of("minecraft:maps/");

    /**
     * 从归档相对路径反推存档数据 id：{@code data/minecraft/scoreboard.dat} → {@code minecraft:scoreboard}。
     * path 里还可以再带斜杠（地图是 {@code minecraft:maps/<n>}，命令存储是 {@code <namespace>:command_storage}）。
     */
    private static String savedDataId(String relative) {
        String tail;
        if (relative.startsWith("data/")) {
            tail = relative.substring("data/".length());
        } else {
            int marker = relative.indexOf("/data/");
            tail = marker >= 0 ? relative.substring(marker + "/data/".length()) : relative;
        }
        if (tail.endsWith(".dat")) {
            tail = tail.substring(0, tail.length() - ".dat".length());
        }
        int slash = tail.indexOf('/');
        return slash < 0 ? tail : tail.substring(0, slash) + ':' + tail.substring(slash + 1);
    }

    private static boolean isVanillaSavedData(String id) {
        return SavedDataRollback.matchesPrefixes(id, VANILLA_SAVED_DATA, VANILLA_SAVED_DATA_PREFIXES);
    }

    /**
     * **不清缓存**的存档数据：被长生命周期对象用字段强引用、摘掉缓存条目就再也没人登记回去的那些。
     *
     * <p>{@code SavedDataStorage.scheduleSave()} 只遍历 {@code cache}，所以条目一旦被摘掉、又没人重新登记，
     * 对应的 {@code .dat} 就再也不落盘。26.1 里这些类型全是字段持有（1.21.1 那会儿它们大多是
     * 「每次用时取一次」，作废缓存自然会重读），所以这里用排除法：它们**故意留在缓存里**，
     * 内容不参与回滚（见类注释的「已知边界」），但会继续正常落盘。
     *
     * <p>每一条的「被谁强引用」：
     * <ul>
     *   <li>{@code minecraft:chunk_tickets} → {@code ServerChunkCache.ticketStorage}（final 字段）</li>
     *   <li>{@code minecraft:ender_dragon_fight} → {@code ServerLevel.dragonFight}</li>
     *   <li>{@code minecraft:random_sequences} → {@code MinecraftServer.randomSequences}（final 字段）</li>
     *   <li>{@code minecraft:world_gen_settings} → {@code MinecraftServer.worldGenSettings}（final 字段）</li>
     *   <li>{@code minecraft:custom_boss_events} → {@code MinecraftServer.customBossEvents}（final 字段）</li>
     *   <li>{@code minecraft:world_clocks} → {@code MinecraftServer.clockManager}（final 字段）</li>
     *   <li>{@code minecraft:scheduled_events} → {@code MinecraftServer.scheduledEvents}（final 字段）</li>
     *   <li>{@code minecraft:stopwatches} → {@code MinecraftServer.stopwatches}</li>
     *   <li>{@code minecraft:game_rules} → {@code MinecraftServer.gameRules} 手里的 {@code GameRules.rules}</li>
     *   <li>{@code minecraft:wandering_trader} → {@code WanderingTraderSpawner.traderData}</li>
     * </ul>
     *
     * <p>命令存储（{@code <任意命名空间>:command_storage}）也是强引用的（{@code CommandStorage.namespaces}），
     * 而且 26.1 的 {@code CommandStorage.get} 命中内存里那份 Container 就直接返回、不走存储层，
     * 所以它由 {@link #isProtectedSavedData} 按 path 一并保护：清掉缓存条目既回滚不了内容
     * （读取命中内存那份），还会让那份 Container 掉出存储缓存、{@code scheduleSave} 再也写不到它。
     */
    private static final Set<SavedDataType<?>> PROTECTED_SAVED_DATA = Set.of(
            TicketStorage.TYPE,
            EnderDragonFight.TYPE,
            RandomSequences.TYPE,
            WorldGenSettings.TYPE,
            CustomBossEvents.TYPE,
            ServerClockManager.TYPE,
            TimerQueue.TYPE,
            Stopwatches.TYPE,
            GameRuleMap.TYPE,
            WanderingTraderData.TYPE);

    /** 这个类型的缓存条目要不要留着：被长生命周期对象强引用的都留。 */
    private static boolean isProtectedSavedData(SavedDataType<?> type) {
        // 命令存储的 id 是 <任意命名空间>:command_storage，命名空间动态，按 path 认
        return PROTECTED_SAVED_DATA.contains(type) || type.id().getPath().equals("command_storage");
    }

    private static Map<SavedDataType<?>, Optional<SavedData>> cacheOf(SavedDataStorage storage) {
        return storageAccess(storage).rewind$cache();
    }

    // ------------------------------------------------------------------ 访问器包装

    private static long millisSince(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }

    private static ChunkMapAccess mapAccess(ChunkMap chunkMap) {
        return (ChunkMapAccess) (Object) chunkMap;
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

    private static GenerationChunkHolderAccess generationAccess(ChunkHolder holder) {
        return (GenerationChunkHolderAccess) (Object) holder;
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

    private static SavedDataStorageAccess storageAccess(SavedDataStorage storage) {
        return (SavedDataStorageAccess) (Object) storage;
    }
}
