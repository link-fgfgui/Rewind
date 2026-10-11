package cc.sighs.rewind.test.realsave;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import cc.sighs.rewind.snapshot.SnapshotBlockStore;
import cc.sighs.rewind.snapshot.SnapshotChunkDecoder;
import cc.sighs.rewind.snapshot.SnapshotLayout;
import cc.sighs.rewind.snapshot.ZstdDelta;

/**
 * TEST-BENCH-01：相同变动世界下三大存储方案对照（真实生产备份 T4 13:00 → 14:00）。
 *
 * <p>两种口径（门限只管增量口径——快照架构优化的正是“再建一个点多付多少”）：
 * <ul>
 *   <li><b>增量（marginal，门限所在）</b>：第二个备份相对第一个的新增成本。
 *       A＝第二个 {@code .tar.gz} 落盘字节；B＝备份 2 相对备份 1 的新增去重扇区
 *       ＋变化松散文件新份字节；C＝zpack 差异补丁（含新建区块的 FULL，Level-B 切片实测）。
 *       门限：B/A ≥70%，C/B ≥80%，C/A ≥93%。</li>
 *   <li><b>全量（total，仅报告）</b>：两个备份的持有总量。A＝两 {@code .tar.gz} 之和；
 *       B＝跨两备份去重扇区＋去重松散；C＝备份 1 全区块 {@code zstdFull}＋补丁。
 *       全量口径不对 B/C 设门：扇区搬家残留的 stale 扇区会污染跨全量去重（见输出注释），
 *       而生产管线走增量累积，从不做“两份全量对消”。</li>
 * </ul>
 *
 * <p>单位统一为“落在盘上的字节”：A 取 tar.gz 文件字节；B 按 4096/扇区；C 取 zstd 产出字节。
 * 块/包级索引开销（约 1~2%）忽略并注明。
 *
 * <p>门控：需要 {@code mc-backups.zip}（{@code REWIND_TEST_BACKUPS_ZIP}）与 Level-B 切片；
 * 缺席即跳过。流式处理，属 nightly 级耗时。
 */
class RealSaveBenchmarkTest {

    private static final String T4_A = "mc-backups/mc-backup-20261010-130006.tar.gz";
    private static final String T4_B = "mc-backups/mc-backup-20261010-140005.tar.gz";

    // ---------------------------------------------------------- 最小 tar 顺序读器

    /** 仅够顺序扫描的最小 ustar/gnu-tar 读器（顺序流，不回退）。 */
    private static final class TarReader implements Closeable {
        static final class Entry {
            final String name;
            final long size;
            final boolean regular;

            Entry(String name, long size, boolean regular) {
                this.name = name;
                this.size = size;
                this.regular = regular;
            }
        }

        private final InputStream in;
        private String pendingLongName;

        TarReader(InputStream in) {
            this.in = in;
        }

        Entry next() throws IOException {
            while (true) {
                byte[] header = new byte[512];
                int read = readFully(header, 0, 512);
                if (read < 0) {
                    return null;
                }
                if (read < 512) {
                    throw new EOFException("truncated tar header");
                }
                boolean zero = true;
                for (byte b : header) {
                    if (b != 0) {
                        zero = false;
                        break;
                    }
                }
                if (zero) {
                    return null;
                }
                String name = cString(header, 0, 100);
                String prefix = cString(header, 345, 155);
                if (!prefix.isEmpty()) {
                    name = prefix + "/" + name;
                }
                long size = parseSize(header);
                char type = (char) header[156];
                if (type == 'L') {
                    // GNU 长文件名：数据块即真实名
                    pendingLongName = new String(readData(size), StandardCharsets.UTF_8);
                    int cut = pendingLongName.indexOf('\0');
                    if (cut >= 0) {
                        pendingLongName = pendingLongName.substring(0, cut);
                    }
                    continue;
                }
                if (type == 'x' || type == 'g') {
                    skipRaw(size);
                    continue;
                }
                if (pendingLongName != null) {
                    name = pendingLongName;
                    pendingLongName = null;
                }
                boolean regular = type == '0' || type == '\0';
                boolean dir = type == '5' || name.endsWith("/");
                if (dir) {
                    continue;
                }
                return new Entry(name, size, regular);
            }
        }

