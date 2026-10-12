package cc.sighs.rewind.snapshot;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.GZIPOutputStream;
import cc.sighs.rewind.common.spi.RewindPlatform;
import cc.sighs.rewind.common.spi.RewindPlatforms;

/**
 * 离线纯 NBT 重建（TODO2 Phase2 §4.3：WorldRepair 的 Anvil 重写器）。
 *
 * <p>Phase 2 弃用 4 KiB 物理扇区快照后，离线修复不再盲写扇区二进制块：从目标槽位的
 * zpack 链还原出全量未压缩 NBT，在内存中装配 Anvil（5 字节头、扇区对齐、超 1 MiB
 * 自动 `.mcc` 外溢），覆盖回 {@code /saves}。{@code .dat}（level/玩家/存档数据）
 * 由 raw 重新 gzip 落盘；其余松散文件（datapacks、配置、进度 JSON 等）仍从槽位目录拷贝。
 *
 * <p>调用契约（{@link Outcome}）：能重建则返回结果；不适用（无平台、无 zpack、槽位不在
 * 索引、未知维度布局、链不可读）返回带原因的 not-applicable，调用方走块存储旧路。
 * 清单与链对不上（缺块/多块/多余残留）、.mcc 无法解释、.dat 缺文件等中途失败抛
 * {@link IOException}——同样由调用方统一 catch 回退旧路，但失败原因会进日志，不再静默。
 * 回退永远安全：旧路是完备快照。写盘全程原子替换（tmp + move），中途失败不留半个文件。
 *
 * <p>流版本说明：zpack 只存未压缩 NBT，不记原 Anvil 流版本；重建统一用 DEFLATE。
 * GZIP/DEFLATE/NONE 均为合法 Anvil（解码按头分派），内容逐字节一致。与原版
 * {@code regionFileComression} 配置的差异只影响压缩比，不影响正确性。
 *
 * <p>Java 8 兼容、无 Minecraft 依赖。维度布局（目录映射/坐标解包）经 {@link Layout}
 * 传入——在线/离线调用方用 {@link #platformLayout} 从已安装平台适配（含 26.1 的
 * {@code pack()} 布局覆盖），单测传桩。
 */
public final class ZPackOffline {
    private ZPackOffline() {
    }

    /** 维度布局：纯映射，不碰服务器对象，离线可用。 */
    public interface Layout {
        /**
         * zpack 目标类型 + 维度 → region 目录相对路径（世界根基准，{@code /} 分隔）。
         * 未知布局返回 null（整单回退旧路，绝不硬猜）。
         */
        String dirRelative(int zpackType, String dimensionId);

        /** 账本打包坐标 → {@code {x, z}}；实现保证返回 2 元数组。 */
        int[] unpack(long packed);
    }

    /** 从已安装平台适配布局；平台未安装返回 null。 */
    public static Layout platformLayout(final RewindPlatform platform) {
        if (platform == null) {
            return null;
        }
        return new Layout() {
            @Override
            public String dirRelative(int zpackType, String dimensionId) {
                return platform.chunkDirRelative(ZPackCheckpoint.typeToKind(zpackType), dimensionId);
            }

            @Override
            public int[] unpack(long packed) {
                return platform.unpackChunkPos(packed);
            }
        };
    }

    /** 快捷：用已安装平台（未安装即 null → 重建返回 not-applicable → 旧路）。 */
    public static Layout installedLayout() {
        return platformLayout(RewindPlatforms.get());
    }

    /** 重建结果：适用（{@link #result} 非 null）或明确不适用（{@link #reason} 非 null）。 */
    public static final class Outcome {
        public final SnapshotMirror.Result result;
        /** 不适用原因（调用方写日志用）；适用时为 null。 */
        public final String reason;

        private Outcome(SnapshotMirror.Result result, String reason) {
            this.result = result;
            this.reason = reason;
        }

        public boolean applied() {
            return result != null;
        }

        @Override
        public String toString() {
            return applied() ? "applied" : "not applicable: " + reason;
        }
    }

    private static Outcome notApplicable(String reason) {
        return new Outcome(null, reason);
    }

    private static Outcome applied(SnapshotMirror.Result result) {
        return new Outcome(result, null);
    }

