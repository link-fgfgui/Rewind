package cc.sighs.rewind.snapshot;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.GZIPOutputStream;
import cc.sighs.rewind.common.RewindLog;
import cc.sighs.rewind.common.spi.RewindPlatform;
import cc.sighs.rewind.common.spi.RewindPlatforms;

/**
 * 离线纯 NBT 重建（TODO2 Phase2 §4.3：WorldRepair 纯 NBT 流式装配 Anvil 重写器）。
 *
 * <p>Phase 2 弃用 4 KiB 物理扇区快照后，离线修复不再盲写扇区二进制块：从目标槽位的
 * zpack 链还原出全量未压缩 NBT，在内存中装配 Anvil（5 字节头、扇区对齐、超 1 MiB
 * 自动 `.mcc` 外溢），覆盖回 {@code /saves}。{@code .dat}（level/玩家/存档数据）
 * 由 raw 重新 gzip 落盘；其余松散文件（datapacks、配置、进度 JSON 等）仍从槽位目录拷贝。
 *
 * <p>适用即切换、不适用即回退：以下任一成立时返回 null，调用方走块存储旧路——
 * 无平台、无 zpack、链损坏、未知维度布局、清单与链对不上（缺块/多块）。
 * 回退永远安全：旧路是完备快照。写盘全程原子替换（tmp + move），中途失败不留半个文件；
 * 调用方在重建抛异常时同样回退旧路（旧路按清单比对收敛，最终一致）。
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

        /** 账本打包坐标 → {@code {x, z}}；解不出返回 null（整单回退）。 */
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
                try {
                    return platform.chunkDirRelative(
                            ZPackCheckpoint.typeToKind(zpackType), dimensionId);
                } catch (Throwable t) {
                    return null;
                }
            }

            @Override
            public int[] unpack(long packed) {
                try {
                    return platform.unpackChunkPos(packed);
                } catch (Throwable t) {
                    return null;
                }
            }
        };
    }

    /** 快捷：用已安装平台（未安装即 null → 重建返回 null → 旧路）。 */
    public static Layout installedLayout() {
        return platformLayout(RewindPlatforms.get());
    }

    /**
     * 纯 NBT 重建槽位进活动存档。
     *
     * @return 镜像结果；不适用返回 null（调用方走 {@code SnapshotBlockIo.restoreInto}）
     * @throws IOException 写盘硬失败（调用方可回退旧路收敛，见类注释）
     */
    public static SnapshotMirror.Result rebuild(Path worldRoot, String slot, Layout layout)
            throws IOException {
        if (worldRoot == null || slot == null || layout == null) {
            return null;
        }
        if (!Files.isRegularFile(SnapshotLayout.zpackFile(worldRoot, slot))) {
            return null;
        }
        SnapshotIndex index = SnapshotIndex.load(SnapshotLayout.indexFile(worldRoot));
        SnapshotMeta meta = index.get(slot);
        if (meta == null) {
            return null;
        }
        Map<String, byte[]> resolved;
        try {
            resolved = ZPackCheckpoint.tryResolve(worldRoot, slot);
        } catch (Throwable t) {
            RewindLog.LOGGER.warn("Rewind: zpack chain of {} unreadable, keeping block store path", slot, t);
            return null;
        }
        if (resolved == null) {
            return null;
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
                return null;
            }
            if (!key.isChunkLike()) {
                dataRaws.put(key.id, e.getValue());
                continue;
            }
            byte[] raw = e.getValue();
            String dir = layout.dirRelative(key.targetType, key.id);
            if (dir == null) {
                return null;
            }
            int[] xz = layout.unpack(key.chunkPos);
            if (xz == null || xz.length < 2) {
                return null;
            }
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

        // -- 完备性门：清单 .mca 与重建 .mca 必须两两对上，否则链有洞 → 回退 --
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
                return null;
            }
        }
        for (String mca : regions.keySet()) {
            if (!manifestMca.contains(mca)) {
                return null;
            }
        }
        // 槽位目录里 manifest 之外的 .mca（老格式残留）：无法验证 → 回退
        if (slotDir != null && Files.isDirectory(slotDir)) {
            for (String loose : SnapshotMirror.listFiles(slotDir)) {
                if (SnapshotBlockStore.isEncoded(loose) && manifest.get(loose) == null) {
                    return null;
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
                chunks.put(packed, new AnvilPacker.Chunk(x, z, chunk.getValue(),
                        AnvilPacker.VERSION_DEFLATE));
            }
            AnvilPacker.Packed packedRegion;
            try {
                packedRegion = AnvilPacker.pack(chunks, timestampSeconds);
            } catch (Throwable t) {
                return null;
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
        // 否则链洞 → 回退）。
        for (String mcc : manifestMcc) {
            if (producedMcc.contains(mcc)) {
                continue;
            }
            MccRef ref = parseMcc(mcc);
            if (ref == null) {
                return null;
            }
            String coord = ref.mca + "#" + ref.x + "#" + ref.z;
            Map<Long, byte[]> region = regions.get(ref.mca);
            boolean packedNormal = region != null && region.containsKey(coordKey(ref.x, ref.z));
            if (!packedNormal && !deletedCoords.contains(coord)) {
                return null;
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
        // .dat：链中有 raw → gzip 写；链中缺失但槽位有文件 → 照拷；两边都没有 → 回退旧路
        // （与旧路“缺文件即抛”同义，错误语义不变）。
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
            } else if (slotDir != null && Files.isRegularFile(slotDir.resolve(path))) {
                copyLoose(worldRoot, slotDir, path, manifest, result, wanted);
            } else {
                return null;
            }
        }
        // 其余松散文件：manifest 有的照拷；槽位多出来的（非 .mca）同样照拷（与旧路一致）；
        // 槽位与清单两边都没有的非 .mca 路径 → 回退（旧路会抛，错误语义不变）。
        Set<String> loosePaths = new LinkedHashSet<>();
        for (String path : manifest.paths()) {
            if (!path.endsWith(".mcc") && !SnapshotBlockStore.isEncoded(path)
                    && !ZPackCheckpoint.isNbtDataFile(path)) {
                loosePaths.add(path);
            }
        }
        if (slotDir != null && Files.isDirectory(slotDir)) {
            for (String loose : SnapshotMirror.listFiles(slotDir)) {
                if (!loose.endsWith(".mcc") && !SnapshotBlockStore.isEncoded(loose)) {
                    loosePaths.add(loose);
                }
            }
        }
        List<String> orderedLoose = new ArrayList<>(loosePaths);
        Collections.sort(orderedLoose);
        for (String path : orderedLoose) {
            if (slotDir == null || !Files.isRegularFile(slotDir.resolve(path))) {
                return null;
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
        pruneEmptyDirectories(worldRoot);
        Collections.sort(result.files);
        return result;
    }

    // ---------------------------------------------------------- 内部

    private static long coordKey(int x, int z) {
        return (((long) x) << 32) | (z & 0xFFFFFFFFL);
    }

    /** manifest .mcc 路径 → 所属 region 与坐标；形非法返回 null。 */
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

    private static MccRef parseMcc(String mccRelative) {
        String path = SnapshotLayout.normalize(mccRelative);
        int slash = path.lastIndexOf('/');
        if (slash < 0) {
            return null;
        }
        String dir = path.substring(0, slash);
        String name = path.substring(slash + 1);
        if (!name.startsWith("c.") || !name.endsWith(".mcc")) {
            return null;
        }
        String coords = name.substring(2, name.length() - 4);
        int dot = coords.lastIndexOf('.');
        if (dot < 0) {
            return null;
        }
        int x;
        int z;
        try {
            x = Integer.parseInt(coords.substring(0, dot));
            z = Integer.parseInt(coords.substring(dot + 1));
        } catch (NumberFormatException e) {
            return null;
        }
        return new MccRef(SnapshotLayout.normalize(dir + "/" + SnapshotChunkDecoder.regionFileName(x, z)), x, z);
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
        writeAtomically(target, bytes);
        SnapshotManifest.Entry recorded = manifest.get(relative);
        long mtime = recorded == null ? System.currentTimeMillis() : recorded.modifiedMillis;
        try {
            Files.setLastModifiedTime(target, FileTime.fromMillis(mtime));
        } catch (IOException ignored) {
            // 时间戳设不上不影响内容正确性
        }
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
        Path temp = target.resolveSibling(target.getFileName().toString() + SnapshotMirror.TEMP_SUFFIX);
        try {
            Files.copy(source, temp, StandardCopyOption.REPLACE_EXISTING);
            try {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicUnsupported) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException | RuntimeException e) {
            Files.deleteIfExists(temp);
            throw e;
        }
        if (recorded != null) {
            try {
                Files.setLastModifiedTime(target, FileTime.fromMillis(recorded.modifiedMillis));
            } catch (IOException ignored) {
                // 时间戳设不上不影响内容正确性
            }
        }
        result.copied++;
        result.copiedBytes += size;
    }

    private static void writeAtomically(Path target, byte[] bytes) throws IOException {
        Path temp = target.resolveSibling(target.getFileName().toString() + SnapshotMirror.TEMP_SUFFIX);
        try {
            Files.write(temp, bytes);
            try {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicUnsupported) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException | RuntimeException e) {
            Files.deleteIfExists(temp);
            throw e;
        }
    }

    private static void pruneEmptyDirectories(Path root) throws IOException {
        if (!Files.isDirectory(root)) {
            return;
        }
        Files.walkFileTree(root, new java.nio.file.SimpleFileVisitor<Path>() {
            @Override
            public java.nio.file.FileVisitResult postVisitDirectory(Path dir, IOException exc) {
                if (dir.equals(root)) {
                    return java.nio.file.FileVisitResult.CONTINUE;
                }
                if (SnapshotLayout.isExcluded(SnapshotLayout.relativize(root, dir))) {
                    return java.nio.file.FileVisitResult.CONTINUE;
                }
                try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
                    if (!stream.iterator().hasNext()) {
                        Files.deleteIfExists(dir);
                    }
                } catch (IOException ignored) {
                    // 目录非空或被占用：留着即可
                }
                return java.nio.file.FileVisitResult.CONTINUE;
            }
        });
    }
}