        byte[] readData(Entry entry) throws IOException {
            return readData(entry.size);
        }

        private byte[] readData(long size) throws IOException {
            if (size < 0 || size > Integer.MAX_VALUE) {
                throw new IOException("unreasonable tar member size: " + size);
            }
            byte[] data = new byte[(int) size];
            readFully(data, 0, data.length);
            long pad = (512 - (size % 512)) % 512;
            skipFully(pad);
            return data;
        }

        void skipData(Entry entry) throws IOException {
            skipFully(entry.size + (512 - (entry.size % 512)) % 512);
        }

        private void skipRaw(long size) throws IOException {
            skipFully(size + (512 - (size % 512)) % 512);
        }

        private int readFully(byte[] buf, int off, int len) throws IOException {
            int total = 0;
            while (total < len) {
                int n = in.read(buf, off + total, len - total);
                if (n < 0) {
                    return total == 0 ? -1 : total;
                }
                total += n;
            }
            return total;
        }

        private void skipFully(long n) throws IOException {
            while (n > 0) {
                long skipped = in.skip(n);
                if (skipped <= 0) {
                    if (in.read() < 0) {
                        throw new EOFException("truncated tar stream");
                    }
                    skipped = 1;
                }
                n -= skipped;
            }
        }

        private static String cString(byte[] header, int off, int len) {
            int end = off;
            while (end < off + len && header[end] != 0) {
                end++;
            }
            return new String(header, off, end - off, StandardCharsets.UTF_8);
        }

        private static long parseSize(byte[] header) throws IOException {
            // GNU base-256（首字节置位）兼容
            if ((header[124] & 0x80) != 0) {
                long value = header[124] & 0x7FL;
                for (int i = 125; i < 136; i++) {
                    value = (value << 8) | (header[i] & 0xFF);
                }
                return value;
            }
            String text = cString(header, 124, 12).trim();
            if (text.isEmpty()) {
                return 0L;
            }
            try {
                return Long.parseLong(text, 8);
            } catch (NumberFormatException e) {
                throw new IOException("bad tar size: " + text);
            }
        }

        @Override
        public void close() throws IOException {
            in.close();
        }
    }

    // ---------------------------------------------------------- 驱动

    private static String zipPath() {
        String override = System.getenv("REWIND_TEST_BACKUPS_ZIP");
        if (override != null && !override.isEmpty()) {
            return override;
        }
        return Paths.get("..", "..", "rewind-others", "mc-backups.zip").toString();
    }

    private static Path slicesDir() {
        String override = System.getenv("REWIND_TEST_SLICES_DIR");
        if (override != null && !override.isEmpty()) {
            return Paths.get(override);
        }
        return Paths.get("build", "goldenslices-t4");
    }