    /**
     * 纯 NBT 重建槽位进活动存档。
     *
     * @return 应用结果；不适用返回带原因的 not-applicable（调用方走
     *     {@code SnapshotBlockIo.restoreInto}）
     * @throws IOException 链/清单对不上或写盘硬失败（调用方统一 catch 回退旧路，见类注释）
     */
    public static Outcome rebuild(Path worldRoot, String slot, Layout layout) throws IOException {
        if (worldRoot == null || slot == null || layout == null) {
            return notApplicable("arguments incomplete");
        }
        if (!Files.isRegularFile(SnapshotLayout.zpackFile(worldRoot, slot))) {
            return notApplicable("slot " + slot + " has no zpack");
        }
        SnapshotIndex index = SnapshotIndex.load(SnapshotLayout.indexFile(worldRoot));
        SnapshotMeta meta = index.get(slot);
        if (meta == null) {
            return notApplicable("slot " + slot + " missing from index");
        }
        Map<String, byte[]> resolved;
        try {
            resolved = ZPackCheckpoint.tryResolve(worldRoot, slot);
        } catch (IOException e) {
            return notApplicable("chain of " + slot + " unreadable: " + e);
        }
        if (resolved == null) {
            return notApplicable("chain of " + slot + " unreadable");
        }
        SnapshotManifest manifest = SnapshotManifest.load(SnapshotLayout.manifestFile(worldRoot, slot));
        Path slotDir = SnapshotLayout.slotDir(worldRoot, slot);

        // -- 分组：region 文件 → (x,z) → raw；.dat → raw；其余记拷贝 --
        Map<String, Map<Long, byte[]>> regions = new LinkedHashMap<>();
        // (mca#x#z)：空 raw（删除标记）集合，供 .mcc 对账
        Set<String> deletedCoords = new LinkedHashSet<>();
        Map<String, byte[]> dataRaws = new LinkedHashMap<>();
        for (Map.Entry<String, byte[]> e : resolved.entrySet()) {
            ZPack.ParsedKey key;
            try {
                key = ZPack.parseKey(e.getKey());
            } catch (IOException bad) {
                throw new IOException("malformed zpack key: " + e.getKey(), bad);
            }
            if (!key.isChunkLike()) {
                dataRaws.put(key.id, e.getValue());
                continue;
            }
            byte[] raw = e.getValue();
            String dir = layout.dirRelative(key.targetType, key.id);
            if (dir == null) {
                return notApplicable("unknown layout for dimension " + key.id);
            }
            int[] xz = layout.unpack(key.chunkPos);
            String mca = SnapshotLayout.normalize(dir + "/" + SnapshotChunkDecoder.regionFileName(xz[0], xz[1]));
            String coord = mca + "#" + xz[0] + "#" + xz[1];
            if (raw == null || raw.length == 0) {
                deletedCoords.add(coord);
                continue;
            }
            Map<Long, byte[]> region = regions.get(mca);
            if (region == null) {
                region = new LinkedHashMap<>();
                regions.put(mca, region);
            }
            region.put(coordKey(xz[0], xz[1]), raw);
        }

        // -- 完备性门：清单 .mca 与重建 .mca 必须两两对上，否则链有洞（损坏，回退并告警） --
        Set<String> manifestMca = new LinkedHashSet<>();
        Set<String> manifestMcc = new LinkedHashSet<>();
        for (String path : manifest.paths()) {
            if (path.endsWith(".mcc")) {
                manifestMcc.add(path);
            } else if (SnapshotBlockStore.isEncoded(path)) {
                manifestMca.add(path);
            }
        }
        for (String mca : manifestMca) {
            Map<Long, byte[]> region = regions.get(mca);
            if (region == null || region.isEmpty()) {
                throw new IOException("chain hole: manifest region " + mca + " has no data");
            }
        }
        for (String mca : regions.keySet()) {
            if (!manifestMca.contains(mca)) {
                throw new IOException("chain mismatch: region " + mca + " not in manifest");
            }
        }
        // 槽位目录里 manifest 之外的 .mca（老格式残留）：无法验证 → 失败回退
        if (Files.isDirectory(slotDir)) {
            for (String loose : SnapshotMirror.listFiles(slotDir)) {
                if (SnapshotBlockStore.isEncoded(loose) && manifest.get(loose) == null) {
                    throw new IOException("stray legacy region file in slot dir: " + loose);
                }
            }
        }

        // -- 装配 --
        int timestampSeconds = meta.savedAtMillis > 0L
                ? (int) (meta.savedAtMillis / 1000L)
                : (int) (System.currentTimeMillis() / 1000L);
        // 重建产物 .mcc 全路径（manifest 旧 .mcc 无条件剔除，见 wanted）
        Set<String> producedMcc = new LinkedHashSet<>();
        Map<String, byte[]> mcaBytes = new LinkedHashMap<>();
        Map<String, Map<String, byte[]>> mccByRegion = new LinkedHashMap<>();
        for (Map.Entry<String, Map<Long, byte[]>> region : regions.entrySet()) {
            Map<Long, AnvilPacker.Chunk> chunks = new LinkedHashMap<>();
            for (Map.Entry<Long, byte[]> chunk : region.getValue().entrySet()) {
                long packed = chunk.getKey();
                int x = (int) (packed >> 32);
                int z = (int) packed;
                chunks.put(packed, new AnvilPacker.Chunk(x, z, chunk.getValue()));
            }
            AnvilPacker.Packed packedRegion;
            try {
                packedRegion = AnvilPacker.pack(chunks, timestampSeconds);
            } catch (IOException e) {
                throw new IOException("anvil assembly failed for " + region.getKey(), e);
            }
            mcaBytes.put(region.getKey(), packedRegion.mca);
            if (!packedRegion.mcc.isEmpty()) {
                String dir = region.getKey().substring(0, region.getKey().lastIndexOf('/'));
                Map<String, byte[]> named = new LinkedHashMap<>();
                for (Map.Entry<String, byte[]> mcc : packedRegion.mcc.entrySet()) {
                    String relative = dir + "/" + mcc.getKey();
                    named.put(relative, mcc.getValue());
                    producedMcc.add(relative);
                }
                mccByRegion.put(region.getKey(), named);
            }
        }
        // manifest .mcc 对账：重建仍外溢（在 produced 中）→ 写；其余必须有解释
        // （所属 chunk 被正常打包 → 旧 .mcc 过期删；所属 chunk 被删除 → 连带删；
        // 否则链洞 → 失败回退）。
        for (String mcc : manifestMcc) {
            if (producedMcc.contains(mcc)) {
                continue;
            }
            MccRef ref = parseMcc(mcc);
            String coord = ref.mca + "#" + ref.x + "#" + ref.z;
            Map<Long, byte[]> region = regions.get(ref.mca);
            boolean packedNormal = region != null && region.containsKey(coordKey(ref.x, ref.z));
            if (!packedNormal && !deletedCoords.contains(coord)) {
                throw new IOException("chain hole: .mcc " + mcc + " not explainable by chain");
            }
        }

        // -- 落盘 --
        SnapshotMirror.Result result = new SnapshotMirror.Result();
        Set<String> wanted = new LinkedHashSet<>();
        List<String> orderedMca = new ArrayList<>(mcaBytes.keySet());
        Collections.sort(orderedMca);
        for (String mca : orderedMca) {
            writeRebuilt(worldRoot, mca, mcaBytes.get(mca), manifest, result, wanted);
            Map<String, byte[]> mccs = mccByRegion.get(mca);
            if (mccs != null) {
                List<String> names = new ArrayList<>(mccs.keySet());
                Collections.sort(names);
                for (String mcc : names) {
                    writeRebuilt(worldRoot, mcc, mccs.get(mcc), manifest, result, wanted);
                }
            }
        }
        // .dat：链中有 raw → gzip 写；链中缺失但槽位有文件 → 照拷；两边都没有 → 链洞，失败回退
        // （与旧路“缺文件即抛”同义）。
        List<String> dataPaths = new ArrayList<>();
        for (String path : manifest.paths()) {
            if (!path.endsWith(".mcc") && !SnapshotBlockStore.isEncoded(path)
                    && ZPackCheckpoint.isNbtDataFile(path)) {
                dataPaths.add(path);
            }
        }
        Collections.sort(dataPaths);
        for (String path : dataPaths) {
            if (dataRaws.containsKey(path)) {
                byte[] raw = dataRaws.get(path);
                if (raw == null || raw.length == 0) {
                    continue;
                }
                writeRebuilt(worldRoot, path, gzip(raw), manifest, result, wanted);
            } else if (Files.isRegularFile(slotDir.resolve(path))) {
                copyLoose(worldRoot, slotDir, path, manifest, result, wanted);
            } else {
                throw new IOException("chain hole: .dat " + path + " neither in chain nor in slot dir");
            }
        }
        // 其余松散文件：manifest 有的照拷；槽位多出来的（非 .mca）同样照拷（与旧路一致）；
        // 槽位与清单两边都没有的非 .mca 路径 → 链洞，失败回退（旧路会抛，错误语义不变）。
        Set<String> loosePaths = new LinkedHashSet<>();
        for (String path : manifest.paths()) {
            if (!path.endsWith(".mcc") && !SnapshotBlockStore.isEncoded(path)
                    && !ZPackCheckpoint.isNbtDataFile(path)) {
                loosePaths.add(path);
            }
        }
        if (Files.isDirectory(slotDir)) {
            for (String loose : SnapshotMirror.listFiles(slotDir)) {
                if (!loose.endsWith(".mcc") && !SnapshotBlockStore.isEncoded(loose)) {
                    loosePaths.add(loose);
                }
            }
        }
        List<String> orderedLoose = new ArrayList<>(loosePaths);
        Collections.sort(orderedLoose);
        for (String path : orderedLoose) {
            if (!Files.isRegularFile(slotDir.resolve(path))) {
                throw new IOException("chain hole: loose file " + path + " missing from slot dir");
            }
            copyLoose(worldRoot, slotDir, path, manifest, result, wanted);
        }
        // 世界多余文件删除（含过期 .mcc）+ 空目录清理（与旧路同义）
        for (String relative : SnapshotMirror.listFiles(worldRoot)) {
            if (wanted.contains(relative)) {
                continue;
            }
            if (Files.deleteIfExists(worldRoot.resolve(relative))) {
                result.deleted++;
            }
        }
        SnapshotLayout.pruneEmptyDirectories(worldRoot);
        Collections.sort(result.files);
        return applied(result);
    }

