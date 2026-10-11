package cc.sighs.rewind.snapshot;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * 目录安全切换与启动自愈（TODO2 §4.2 三阶段重命名 + Startup Self-Healing）。
 *
 * <p>背景：文件系统对非空目录移动的物理限制——单步 {@code Files.move(ATOMIC_MOVE)}
 * 在非空目录会抛 {@code DirectoryNotEmptyException}。GC 采用严格三阶段：
 * {@code .compact_tmp → 让位 .compact_old → 就位 rewind_snapshots}。
 *
 * <p>自愈检查（游戏启动加载存档或进入 WorldRepairScreen 时执行）：
 * <ol>
 *   <li>{@code rewind_snapshots/} 不存在但 {@code .compact_old/} 存在：
 *       6a 成功后被 SIGKILL，立即重命名 {@code .compact_old/ → rewind_snapshots/}；</li>
 *   <li>两者同时存在：6b 已完成、6c 清理未完成，新快照已生效，删除残余 {@code .compact_old/}；</li>
 *   <li>存在 {@code .compact_tmp/}：重烘焙中途终止，直接清理。</li>
 * </ol>
 *
 * <p>纯 JDK、Java 8 兼容。目录级移动用普通 {@code Files.move(REPLACE_EXISTING)}
 *（非 ATOMIC），因为非空目录原子移动本就不被支持；安全由“旧目录先让位、新目录
 * 再就位、崩溃后自愈”保证，而非单步原子性。
 */
public final class SnapshotSelfHealing {
    private SnapshotSelfHealing() {
    }

    /** 自愈结果。 */
    public static final class Result {
        public final boolean recoveredOld;
        public final boolean cleanedOld;
        public final boolean cleanedTmp;
        /** 崩溃残留的原子替换临时文件（`*.rewind-tmp` / `index.properties.tmp`）是否清过。 */
        public final boolean cleanedTemps;
        public final List<String> notes = new ArrayList<>();

        Result(boolean recoveredOld, boolean cleanedOld, boolean cleanedTmp, boolean cleanedTemps) {
            this.recoveredOld = recoveredOld;
            this.cleanedOld = cleanedOld;
            this.cleanedTmp = cleanedTmp;
            this.cleanedTemps = cleanedTemps;
        }

        public boolean touched() {
            return recoveredOld || cleanedOld || cleanedTmp || cleanedTemps;
        }

        @Override
        public String toString() {
            return "SelfHealing{recoveredOld=" + recoveredOld
                    + " cleanedOld=" + cleanedOld + " cleanedTmp=" + cleanedTmp
                    + " cleanedTemps=" + cleanedTemps
                    + " notes=" + notes + "}";
        }
    }

    /**
     * 启动预检自愈（幂等，可每次启动调用）。
     *
     * <p>调用前提：世界未加载、无建点/回滚在途（此时才没有合法的 `*.rewind-tmp` 在写）。
     * 本类现有调用方（`WorldRepairScreen` 入口、重烘焙开头）都满足这条。
     *
     * @param worldRoot 存档目录（level.dat 所在目录）
     */
    public static Result heal(Path worldRoot) throws IOException {
        // 世界根不可用时直接返回空结果（调用方是界面入口，不应炸）
        if (worldRoot == null) {
            return new Result(false, false, false, false);
        }
        Path snapshots = SnapshotLayout.snapshotRoot(worldRoot);
        Path oldDir = SnapshotLayout.compactOldDir(worldRoot);
        Path tmpDir = SnapshotLayout.compactTmpDir(worldRoot);
        boolean recoveredOld = false;
        boolean cleanedOld = false;
        boolean cleanedTmp = false;
        Result result = new Result(false, false, false, false);
        boolean hasSnapshots = Files.isDirectory(snapshots);
        boolean hasOld = Files.isDirectory(oldDir);
        boolean hasTmp = Files.isDirectory(tmpDir);
        if (!hasSnapshots && hasOld) {
            // 6a 成功后被杀：原快照毫发无损地躺在 .compact_old，搬回来
            moveDir(oldDir, snapshots);
            recoveredOld = true;
            result.notes.add("recovered .compact_old -> rewind_snapshots");
        } else if (hasSnapshots && hasOld) {
            // 6b 已完成、6c 未完成：新快照已生效，删残余旧目录
            deleteRecursively(oldDir);
            cleanedOld = true;
            result.notes.add("removed residual .compact_old");
        }
        if (Files.isDirectory(tmpDir)) {
            deleteRecursively(tmpDir);
            cleanedTmp = true;
            result.notes.add("removed interrupted .compact_tmp");
        }
        // 崩溃残留的原子替换临时文件：建点/镜像/zpack 写盘都是“写 tmp 再 move”，
        // 崩在 move 之前就剩一个 `.rewind-tmp`（槽位目录里与根目录下都可能有）。
        // 离线时没有任何合法写者在跑，见之即删；索引 tmp 只在正式索引健在时清
        // （正式索引缺席而仅剩 tmp 时不动它——无法分辨写完与否，不替它做主）。
        int temps = sweepTempFiles(snapshots);
        boolean cleanedTemps = temps > 0;
        if (cleanedTemps) {
            result.notes.add("removed " + temps + " orphaned temp files");
        }
        Result done = new Result(recoveredOld, cleanedOld, cleanedTmp, cleanedTemps);
        done.notes.addAll(result.notes);
        return done;
    }

