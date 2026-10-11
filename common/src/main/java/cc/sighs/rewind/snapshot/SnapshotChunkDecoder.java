package cc.sighs.rewind.snapshot;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;

/**
 * 单个区块的快照扇区解码器（纯 common / 纯 JDK）。
 */
public final class SnapshotChunkDecoder {
    public static final int SECTOR_BYTES = 4096;
    public static final int HEADER_BYTES = 8192;
    public static final int EXTERNAL_STREAM_FLAG = 128;
    public static final int EXTERNAL_CHUNK_THRESHOLD = 256;
    public static final int VERSION_GZIP = 1;
    public static final int VERSION_DEFLATE = 2;
    public static final int VERSION_NONE = 3;
    public static final int VERSION_LZ4 = 4;

    private SnapshotChunkDecoder() {
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

    public static Sector readSector(SnapshotBlockStore store, SnapshotBlocks blocks,
            String mcaRelative, int chunkX, int chunkZ) throws IOException {
        String relative = SnapshotLayout.normalize(mcaRelative);
        SnapshotBlocks.Entry entry = blocks == null ? null : blocks.get(relative);
        if (entry == null) {
            return null;
        }
        byte[] header = store.readRange(entry.hashes, entry.size, 0, HEADER_BYTES);
        int index = chunkIndex(chunkX, chunkZ);
        int packed = getInt(header, index * 4);
        int sectorNumber = (packed >> 8) & 0xFFFFFF;
        int sectorCount = packed & 0xFF;
        if (packed == 0 || sectorNumber < 2 || sectorCount == 0) {
            return null;
        }
        long sectorOffset = (long) sectorNumber * SECTOR_BYTES;
        long sectorsBytes = (long) sectorCount * SECTOR_BYTES;
        if (sectorOffset + 5 > entry.size) {
            throw new EOFException("sector header of (" + chunkX + "," + chunkZ + ") in "
                    + relative + " is out of bounds");
        }
        byte[] head = store.readRange(entry.hashes, entry.size, sectorOffset, 5);
        int i1 = getInt(head, 0);
        int b0 = head[4] & 0xFF;
        if (i1 == 0) {
            return null;
        }
        boolean external = (b0 & EXTERNAL_STREAM_FLAG) != 0;
        int versionId = b0 & ~EXTERNAL_STREAM_FLAG;
        if (external) {
            return new Sector(versionId, null, true);
        }
        int j1 = i1 - 1;
        if (j1 < 0 || (long) j1 > sectorsBytes - 5) {
            throw new EOFException("chunk (" + chunkX + "," + chunkZ + ") in " + relative
                    + " declares " + j1 + " bytes but sector holds " + (sectorsBytes - 5));
        }
        byte[] compressed = store.readRange(entry.hashes, entry.size, sectorOffset + 5, j1);
        return new Sector(versionId, compressed, false);
    }

    public static Sector readSectorFromFile(Path slotMcaFile, int chunkX, int chunkZ) throws IOException {
        if (slotMcaFile == null || !Files.isRegularFile(slotMcaFile)) {
            return null;
        }
        byte[] file = Files.readAllBytes(slotMcaFile);
        return readSectorFromBytes(file, chunkX, chunkZ);
    }

    public static Sector readSectorFromBytes(byte[] file, int chunkX, int chunkZ) throws IOException {
        if (file == null || file.length < HEADER_BYTES) {
            return null;
        }
        int index = chunkIndex(chunkX, chunkZ);
        int packed = getInt(file, index * 4);
        int sectorNumber = (packed >> 8) & 0xFFFFFF;
        int sectorCount = packed & 0xFF;
        if (packed == 0 || sectorNumber < 2 || sectorCount == 0) {
            return null;
        }
        long sectorOffset = (long) sectorNumber * SECTOR_BYTES;
        if (sectorOffset + 5 > file.length) {
            throw new EOFException("sector header out of bounds");
        }
        int base = (int) sectorOffset;
        int i1 = getInt(file, base);
        int b0 = file[base + 4] & 0xFF;
        if (i1 == 0) {
            return null;
        }
        boolean external = (b0 & EXTERNAL_STREAM_FLAG) != 0;
        int versionId = b0 & ~EXTERNAL_STREAM_FLAG;
        if (external) {
            return new Sector(versionId, null, true);
        }
        int j1 = i1 - 1;
        long sectorsBytes = (long) sectorCount * SECTOR_BYTES;
        if (j1 < 0 || (long) j1 > sectorsBytes - 5 || (long) base + 5 + j1 > file.length) {
            throw new EOFException("chunk declares " + j1 + " bytes but sector holds " + (sectorsBytes - 5));
        }
        byte[] compressed = new byte[j1];
        System.arraycopy(file, base + 5, compressed, 0, j1);
        return new Sector(versionId, compressed, false);
    }

    public static byte[] readExternalPayload(SnapshotBlockStore store, SnapshotBlocks blocks,
            String mccRelative) throws IOException {
        String relative = SnapshotLayout.normalize(mccRelative);
        SnapshotBlocks.Entry entry = blocks == null ? null : blocks.get(relative);
        if (entry == null) {
            throw new IOException("external chunk file missing from snapshot: " + relative);
        }
        return store.readAll(entry.hashes, entry.size);
    }

    public static byte[] readExternalPayloadFromFile(Path slotMccFile) throws IOException {
        if (slotMccFile == null || !Files.isRegularFile(slotMccFile)) {
            throw new IOException("external chunk file missing: " + slotMccFile);
        }
        return Files.readAllBytes(slotMccFile);
    }

    public static byte[] decompress(int versionId, byte[] compressed) throws IOException {
        if (compressed == null) {
            throw new IOException("missing chunk payload");
        }
        switch (versionId) {
            case VERSION_GZIP:
                return uncompress(new GZIPInputStream(new java.io.ByteArrayInputStream(compressed)));
            case VERSION_DEFLATE:
                return uncompress(new InflaterInputStream(new java.io.ByteArrayInputStream(compressed)));
            case VERSION_NONE: {
                byte[] copy = new byte[compressed.length];
                System.arraycopy(compressed, 0, copy, 0, compressed.length);
                return copy;
            }
            case VERSION_LZ4:
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
        String name = "c." + chunkX + "." + chunkZ + ".mcc";
        return dir.isEmpty() ? name : dir + "/" + name;
    }

    public static boolean isExternalVersion(int versionByte) {
        return (versionByte & EXTERNAL_STREAM_FLAG) != 0;
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

    private static int getInt(byte[] bytes, int base) {
        return ((bytes[base] & 0xFF) << 24)
                | ((bytes[base + 1] & 0xFF) << 16)
                | ((bytes[base + 2] & 0xFF) << 8)
                | (bytes[base + 3] & 0xFF);
    }
}
