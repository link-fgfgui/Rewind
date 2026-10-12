package cc.sighs.rewind.common.spi;

/**
 * 非区块 NBT 的覆盖账本：存档数据（SavedData {@code .dat}）与玩家数据共用。
 *
 * <p>键是版本节点拼的字符串（见各版本 {@code DataJournalMixins}）：
 * <ul>
 *   <li>存档数据：{@code "savedata|" + 数据文件绝对路径}（如
 *       {@code savedata|/saves/world/DIM-1/data/raids.dat}）。用绝对路径而不用
 *       “维度 + id”，是因为 26.1 的落盘布局是 {@code data/<ns>/<path>.dat}、
 *       维度目录还分大小写，用路径最不会错位。</li>
 *   <li>玩家数据：{@code "player|" + uuid 字符串}。</li>
 *   <li>level.dat 写监听：{@code "leveldat"}（只记不查，回滚走内存 WorldData）。</li>
 * </ul>
 *
 * <p>记/查/删除标记/命中计数的语义见 {@link NbtLedger}（与 {@link StorageOverlay} 对齐）。
 */
public final class DataCover extends NbtLedger<String> {
}
