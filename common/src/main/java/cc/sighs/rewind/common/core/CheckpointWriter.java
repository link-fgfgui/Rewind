package cc.sighs.rewind.common.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import cc.sighs.rewind.common.CoverRequest;
import cc.sighs.rewind.common.RewindLog;
import cc.sighs.rewind.common.RewindState;
import cc.sighs.rewind.common.compat.SophisticatedCoreCompat;
import cc.sighs.rewind.common.spi.EncodedSector;
import cc.sighs.rewind.common.spi.FlushOutcome;
import cc.sighs.rewind.common.spi.RewindPlatform;
import cc.sighs.rewind.common.spi.RewindPlatforms;
import cc.sighs.rewind.common.spi.StorageKind;
import cc.sighs.rewind.common.spi.StorageOverlay;
import cc.sighs.rewind.common.store.SnapshotBlockIo;
import cc.sighs.rewind.snapshot.AnvilDeltaAssembler;
import cc.sighs.rewind.snapshot.AnvilFormat;
import cc.sighs.rewind.snapshot.AtomicFiles;
import cc.sighs.rewind.snapshot.MccName;
import cc.sighs.rewind.snapshot.SnapshotBlockStore;
import cc.sighs.rewind.snapshot.SnapshotBlocks;
import cc.sighs.rewind.snapshot.SnapshotChunkDecoder;
import cc.sighs.rewind.snapshot.SnapshotIndex;
import cc.sighs.rewind.snapshot.SnapshotInventory;
import cc.sighs.rewind.snapshot.SnapshotLayout;
import cc.sighs.rewind.snapshot.SnapshotManifest;
import cc.sighs.rewind.snapshot.SnapshotMeta;
import cc.sighs.rewind.snapshot.SnapshotMirror;
import cc.sighs.rewind.snapshot.ZPackCheckpoint;
import cc.sighs.rewind.snapshot.ZPackOffline;

/**
 * 存档点的磁盘机制：把世界强制落盘后镜像进槽位，以及把槽位镜像回活动存档。
 *
 * <p>一个槽位在磁盘上是三样东西：{@code <槽位>/} 目录（世界文件镜像）、{@code <槽位>.manifest}
 * （增量清单）、{@code <槽位>.inventory}（背包快照，只在界面上展示）。后两个与槽位目录同级、
 * 不参与镜像。槽位的删除与改名在 {@link cc.sighs.rewind.common.store.SnapshotStore}。
 *
 * <p>建点还会在索引里留下一条时间线的边：新存档点的 {@code parentSlot} 指向建点这一刻世界所在的
 * 那个存档点（索引里的 {@code head}），建完点 {@code head} 就移到新节点上。界面上的
 * 「节点树布局」画的就是这些边。
 *
 * <p>「强制落盘 + 采集玩家展示信息」是唯一碰 Minecraft 的部分，被隔离在
 * {@link RewindPlatform#flushForCheckpoint} 后面；所以本类整体是平台无关的，放在 common。
 * 带过渡、界面、状态机的那一层在 {@code cc.sighs.rewind.client.CheckpointController}，
 * 对外门面是 {@link cc.sighs.rewind.api.RewindApi}。
 */
public final class CheckpointWriter {
    /** 一次磁盘操作的结果：{@code meta} 只有建立存档点时非 null。 */
    public static final class Result {
        public final SnapshotMeta meta;
        public final SnapshotMirror.Result mirror;
        public final long millis;

        Result(SnapshotMeta meta, SnapshotMirror.Result mirror, long millis) {
            this.meta = meta;
            this.mirror = mirror;
            this.millis = millis;
        }

        public String summary() {
            return mirror.summary();
        }
    }

    private CheckpointWriter() {
    }

