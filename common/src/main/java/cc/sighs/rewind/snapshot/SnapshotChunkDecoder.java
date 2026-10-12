package cc.sighs.rewind.snapshot;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;

/**
 * 单个区块的快照扇区解码器（纯 common / 纯 JDK）。
 *
 * <p>.mca 字节来源统一走 {@link SectorSource}：快照块存储（内容寻址、稀疏读）或槽位实体
 * 文件（老格式整文件槽位），读取逻辑只写一份。
 */
public final class SnapshotChunkDecoder {
    private SnapshotChunkDecoder() {
    }

    /**
     * .mca/.mcc 字节源：快照块存储条目，或槽位里的实体文件。
     *
     * <p>不可读（条目/文件缺席）时两个读口都返回 null，调用方按缺席处理；区间越界
     * 由具体实现抛 {@link EOFException}（映射存在但短于读口 = 存储可疑，不能当缺席）。
     */
    public interface SectorSource {
        /** 读 {@code [offset, offset + length)}；源缺席返回 null。 */
        byte[] readRange(long offset, int length) throws IOException;

        /** 读整个文件；源缺席返回 null。 */
        byte[] readAll() throws IOException;

        /** 槽位实体文件源（老格式整文件槽位）；文件不存在即缺席。 */
        static SectorSource ofFile(Path file) {
            return new SectorSource() {
                private byte[] loaded;
                private boolean loadedOnce;

                private byte[] load() throws IOException {
                    if (!loadedOnce) {
                        loadedOnce = true;
                        loaded = Files.isRegularFile(file) ? Files.readAllBytes(file) : null;
                    }
                    return loaded;
                }

                @Override
                public byte[] readRange(long offset, int length) throws IOException {
                    byte[] data = load();
                    if (data == null || offset < 0 || offset + length > data.length) {
                        return null;
                    }
                    return Arrays.copyOfRange(data, (int) offset, (int) offset + length);
                }

                @Override
                public byte[] readAll() throws IOException {
                    return load();
                }
            };
        }

        /** 快照块存储源；条目缺席即缺席，区间越界由块存储抛 {@link EOFException}。 */
        static SectorSource ofBlocks(SnapshotBlockStore store, SnapshotBlocks.Entry entry) {
            return new SectorSource() {
                @Override
                public byte[] readRange(long offset, int length) throws IOException {
                    if (entry == null) {
                        return null;
                    }
                    return store.readRange(entry.hashes, entry.size, offset, length);
                }

                @Override
                public byte[] readAll() throws IOException {
                    if (entry == null) {
                        return null;
                    }
                    return store.readAll(entry.hashes, entry.size);
                }
            };
        }

        /** 内存字节源（测试与内联解码）。 */
        static SectorSource ofBytes(byte[] data) {
            return new SectorSource() {
                @Override
                public byte[] readRange(long offset, int length) throws IOException {
                    if (data == null || offset < 0 || offset + length > data.length) {
                        return null;
                    }
                    return Arrays.copyOfRange(data, (int) offset, (int) offset + length);
                }

                @Override
                public byte[] readAll() throws IOException {
                    return data == null ? null : Arrays.copyOf(data, data.length);
                }
            };
        }
    }

    public static final class Sector {
        public final int versionId;
        public final byte[] compressed;
        public final boolean external;

        Sector(int versionId, byte[] compressed, boolean external) {
            this.versionId = versionId;
            this.compressed = compressed;
            this.external = external;
        }
    }