    /**
     * 界面入口用的自愈：失败只返回空结果不抛异常（残留目录由下次调用再试）。
     *
     * <p>进 {@code WorldRepairScreen} 与离线修复入口时调一次，消除 GC 让位/就位之间的崩溃窗口。
     */
    public static Result healQuietly(Path worldRoot) {
        try {
            return heal(worldRoot);
        } catch (Throwable t) {
            return new Result(false, false, false, false);
        }
    }

    /**
     * 三阶段切换：让位 + 就位。调用前临时区必须已校验通过。
     *
     * <p>步骤 6a 让位 {@code rewind_snapshots → .compact_old}；
     * 步骤 6b 就位 {@code .compact_tmp → rewind_snapshots}。
     * 6c 清理由调用方（后台删除 .compact_old）或下次 {@link #heal} 完成。
     */
    public static void swapInCompacted(Path worldRoot) throws IOException {
        Path snapshots = SnapshotLayout.snapshotRoot(worldRoot);
        Path oldDir = SnapshotLayout.compactOldDir(worldRoot);
        Path tmpDir = SnapshotLayout.compactTmpDir(worldRoot);
        if (!Files.isDirectory(tmpDir)) {
            throw new IOException("compact workspace missing: " + tmpDir);
        }
        if (Files.exists(oldDir)) {
            deleteRecursively(oldDir);
        }
        if (Files.isDirectory(snapshots)) {
            // 6a 让位（非空目录普通移动；失败直接抛，磁盘原文件不动）
            moveDir(snapshots, oldDir);
        }
        try {
            // 6b 就位
            moveDir(tmpDir, snapshots);
        } catch (IOException e) {
            // 6b 失败：尝试把让位的目录搬回来，原快照不丢
            try {
                if (!Files.isDirectory(snapshots) && Files.isDirectory(oldDir)) {
                    moveDir(oldDir, snapshots);
                }
            } catch (IOException ignored) {
                // 保留现场给下次 heal
            }
            throw e;
        }
    }

    /** 后台清理旧目录（6c）；失败只记不抛，由下次 heal 兜底。 */
    public static void cleanupOld(Path worldRoot) {
        try {
            Path oldDir = SnapshotLayout.compactOldDir(worldRoot);
            if (Files.isDirectory(oldDir)) {
                deleteRecursively(oldDir);
            }
        } catch (IOException ignored) {
            // 下次 heal 会再试
        }
    }

    /** 中途异常清理临时区，原磁盘文件 100% 不动。 */
    public static void discardTmp(Path worldRoot) {
        try {
            Path tmpDir = SnapshotLayout.compactTmpDir(worldRoot);
            if (Files.isDirectory(tmpDir)) {
                deleteRecursively(tmpDir);
            }
        } catch (IOException ignored) {
            // 下次 heal 会再试
        }
    }

    // ---------------------------------------------------------- 内部

    /**
     * 清掉快照目录树里崩溃残留的原子替换临时文件，返回清掉的个数。
     *
     * <p>认两种：全树任意位置的 {@code *.rewind-tmp}（建点/镜像/zpack 写盘的中途残留，
     * {@code SnapshotLayout.isExcluded} 本来就不收它们），以及根目录下、
     * 且正式索引健在时的 {@code index.properties.tmp}。
     */
    static int sweepTempFiles(Path snapshots) throws IOException {
        if (snapshots == null || !Files.isDirectory(snapshots)) {
            return 0;
        }
        final int[] removed = {0};
        Files.walkFileTree(snapshots, new java.nio.file.SimpleFileVisitor<Path>() {
            @Override
            public java.nio.file.FileVisitResult visitFile(Path file,
                    java.nio.file.attribute.BasicFileAttributes attrs) throws IOException {
                String name = file.getFileName().toString();
                if (name.endsWith(SnapshotMirror.TEMP_SUFFIX)) {
                    Files.deleteIfExists(file);
                    removed[0]++;
                }
                return java.nio.file.FileVisitResult.CONTINUE;
            }

            @Override
            public java.nio.file.FileVisitResult visitFileFailed(Path file, IOException exc) {
                return java.nio.file.FileVisitResult.CONTINUE;
            }
        });
        Path indexTmp = snapshots.resolve(SnapshotLayout.INDEX_FILE_NAME + ".tmp");
        if (Files.isRegularFile(SnapshotLayout.indexFile(snapshots.getParent()))
                && Files.deleteIfExists(indexTmp)) {
            removed[0]++;
        }
        return removed[0];
    }

    static void moveDir(Path from, Path to) throws IOException {        Path parent = to.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        try {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicUnsupported) {
            // 非空目录原子移动不受支持：退回普通移动（语义即重命名指针）
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    static void deleteRecursively(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        if (!Files.isDirectory(dir)) {
            Files.deleteIfExists(dir);
            return;
        }
        // 先删文件后删目录；用 walk 收集后逆序删，避免 DirectoryStream 嵌套占用
        List<Path> paths = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (Path child : stream) {
                collect(child, paths);
            }
        }
        paths.add(dir);
        // 深层优先：按路径长度降序
        paths.sort((a, b) -> Integer.compare(b.toString().length(), a.toString().length()));
        for (Path p : paths) {
            Files.deleteIfExists(p);
        }
    }

    private static void collect(Path path, List<Path> out) throws IOException {
        if (Files.isDirectory(path)) {
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(path)) {
                for (Path child : stream) {
                    collect(child, out);
                }
            }
        }
        out.add(path);
    }
}