    /**
     * 建立 / 覆盖存档点。
     *
     * <p><b>必须在服务端线程上调用。</b>线程被占住本身就是「拷贝期间没人写盘」的保证——
     * 没有界面就没法靠「暂停世界」做到这件事，让服务端线程忙在落盘和拷贝上等价于把它冻结。
     *
     * <p>先写 {@code incomplete} 再写 {@code complete}：中途崩了留下的槽位不会被当成可还原的存档点。
     *
     * @param server 平台自己的服务器对象，原样转交给 {@link RewindPlatform}
     */
    public static Result create(Object server, String slot, String source) throws IOException {
        long startedNanos = System.nanoTime();
        RewindPlatform platform = RewindPlatforms.require();
        FlushOutcome flushed = platform.flushForCheckpoint(server, slot, source);
        Path world = flushed.worldRoot;
        SnapshotMeta base = flushed.meta.copy();
        // 封面：让客户端在下一帧抓一张没有界面的画面。那会儿我们正占着服务端线程、世界不 tick，
        // 所以抓到的就是存档点这一刻的样子。文件名用 savedAtMillis，索引里也是它，页面才拼得出来。
        CoverRequest.request(world.getFileName().toString(), slot, base.savedAtMillis);

        Path indexFile = SnapshotLayout.indexFile(world);
        Path manifestFile = SnapshotLayout.manifestFile(world, slot);
        Files.createDirectories(SnapshotLayout.snapshotRoot(world));

        // 覆盖一个改过名的槽位时把玩家的名字留着：名字记在索引里，不该被下一次覆盖抹掉
        SnapshotIndex index = SnapshotIndex.load(indexFile);
        SnapshotMeta previous = index.get(slot);
        if (previous != null) {
            base.displayName = previous.displayName;
        }
        // 时间线：新节点挂在建点这一刻世界所在的节点下面
        base.parentSlot = parentFor(index, slot, previous);
        // 覆盖墓碑槽位即复活：新存档点永远是存活节点（墓碑不继承）
        base.tombstone = false;

        SnapshotMeta incomplete = base.copy();
        incomplete.status = SnapshotLayout.STATUS_INCOMPLETE;
        index.put(incomplete, slot);
        index.save(indexFile);
        // 背包快照与槽位负载分开存（SNBT 体积大，且界面侧只在打开详情时才读）。
        // 先于镜像写：中途失败的话槽位还是 incomplete，不会被当成可还原的存档点。
        SnapshotInventory.of(flushed.inventory).save(SnapshotLayout.inventoryFile(world, slot));

        SnapshotManifest previousManifest = SnapshotManifest.load(manifestFile);
        // region / entities / poi 的 .mca 按 4 KiB 块存：换一个槽位建点只为真正变了的扇区付字节。
        // 第二个槽位起走增量：.mca 不再读 /saves（0 读），只把监听到的变更集在内存里组装进块存储；
        // 松散文件（level.dat / playerdata / data/*.dat，几十 KB）仍走磁盘镜像。
        // 首建点（该槽位无历史）必须全量扫描对齐，为冷区块与离线修复留完备基线。
        SnapshotMirror.Result mirror;
        SnapshotBlockIo blocks = SnapshotBlockIo.open(world, slot);
        try {
            mirror = mirrorIncrementalOrFull(world, slot, previousManifest, blocks, platform);
            mirror.manifest.save(manifestFile);
        } finally {
            // close() 才把这次收的块真正写进 pack
            blocks.close();
        }
        // 映射必须等块落盘之后再写：先写映射、崩在落盘之前，会留下指向不存在块的槽位（读不回来）
        blocks.save();

        // TODO2 Phase2 §3.1：附加式 zpack 写包（变更集相对父槽位的字典差分）。
        // 失败只记日志：块存储仍是权威源，回滚自动退回旧路。必须在清账本之前写。
        try {
            ZPackCheckpoint.Written zpack = ZPackCheckpoint.writeSlotZpack(world, slot, base.parentSlot,
                    RewindState.storageJournal().entries(), mirror.files,
                    SnapshotLayout.slotDir(world, slot), platform);
            RewindLog.LOGGER.info("Rewind: zpack {} written ({} entries, {} payload bytes)",
                    slot, zpack.entries, zpack.payloadBytes);
        } catch (Throwable t) {
            RewindLog.LOGGER.warn("Rewind: zpack for {} not written, block store remains authoritative", slot, t);
        }

        SnapshotMeta complete = base.copy();
        complete.status = SnapshotLayout.STATUS_COMPLETE;
        complete.fileCount = mirror.files.size();
        complete.totalBytes = mirror.totalBytes;
        index = SnapshotIndex.load(indexFile);
        index.put(complete, slot);
        // 建完点，世界就站在这个节点上了：之后建的存档点都挂在它下面
        index.setHead(slot);
        index.save(indexFile);

        // 基线前移：写拦截从这里开始重记「相对这个存档点改了什么」，给将来的 collectTargets 用
        RewindState.clearStorageJournal();

        long millis = millisSince(startedNanos);
        RewindLog.LOGGER.info("Rewind: checkpoint {} written ({}) in {} ms", slot, mirror.summary(), millis);
        // 槽位已经落定，后台把下一次回溯要读的东西先摸一遍（只读、不碰世界）
        RollbackWarmup.afterCheckpoint(world, slot);
        return new Result(complete, mirror, millis);
    }