    /** 读单个区块扇区；源不可读（文件/条目缺席、不足 8 KiB 头）返回 null。 */
    public static Sector readSector(SectorSource source, int chunkX, int chunkZ) throws IOException {
        byte[] header = source.readRange(0, AnvilFormat.HEADER_BYTES);
        if (header == null || header.length < AnvilFormat.HEADER_BYTES) {
            return null;
        }
        int index = chunkIndex(chunkX, chunkZ);
        int packed = AnvilFormat.getInt(header, index * 4);
        int sectorNumber = (packed >> 8) & 0xFFFFFF;
        int sectorCount = packed & 0xFF;
        if (packed == 0 || sectorNumber < 2 || sectorCount == 0) {
            return null;
        }
        long sectorOffset = (long) sectorNumber * AnvilFormat.SECTOR_BYTES;
        long sectorsBytes = (long) sectorCount * AnvilFormat.SECTOR_BYTES;
        byte[] head = source.readRange(sectorOffset, 5);
        if (head == null || head.length < 5) {
            throw new EOFException("sector header of (" + chunkX + "," + chunkZ
                    + ") is out of bounds at " + sectorOffset);
        }
        int i1 = AnvilFormat.getInt(head, 0);
        int b0 = head[4] & 0xFF;
        if (i1 == 0) {
            return null;
        }
        boolean external = (b0 & AnvilFormat.EXTERNAL_STREAM_FLAG) != 0;
        int versionId = b0 & ~AnvilFormat.EXTERNAL_STREAM_FLAG;
        if (external) {
            return new Sector(versionId, null, true);
        }
        int j1 = i1 - 1;
        if (j1 < 0 || (long) j1 > sectorsBytes - 5) {
            throw new EOFException("chunk (" + chunkX + "," + chunkZ + ") declares " + j1
                    + " bytes but sector holds " + (sectorsBytes - 5));
        }
        byte[] compressed = source.readRange(sectorOffset + 5, j1);
        if (compressed == null || compressed.length < j1) {
            throw new EOFException("chunk (" + chunkX + "," + chunkZ + ") declares " + j1
                    + " bytes but the sector payload is truncated");
        }
        return new Sector(versionId, compressed, false);
    }

    /** 读外溢载荷（.mcc）；源缺席抛 {@link IOException}。 */
    public static byte[] readExternalPayload(SectorSource source) throws IOException {
        byte[] payload = source.readAll();
        if (payload == null) {
            throw new IOException("external chunk file missing from source");
        }
        return payload;
    }

    public static byte[] decompress(int versionId, byte[] compressed) throws IOException {
        if (compressed == null) {
            throw new IOException("missing chunk payload");
        }
        switch (versionId) {
            case AnvilFormat.VERSION_GZIP:
                return uncompress(new GZIPInputStream(new java.io.ByteArrayInputStream(compressed)));
            case AnvilFormat.VERSION_DEFLATE:
                return uncompress(new InflaterInputStream(new java.io.ByteArrayInputStream(compressed)));
            case AnvilFormat.VERSION_NONE: {
                byte[] copy = new byte[compressed.length];
                System.arraycopy(compressed, 0, copy, 0, compressed.length);
                return copy;
            }
            case AnvilFormat.VERSION_LZ4:
                return decompressLz4(compressed);
            default:
                throw new IOException("unsupported chunk stream version: " + versionId);
        }
    }

    public static int chunkIndex(int chunkX, int chunkZ) {
        return (chunkX & 31) + (chunkZ & 31) * 32;
    }

    public static String regionFileName(int chunkX, int chunkZ) {
        return "r." + (chunkX >> 5) + "." + (chunkZ >> 5) + ".mca";
    }

    public static String mccRelative(String mcaRelative, int chunkX, int chunkZ) {
        String relative = SnapshotLayout.normalize(mcaRelative);
        int slash = relative.lastIndexOf('/');
        String dir = slash < 0 ? "" : relative.substring(0, slash);
        String name = MccName.format(chunkX, chunkZ);
        return dir.isEmpty() ? name : dir + "/" + name;
    }

    private static byte[] uncompress(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(8192);
        byte[] buffer = new byte[8192];
        try {
            int read;
            while ((read = in.read(buffer)) >= 0) {
                out.write(buffer, 0, read);
            }
        } finally {
            try {
                in.close();
            } catch (IOException ignored) {
                // ignore
            }
        }
        return out.toByteArray();
    }

    private static byte[] decompressLz4(byte[] compressed) throws IOException {
        try {
            Class<?> type = Class.forName("net.jpountz.lz4.LZ4BlockInputStream");
            java.lang.reflect.Constructor<?> init = type.getConstructor(InputStream.class);
            InputStream in = (InputStream) init.newInstance(new java.io.ByteArrayInputStream(compressed));
            return uncompress(in);
        } catch (ClassNotFoundException e) {
            throw new IOException("lz4 chunk payload but lz4 is not on the classpath", e);
        } catch (ReflectiveOperationException e) {
            throw new IOException("cannot open lz4 stream", e);
        }
    }
}
