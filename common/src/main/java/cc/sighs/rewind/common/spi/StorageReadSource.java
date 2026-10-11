package cc.sighs.rewind.common.spi;

/**
 * 读拦截：回滚期间，读请求先问这里要旧数据。
 *
 * <p>由各版本节点的 mixin 在读入口调用：拿到非 null 就直接用，不再走正常读盘；
 * 返回 null 表示这里没有覆盖，走原版读盘逻辑。
 *
 * @see StorageWriteSink
 * @see StorageOverlay
 */
public interface StorageReadSource {
    /**
     * 取一次覆盖数据。
     *
     * @param kind 存储种类
     * @param dimensionId 维度标识（与 {@link StorageWriteSink} 写入时一致）
     * @param chunkPos 区块坐标（与写入时一致）
     * @return 覆盖的数据（写入时的那个不透明句柄）；没有覆盖、或覆盖的是删除标记时返回 null
     */
    Object serve(StorageKind kind, String dimensionId, long chunkPos);
}