    /**
     * 只把槽位文件镜像回活动存档，不碰内存里的世界。
     *
     * <p>「关世界 → 覆盖 → 重开」那条回退路径用它（那时候世界已经关了，内存里没有东西要同步）；
     * 原地回滚有自己的回拷逻辑，不走这里。
     *
     * <p>写盘二选一（TODO2 Phase2 §4.3）：有完整 zpack 链时走纯 NBT 重建
     * （链 → {@code AnvilPacker} 装配 {@code .mca}，raw 重新 gzip 落 {@code .dat}，
     * 不盲写扇区二进制块）；不适用或重建失败回退块存储旧路。旧路完备，回退永远安全。
     *
     * <p>回拷完顺手通知一遍「模组侧的解码缓存」（见 {@link SophisticatedCoreCompat}）：那条路径会把世界
     * 重新开起来，模组跟着重载走一遍，但它们自己缓存的解码结果不归世界生命周期管，同一进程里还会被复用。
     */
    public static Result restoreFiles(Path worldRoot, String slot) throws IOException {
        long startedNanos = System.nanoTime();
        Path snapshotDir = SnapshotLayout.slotDir(worldRoot, slot);
        if (!Files.isDirectory(snapshotDir)) {
            throw new IOException("snapshot directory missing: " + snapshotDir);
        }
        // 用建点时记录的清单判断活动存档里哪些文件还是原样：没动过的不必回拷
        SnapshotManifest reference = SnapshotManifest.load(SnapshotLayout.manifestFile(worldRoot, slot));
        SnapshotMirror.Result mirror = null;
        try {
            ZPackOffline.Outcome outcome = ZPackOffline.rebuild(worldRoot, slot, ZPackOffline.installedLayout());
            if (outcome.applied()) {
                mirror = outcome.result;
            } else {
                RewindLog.LOGGER.info("Rewind: pure-NBT rebuild of {} not applicable ({}), using block store",
                        slot, outcome.reason);
            }
        } catch (Throwable t) {
            RewindLog.LOGGER.warn("Rewind: pure-NBT rebuild of {} failed, falling back to block store", slot, t);
            mirror = null;
        }
        if (mirror != null) {
            RewindLog.LOGGER.info("Rewind: restored slot {} via pure-NBT rebuild", slot);
        } else {
            mirror = SnapshotBlockIo.restoreInto(worldRoot, slot, reference);
        }
        long millis = millisSince(startedNanos);
        RewindLog.LOGGER.info("Rewind: restored slot {} into {} ({}) in {} ms", slot, worldRoot, mirror.summary(), millis);
        SophisticatedCoreCompat.clearCaches();
        return new Result(null, mirror, millis);
    }

    private static long millisSince(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }

    /**
     * 建点的块路径二选一：首建点全量扫描，之后增量（.mca 0 读）。
     *
     * <p>增量条件：该槽位已有块映射，且平台已接入扇区编码与目录映射。任一不满足就回退全量——
     * 正确性优先，代价只是多读一次盘。
     */
    private static SnapshotMirror.Result mirrorIncrementalOrFull(Path world, String slot,
            SnapshotManifest previousManifest, SnapshotBlockIo blocks, RewindPlatform platform)
            throws IOException {
        Path slotDir = SnapshotLayout.slotDir(world, slot);
        SnapshotMirror.Blocks ctx = blocks.context();
        // 首建点必须全量：该槽位还没有块映射（含老格式整文件槽位的迁移——全量重编码并删整文件）。
        // 只看块映射不看清单：光有清单没有映射说明是从老版本升上来的，增量组装无底可组。
        boolean hasHistory = ctx.existing != null && ctx.existing.size() > 0;
        if (!hasHistory) {
            return SnapshotMirror.mirror(world, slotDir,
                    SnapshotMirror.Direction.TO_SNAPSHOT, previousManifest, null, ctx);
        }
        try {
            SnapshotMirror.Result incremental = mirrorIncremental(world, slot, previousManifest, ctx, platform);
            if (incremental != null) {
                return incremental;
            }
        } catch (Throwable t) {
            RewindLog.LOGGER.warn("Rewind: incremental checkpoint failed, falling back to full scan", t);
        }
        return SnapshotMirror.mirror(world, slotDir,
                SnapshotMirror.Direction.TO_SNAPSHOT, previousManifest, null, ctx);
    }