    private static String hex(byte[] bytes) {
        char[] digits = "0123456789abcdef".toCharArray();
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(digits[(b >> 4) & 0xF]).append(digits[b & 0xF]);
        }
        return sb.toString();
    }

    private static byte[] gunzip(byte[] data) throws IOException {
        try (GZIPInputStream in = new GZIPInputStream(new java.io.ByteArrayInputStream(data));
                ByteArrayOutputStream out = new ByteArrayOutputStream(data.length * 2)) {
            byte[] buf = new byte[65536];
            int read;
            while ((read = in.read(buf)) >= 0) {
                out.write(buf, 0, read);
            }
            return out.toByteArray();
        }
    }

    private TarReader openTar(ZipFile zip, String member) throws IOException {
        ZipEntry entry = zip.getEntry(member);
        Assumptions.assumeTrue(entry != null, "zip 缺成员: " + member);
        return new TarReader(new GZIPInputStream(
                new java.io.BufferedInputStream(zip.getInputStream(entry), 1 << 16)));
    }

    @Test
    @DisplayName("TEST-BENCH-01: 真实备份 A/B/C 三大方案存储对照")
    void threeSchemeComparison() throws Exception {
        String zip = zipPath();
        Assumptions.assumeTrue(Files.isRegularFile(Paths.get(zip)), "mc-backups.zip 缺席，跳过基准");
        Assumptions.assumeTrue(Files.isDirectory(slicesDir()), "Level-B 切片缺席，跳过基准");
        long started = System.nanoTime();
        try (ZipFile zf = new ZipFile(zip)) {
            // ---- A：整文件归档（tar.gz 落盘字节） ----
            ZipEntry ea = zf.getEntry(T4_A);
            ZipEntry eb = zf.getEntry(T4_B);
            Assumptions.assumeTrue(ea != null && eb != null, "zip 缺 T4 归档");
            long costA = compressedSize(zf, ea) + compressedSize(zf, eb);

            // ---- 成员表（名 → 原始字节，头扫描） ----
            Map<String, Long> membersA = listMembers(zf, T4_A);
            Map<String, Long> membersB = listMembers(zf, T4_B);

            // ---- B：去重扇区（两备份全部 .mca，分开记以便算增量新增） ----
            Set<String> sectorsA = new HashSet<>();
            Set<String> sectorsB = new HashSet<>();
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            long mcaBytes = 0L;
            String[] members = new String[]{T4_A, T4_B};
            @SuppressWarnings("unchecked")
            Set<String>[] sectorSets = new Set[]{sectorsA, sectorsB};
            for (int t = 0; t < members.length; t++) {
                try (TarReader tar = openTar(zf, members[t])) {
                    TarReader.Entry e;
                    while ((e = tar.next()) != null) {
                        if (!e.regular
                                || !SnapshotBlockStore.isEncoded(SnapshotLayout.normalize(e.name))) {
                            tar.skipData(e);
                            continue;
                        }
                        byte[] mca = tar.readData(e);
                        mcaBytes += mca.length;
                        for (int off = 0; off < mca.length; off += 4096) {
                            int len = Math.min(4096, mca.length - off);
                            byte[] sector = new byte[len];
                            System.arraycopy(mca, off, sector, 0, len);
                            sectorSets[t].add(hex(sha.digest(sector)));
                        }
                    }
                }
            }
            Set<String> sectorsUnion = new HashSet<>(sectorsA);
            sectorsUnion.addAll(sectorsB);
            Set<String> sectorsNew = new HashSet<>(sectorsB);
            sectorsNew.removeAll(sectorsA);
            long uniqueSectorBytes = (long) sectorsUnion.size() * 4096L;
            long newSectorBytes = (long) sectorsNew.size() * 4096L;

            // ---- B/C 共用的非 .mca 去重字节 ----
            long dedupOther = 0L;
            Set<String> otherPaths = new HashSet<>();
            for (String name : membersA.keySet()) {
                if (!isMcaPath(name)) {
                    otherPaths.add(name);
                }
            }
            for (String name : membersB.keySet()) {
                if (!isMcaPath(name)) {
                    otherPaths.add(name);
                }
            }
            List<String> orderedOther = new ArrayList<>(otherPaths);
            Collections.sort(orderedOther);
            Map<String, byte[]> otherA = new HashMap<>();
            Map<String, byte[]> otherB = new HashMap<>();
            try (TarReader ta = openTar(zf, T4_A)) {
                TarReader.Entry e;
                while ((e = ta.next()) != null) {
                    if (e.regular && otherPaths.contains(e.name) && e.size <= 8L * 1024 * 1024) {
                        otherA.put(e.name, ta.readData(e));
                    } else {
                        ta.skipData(e);
                    }
                }
            }
            try (TarReader tb = openTar(zf, T4_B)) {
                TarReader.Entry e;
                while ((e = tb.next()) != null) {
                    if (e.regular && otherPaths.contains(e.name) && e.size <= 8L * 1024 * 1024) {
                        otherB.put(e.name, tb.readData(e));
                    } else {
                        tb.skipData(e);
                    }
                }
            }
            // 超大成员（>8MiB，正常是 datapacks 压缩包）：按名去重计一字节账
            // （只比尺寸，内容级差异计为变化——近似，见输出注释）。
            long changedOtherBytes = 0L;
            for (String name : orderedOther) {
                byte[] a = otherA.get(name);
                byte[] b = otherB.get(name);
                if (a == null && b == null) {
                    Long sa = membersA.get(name);
                    Long sb = membersB.get(name);
                    dedupOther += sa == null ? 0L : sa;
                    if (sb != null && (sa == null || !sb.equals(sa))) {
                        dedupOther += sb;
                        changedOtherBytes += sb;
                    }
                    continue;
                }
                if (a != null && b != null && java.util.Arrays.equals(a, b)) {
                    dedupOther += a.length;
                } else {
                    if (a != null) {
                        dedupOther += a.length;
                    }
                    if (b != null) {
                        dedupOther += b.length;
                        changedOtherBytes += b.length;
                    }
                }
            }
            long costB = uniqueSectorBytes + dedupOther;

            // ---- C：备份 1 全区块 zstdFull ＋ 差异补丁（Level-B 切片） ----
            long baseFulls = 0L;
            long baseChunks = 0L;
            long skippedLz4 = 0L;
            try (TarReader ta = openTar(zf, T4_A)) {
                TarReader.Entry e;
                while ((e = ta.next()) != null) {
                    if (!e.regular
                            || !SnapshotBlockStore.isEncoded(SnapshotLayout.normalize(e.name))) {
                        ta.skipData(e);
                        continue;
                    }
                    byte[] mca = ta.readData(e);
                    int[] region = regionCoords(e.name);
                    if (region == null || mca.length < 8192) {
                        continue;
                    }
                    for (int i = 0; i < 1024; i++) {
                        int loc = getInt(mca, i * 4);
                        if (loc == 0) {
                            continue;
                        }
                        int x = (i % 32) + region[0] * 32;
                        int z = (i / 32) + region[1] * 32;
                        SnapshotChunkDecoder.Sector sector;
                        try {
                            sector = SnapshotChunkDecoder.readSectorFromBytes(mca, x, z);
                        } catch (IOException bad) {
                            continue;
                        }
                        if (sector == null || sector.external) {
                            continue;
                        }
                        byte[] raw;
                        try {
                            raw = SnapshotChunkDecoder.decompress(sector.versionId, sector.compressed);
                        } catch (IOException lz4) {
                            skippedLz4++;
                            continue;
                        }
                        baseFulls += ZstdDelta.compressFull(raw, ZstdDelta.DEFAULT_LEVEL).length;
                        baseChunks++;
                    }
                }
            }
            // 差异补丁：复用 Level-B 切片对（与回放测试同一数据源）
            Path slices = slicesDir();
            long patchTotal = 0L;
            int pairs = 0;
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(slices, "t4_pair_*_base.nbt")) {
                for (Path baseFile : stream) {
                    String name = baseFile.getFileName().toString();
                    Path targetFile = slices.resolve(
                            name.substring(0, name.length() - "_base.nbt".length()) + "_target.nbt");
                    byte[] base = Files.readAllBytes(baseFile);
                    byte[] target = Files.readAllBytes(targetFile);
                    byte[] dict = base.length == 0 ? null : base;
                    patchTotal += ZstdDelta.compressDelta(target, dict, ZstdDelta.DEFAULT_LEVEL).length;
                    pairs++;
                }
            }
            long datFull = 0L;
            long datPatch = 0L;
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(slices, "t4_dat_*_base.dat")) {
                for (Path baseFile : stream) {
                    String name = baseFile.getFileName().toString();
                    Path targetFile = slices.resolve(
                            name.substring(0, name.length() - "_base.dat".length()) + "_target.dat");
                    byte[] baseRaw = gunzip(Files.readAllBytes(baseFile));
                    byte[] targetRaw = gunzip(Files.readAllBytes(targetFile));
                    datFull += ZstdDelta.compressFull(baseRaw, ZstdDelta.DEFAULT_LEVEL).length;
                    datPatch += ZstdDelta.compressDelta(targetRaw, baseRaw, ZstdDelta.DEFAULT_LEVEL).length;
                }
            }
            long costC = baseFulls + patchTotal + datFull + datPatch + dedupOther;

            // ---- 增量口径（门限所在）：第二个备份的新增成本 ----
            long costAm = compressedSize(zf, eb);
            long costBm = newSectorBytes + changedOtherBytes;
            long costCm = patchTotal + datPatch;
            double saveBAm = 1.0 - costBm / (double) costAm;
            double saveCBm = 1.0 - costCm / (double) costBm;
            double saveCAm = 1.0 - costCm / (double) costAm;
            // ---- 全量口径（仅报告）：两备份持有总量 ----
            double saveBA = 1.0 - costB / (double) costA;
            double saveCB = 1.0 - costC / (double) costB;
            double saveCA = 1.0 - costC / (double) costA;
            long millis = (System.nanoTime() - started) / 1000000L;
            System.out.println(String.format(Locale.ROOT,
                    "BENCH marginal A=%d B=%d(newSectors=%d,changedOther=%d) C=%d(patch=%d,datPatch=%d) "
                            + "saveBA=%.2f%% saveCB=%.2f%% saveCA=%.2f%%",
                    costAm, costBm, sectorsNew.size(), changedOtherBytes,
                    costCm, patchTotal, datPatch,
                    saveBAm * 100, saveCBm * 100, saveCAm * 100));
            System.out.println(String.format(Locale.ROOT,
                    "BENCH total A=%d B=%d(sectors=%d,%.1fMiB mca) C=%d[baseFulls=%d/%d chunks,lz4skip=%d,other=%d] "
                            + "saveBA=%.2f%% saveCB=%.2f%% saveCA=%.2f%% took=%dms "
                            + "注：全量口径含扇区搬家 stale 残留，仅供参考",
                    costA, costB, sectorsUnion.size(), mcaBytes / 1048576.0, costC,
                    baseFulls, baseChunks, skippedLz4, dedupOther,
                    saveBA * 100, saveCB * 100, saveCA * 100, millis));
            assertTrue(saveBAm >= 0.70, "增量 B 相对 A 削减不足 70%: " + saveBAm);
            assertTrue(saveCBm >= 0.80, "增量 C 相对 B 削减不足 80%: " + saveCBm);
            assertTrue(saveCAm >= 0.93, "增量 C 相对 A 削减不足 93%: " + saveCAm);
        }
    }

    private static long compressedSize(ZipFile zip, ZipEntry entry) throws IOException {
        long compressed = entry.getCompressedSize();
        if (compressed >= 0) {
            return compressed;
        }
        // 回退：压缩尺寸未知时按流式计字节
        long total = 0L;
        try (InputStream in = zip.getInputStream(entry)) {
            byte[] buf = new byte[65536];
            int read;
            while ((read = in.read(buf)) >= 0) {
                total += read;
            }
        }
        return total;
    }

    private static Map<String, Long> listMembers(ZipFile zip, String member) throws IOException {
        Map<String, Long> names = new LinkedHashMap<>();
        try (TarReader tar = new TarReader(new GZIPInputStream(
                new java.io.BufferedInputStream(zip.getInputStream(zip.getEntry(member)), 1 << 16)))) {
            TarReader.Entry e;
            while ((e = tar.next()) != null) {
                if (e.regular) {
                    names.put(e.name, e.size);
                }
                tar.skipData(e);
            }
        }
        return names;
    }

    private static boolean isMcaPath(String name) {
        return SnapshotBlockStore.isEncoded(SnapshotLayout.normalize(name));
    }

    private static int[] regionCoords(String memberName) {
        String base = memberName.substring(memberName.lastIndexOf('/') + 1);
        if (!base.startsWith("r.") || !base.endsWith(".mca")) {
            return null;
        }
        String[] parts = base.substring(2, base.length() - 4).split("\\.");
        if (parts.length != 2) {
            return null;
        }
        try {
            return new int[]{Integer.parseInt(parts[0]), Integer.parseInt(parts[1])};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static int getInt(byte[] bytes, int base) {
        return ((bytes[base] & 0xFF) << 24)
                | ((bytes[base + 1] & 0xFF) << 16)
                | ((bytes[base + 2] & 0xFF) << 8)
                | (bytes[base + 3] & 0xFF);
    }
}
