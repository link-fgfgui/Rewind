package cc.sighs.rewind.common.spi;

/**
 * 存档里按区块寻址的三类存储。
 *
 * <p>对应磁盘上的三套 {@code .mca}：`region/`（方块）、`entities/`（实体）、`poi/`（兴趣点）。
 * 三者在原版里都汇到 `IOWorker` 这一单点（写 `store`、读 `loadAsync`），所以拦截层只用一种 key
 * 形状就能盖住它们：`StorageKind` 区分种类，维度与区块坐标定位条目。
 */
public enum StorageKind {
    /** 方块数据（`region/*.mca`）。 */
    CHUNK,
    /** 实体数据（`entities/*.mca`）。 */
    ENTITY,
    /** 兴趣点数据（`poi/*.mca`）。 */
    POI
}