    /**
     * 增量建点：松散文件走磁盘镜像（小文件，stat + 按需拷贝），
     * {@code .mca/.mcc} 只用监听变更集在内存里组装（对 {@code /saves} 区块文件 0 内容读取）。
     *
     * <p>任一变更条目编不出（未知维度布局）时返回 null，调用方回退全量。
     */
    private static SnapshotMirror.Result mirrorIncremental(Path world, String slot,
            SnapshotManifest previousManifest, SnapshotMirror.Blocks ctx, RewindPlatform platform)
            throws IOException {
        Path slotDir = SnapshotLayout.slotDir(world, slot);
        SnapshotBlockStore store = ctx.store;
        SnapshotBlocks previous = ctx.existing == null ? SnapshotBlocks.empty() : ctx.existing;
        SnapshotBlocks written = ctx.written == null ? SnapshotBlocks.empty() : ctx.written;

        List<StorageOverlay.Entry> journal = RewindState.storageJournal().entries();
        // 按 region 文件分组：mcaRelative -> 下标 -> 账本条目
        Map<String, Map<Integer, StorageOverlay.Entry>> byFile = new LinkedHashMap<>();
        for (StorageOverlay.Entry e : journal) {
            String dir = platform.chunkDirRelative(e.kind, e.dimensionId);
            if (dir == null || dir.isEmpty()) {
                return null;
            }
            long pos = e.chunkPos;
            int[] xz = platform.unpackChunkPos(pos);
            int x = xz[0];
            int z = xz[1];
            String file = SnapshotChunkDecoder.regionFileName(x, z);
            String mcaRelative = SnapshotLayout.normalize(dir + "/" + file);
            int index = SnapshotChunkDecoder.chunkIndex(x, z);
            Map<Integer, StorageOverlay.Entry> group = byFile.get(mcaRelative);
            if (group == null) {
                group = new LinkedHashMap<>();
                byFile.put(mcaRelative, group);
            }
            group.put(index, e);
        }

        SnapshotMirror.Result result = new SnapshotMirror.Result();
        long nowMillis = System.currentTimeMillis();
        int nowSeconds = (int) (nowMillis / 1000L);
        Set<String> handledMca = new HashSet<>();
        Set<String> handledMcc = new HashSet<>();

        // 先处理 .mca/.mcc（内存组装），再处理松散文件（磁盘镜像），最后清理槽位多余松散文件。
        for (Map.Entry<String, Map<Integer, StorageOverlay.Entry>> fileEntry : byFile.entrySet()) {
            String mcaRelative = fileEntry.getKey();
            Map<Integer, StorageOverlay.Entry> group = fileEntry.getValue();
            Map<Integer, AnvilDeltaAssembler.NewChunk> changes = new LinkedHashMap<>();
            Map<String, byte[]> newMcc = new LinkedHashMap<>();
            for (Map.Entry<Integer, StorageOverlay.Entry> chunkEntry : group.entrySet()) {
                int index = chunkEntry.getKey();
                StorageOverlay.Entry je = chunkEntry.getValue();
                if (je.tag == null) {
                    changes.put(index, AnvilDeltaAssembler.NewChunk.delete());
                    continue;
                }
                EncodedSector sector = platform.encodeChunkSector(je.tag);
                if (sector == null || sector.compressed == null) {
                    return null;
                }
                long pos = je.chunkPos;
                int[] xz = platform.unpackChunkPos(pos);
                int x = xz[0];
                int z = xz[1];
                int total = 5 + sector.compressed.length;
                int sectors = (total + SnapshotBlockStore.BLOCK_SIZE - 1) / SnapshotBlockStore.BLOCK_SIZE;
                if (sectors >= AnvilFormat.EXTERNAL_CHUNK_THRESHOLD) {
                    changes.put(index, AnvilDeltaAssembler.NewChunk.external(sector.versionId));
                    newMcc.put(SnapshotChunkDecoder.mccRelative(mcaRelative, x, z), sector.compressed);
                } else {
                    changes.put(index, AnvilDeltaAssembler.NewChunk.update(sector.versionId, sector.compressed));
                }
            }
            byte[] prevBytes = readPreviousMca(store, previous, slotDir, mcaRelative);
            byte[] assembled = AnvilDeltaAssembler.assemble(prevBytes, changes, nowSeconds);
            SnapshotBlockStore.Stored stored = store.encodeBytes(assembled);
            written.put(mcaRelative, assembled.length, stored.hashes);
            result.manifest.put(mcaRelative, assembled.length, nowMillis);
            result.files.add(mcaRelative);
            result.totalBytes += assembled.length;
            result.copied++;
            result.copiedBytes += stored.newBytes;
            result.encoded++;
            handledMca.add(mcaRelative);
            for (Map.Entry<String, byte[]> mcc : newMcc.entrySet()) {
                SnapshotBlockStore.Stored mccStored = store.encodeBytes(mcc.getValue());
                written.put(mcc.getKey(), mcc.getValue().length, mccStored.hashes);
                result.manifest.put(mcc.getKey(), mcc.getValue().length, nowMillis);
                result.files.add(mcc.getKey());
                result.totalBytes += mcc.getValue().length;
                result.copied++;
                result.copiedBytes += mccStored.newBytes;
                result.encoded++;
                handledMcc.add(mcc.getKey());
            }
        }

        // 未动过的 .mca/.mcc：沿用上次映射，一个字节不读
        for (String path : previous.paths()) {
            if (!SnapshotBlockStore.isEncoded(path)) {
                continue;
            }
            if (path.endsWith(".mcc")) {
                if (handledMcc.contains(path)) {
                    continue;
                }
                // 外溢文件若所属区块本次被改写（转 normal/删除），旧 .mcc 不再沿用
                if (mccSuperseded(path, byFile)) {
                    continue;
                }
            } else if (handledMca.contains(path)) {
                continue;
            }
            SnapshotBlocks.Entry prev = previous.get(path);
            SnapshotManifest.Entry rec = previousManifest.get(path);
            if (prev == null) {
                continue;
            }
            written.put(path, prev.size, prev.hashes);
            result.manifest.put(path, prev.size, rec == null ? nowMillis : rec.modifiedMillis);
            result.files.add(path);
            result.totalBytes += prev.size;
            result.reused++;
        }

        // 松散文件：小体量，走磁盘镜像（stat + 按需拷贝），与老路一致
        List<String> liveAll = SnapshotMirror.listFiles(world);
        Collections.sort(liveAll);
        List<String> looseLive = new ArrayList<>();
        for (String relative : liveAll) {
            if (!SnapshotBlockStore.isEncoded(relative)) {
                looseLive.add(relative);
            }
        }
        for (String relative : looseLive) {
            Path source = world.resolve(relative);
            long size = Files.size(source);
            long modified = Files.getLastModifiedTime(source).toMillis();
            result.manifest.put(relative, size, modified);
            result.files.add(relative);
            result.totalBytes += size;
            Path target = slotDir.resolve(relative);
            if (previousManifest.matches(relative, size, modified)
                    && Files.isRegularFile(target) && Files.size(target) == size) {
                result.skipped++;
                continue;
            }
            Path parent = target.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            copyAtomically(source, target);
            result.copied++;
            result.copiedBytes += size;
        }
        // 槽位里多出来的松散文件删掉；块映射的 .mca/.mcc 在槽位里没有实体文件，不参与
        Set<String> wanted = new HashSet<>(looseLive);
        wanted.addAll(handledMca);
        wanted.addAll(handledMcc);
        for (String relative : SnapshotMirror.listFiles(slotDir)) {
            if (SnapshotBlockStore.isEncoded(relative)) {
                continue;
            }
            if (wanted.contains(relative)) {
                continue;
            }
            if (Files.deleteIfExists(slotDir.resolve(relative))) {
                result.deleted++;
            }
        }
        SnapshotLayout.pruneEmptyDirectories(slotDir);
        return result;
    }

