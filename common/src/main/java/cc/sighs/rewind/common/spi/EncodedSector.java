package cc.sighs.rewind.common.spi;

/**
 * 版本节点把监听到的 NBT 压缩成的扇区负载（common 组装 {@code .mca} 用）。
 *
 * <p>由各版本平台实现编码（NBT 序列化 + 压缩是版本相关的），common 只管组装：
 * 按负载大小决定直存还是外溢（{@code 5 + compressed.length} 换算扇区数，
 * {@code >= 256} 扇区走 {@code .mcc}，见快照解码器的阈值常量）。
 * 外溢时 {@code compressed} 同样给出（调用方另存为 {@code .mcc} 条目），
 * 组装器在 {@code .mca} 里只放 stub。
 */
public final class EncodedSector {
    /** 版本 ID（GZIP=1、DEFLATE=2、NONE=3、LZ4=4），不带 {@code |128}。 */
    public final int versionId;
    /** 压缩负载（j1 字节）。 */
    public final byte[] compressed;

    public EncodedSector(int versionId, byte[] compressed) {
        if (compressed == null) {
            throw new NullPointerException("compressed");
        }
        this.versionId = versionId;
        this.compressed = compressed;
    }
}