    // ---------------------------------------------------------- 内部

    private static long coordKey(int x, int z) {
        return (((long) x) << 32) | (z & 0xFFFFFFFFL);
    }

    /** manifest .mcc 路径 → 所属 region 与坐标；.mcc 名由本仓写盘生成，形非法即存储损坏。 */
    private static final class MccRef {
        final String mca;
        final int x;
        final int z;

        MccRef(String mca, int x, int z) {
            this.mca = mca;
            this.x = x;
            this.z = z;
        }
    }

    private static MccRef parseMcc(String mccRelative) throws IOException {
        String path = SnapshotLayout.normalize(mccRelative);
        int slash = path.lastIndexOf('/');
        if (slash < 0) {
            throw new IOException("malformed .mcc path in manifest: " + mccRelative);
        }
        MccName parsed = MccName.parse(path.substring(slash + 1));
        if (parsed == null) {
            throw new IOException("malformed .mcc path in manifest: " + mccRelative);
        }
        String dir = path.substring(0, slash);
        return new MccRef(SnapshotLayout.normalize(dir + "/"
                + SnapshotChunkDecoder.regionFileName(parsed.x, parsed.z)), parsed.x, parsed.z);
    }

    private static byte[] gzip(byte[] raw) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream(raw.length / 2 + 32);
        GZIPOutputStream out = new GZIPOutputStream(bos);
        out.write(raw);
        out.close();
        return bos.toByteArray();
    }

    /** 重建文件写盘（必写，mtime 拨回清单值；大小与清单原值不同是预期的——重压了）。 */
    private static void writeRebuilt(Path worldRoot, String relative, byte[] bytes,
            SnapshotManifest manifest, SnapshotMirror.Result result, Set<String> wanted) throws IOException {
        Path target = worldRoot.resolve(relative);
        Path parent = target.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        AtomicFiles.write(target, bytes);
        SnapshotManifest.Entry recorded = manifest.get(relative);
        long mtime = recorded == null ? System.currentTimeMillis() : recorded.modifiedMillis;
        restoreModifiedTime(target, mtime);
        result.manifest.put(relative, bytes.length, mtime);
        result.files.add(relative);
        result.totalBytes += bytes.length;
        result.copied++;
        result.copiedBytes += bytes.length;
        result.decoded++;
        wanted.add(relative);
    }

    /** 松散文件照拷（已就位则跳过，与旧路同判据）。 */
    private static void copyLoose(Path worldRoot, Path slotDir, String relative,
            SnapshotManifest manifest, SnapshotMirror.Result result, Set<String> wanted) throws IOException {
        Path source = slotDir.resolve(relative);
        long size = Files.size(source);
        long modified = Files.getLastModifiedTime(source).toMillis();
        result.manifest.put(relative, size, modified);
        result.files.add(relative);
        result.totalBytes += size;
        wanted.add(relative);
        Path target = worldRoot.resolve(relative);
        SnapshotManifest.Entry recorded = manifest.get(relative);
        if (recorded != null && Files.isRegularFile(target)
                && Files.size(target) == recorded.size
                && Files.getLastModifiedTime(target).toMillis() == recorded.modifiedMillis) {
            result.skipped++;
            return;
        }
        Path parent = target.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        AtomicFiles.copy(source, target);
        if (recorded != null) {
            restoreModifiedTime(target, recorded.modifiedMillis);
        }
        result.copied++;
        result.copiedBytes += size;
    }

    /** 把 mtime 拨回记录值；设不上不影响内容正确性，只记不抛。 */
    private static void restoreModifiedTime(Path target, long millis) {
        try {
            Files.setLastModifiedTime(target, FileTime.fromMillis(millis));
        } catch (IOException ignored) {
            // 时间戳设不上不影响内容正确性
        }
    }
}