    /** 上一个快照的该文件字节：块映射有就走块存储拼，无（老格式槽位）就读槽位整文件，新文件返回 null。 */
    private static byte[] readPreviousMca(SnapshotBlockStore store, SnapshotBlocks previous,
            Path slotDir, String mcaRelative) throws IOException {
        SnapshotBlocks.Entry prev = previous.get(mcaRelative);
        if (prev != null) {
            return store.readAll(prev.hashes, prev.size);
        }
        Path file = slotDir.resolve(mcaRelative);
        if (Files.isRegularFile(file)) {
            return Files.readAllBytes(file);
        }
        return null;
    }

    /** 该 .mcc 是否因所属区块本次被改写而失效（转 normal/删除，外溢 stub 已不在新文件里）。 */
    private static boolean mccSuperseded(String mccRelative, Map<String, Map<Integer, StorageOverlay.Entry>> byFile) {
        String normalized = SnapshotLayout.normalize(mccRelative);
        int slash = normalized.lastIndexOf('/');
        String dir = slash < 0 ? "" : normalized.substring(0, slash);
        String name = slash < 0 ? normalized : normalized.substring(slash + 1);
        MccName parsed = MccName.parse(name);
        if (parsed == null) {
            return false;
        }
        String mcaRelative = SnapshotLayout.normalize(dir + "/"
                + SnapshotChunkDecoder.regionFileName(parsed.x, parsed.z));
        Map<Integer, StorageOverlay.Entry> group = byFile.get(mcaRelative);
        return group != null && group.containsKey(SnapshotChunkDecoder.chunkIndex(parsed.x, parsed.z));
    }

    private static void copyAtomically(Path source, Path target) throws IOException {
        AtomicFiles.copy(source, target);
    }

    /**
     * 新节点挂在谁下面：建点这一刻时间线的「头」（世界当前所在的节点）就是它的父节点。
     *
     * <p>覆盖头所在的那个槽位时，新节点顶替旧节点的位置——沿用旧节点的父节点，
     * 免得时间线上凭空多出一个自己指向自己的节点、或者把这条线的根弄丢。
     * 旧节点的父节点如果反过来挂在这个槽位下面，那宁可退回成根：断一条边好过成环。
     */
    private static String parentFor(SnapshotIndex index, String slot, SnapshotMeta previous) {
        String head = index.getHead();
        if (head.isEmpty() || index.get(head) == null) {
            // 头还没立起来（新世界），或者头所在的槽位已经被删了：这条线从这里重新起头
            return "";
        }
        if (!head.equals(slot)) {
            return head;
        }
        String previousParent = previous == null ? "" : previous.parentSlot;
        if (previousParent.isEmpty() || isDescendant(index, previousParent, slot)) {
            return "";
        }
        return previousParent;
    }

    /** {@code slot} 是不是挂在 {@code ancestor} 下面（顺着 parentSlot 往上走）。 */
    private static boolean isDescendant(SnapshotIndex index, String slot, String ancestor) {
        String current = slot;
        for (int step = 0; step < SnapshotIndex.TIMELINE_WALK_LIMIT && current != null && !current.isEmpty(); step++) {
            if (current.equals(ancestor)) {
                return true;
            }
            SnapshotMeta meta = index.get(current);
            current = meta == null ? "" : meta.parentSlot;
        }
        return false;
    }
}
