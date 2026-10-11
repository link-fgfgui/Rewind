package cc.sighs.rewind.common.spi;

/**
 * 写拦截：把「刚被写下去的数据」回传给 common。
 *
 * <p>由各版本节点的 mixin 实现：拦到原版写入口就调这里。`tag` 是该版本自己的 NBT 对象，
 * common 只把它当作不透明句柄原样存放、原样交还（和 {@link RewindPlatform} 里 `server`
 * 的约定一样，common 里没有可用的公共 NBT 父类型）。
 *
 * @see StorageReadSource
 * @see StorageOverlay
 */
public interface StorageWriteSink {
    /**
     * 记录一次写入。
     *
     * @param kind 存储种类
     * @param dimensionId 维度标识（各版本自己的维度键字符串，由实现方保证同一世界内稳定）
     * @param chunkPos 区块坐标（原版 `ChunkPos.toLong()` 拼出来的那个 long）
     * @param tag 刚被写下去的数据（版本相关类型，不透明）；`null` 表示删除类写入，
     *     记删除标记（变更集照样包含它）
     */
    void onWritten(StorageKind kind, String dimensionId, long chunkPos, Object tag);
}
