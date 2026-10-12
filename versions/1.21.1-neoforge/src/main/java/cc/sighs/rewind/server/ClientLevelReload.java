package cc.sighs.rewind.server;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.network.protocol.game.ClientboundPlayerAbilitiesPacket;
import net.minecraft.network.protocol.game.ClientboundRespawnPacket;
import net.minecraft.network.protocol.game.ClientboundSetCarriedItemPacket;
import net.minecraft.network.protocol.game.ClientboundUpdateAttributesPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import cc.sighs.mixin.RollbackAccessMixins.ChunkMapAccess;
import cc.sighs.rewind.Rewind;

/**
 * 多人在线回滚成功后的**原生客户端重载**（TODO4 阶段 3）：用原版那套
 * {@code ClientboundRespawnPacket} + {@code sendLevelInfo} + 区块重跟踪把全体在线客户端的
 * 旧世界丢掉、重新下载回滚后的地形。
 *
 * <p><b>flags = 0</b>（{@code ClientboundRespawnPacket} 的第二个参数）：死亡重生用的就是 0，
 * 语义是「客户端丢掉全部旧状态」——{@code KEEP_ENTITY_DATA} / {@code KEEP_ATTRIBUTE_MODIFIERS}
 * 恰恰相反，那是让客户端**保留**回溯前的同步数据。回滚要的是丢弃，所以属性 / 实体数据全部由服务端
 * 重推（{@link #reload} 里那几行）。
 *
 * <p><b>同维度没有原版先例</b>：1.21.1 全树只有「死亡重生」（{@code PlayerList.respawn}）与
 * 「跨维度」（{@code ServerPlayer.changeDimension}）两处发 Respawn，同维度换世界是这套流程手搓的。
 * 客户端收到之后会：把 LocalPlayer 换成一个新的（同维度时 {@code ClientLevel} 保持不变）、挂上原生的
 * {@code ReceivingLevelScreen}（「正在下载地形」）、等 {@code LEVEL_CHUNKS_LOAD_START} 与玩家脚下
 * 区块到手再关屏。
 *
 * <p><b>区块跟踪必须重置</b>：服务端 {@code ChunkMap} 记忆着「已经发给这个玩家哪些区块」，
 * 客户端换了一个 LocalPlayer 之后那份记忆全是错的——不重置就不会有任何区块被重发，客户端会一直
 * 停在加载屏上。{@code setChunkTrackingView(EMPTY)} + {@code chunkMap.move(player)} 等价于原版
 * 跨维度时 {@code addDuringTeleport} 里走过的 {@code updateChunkTracking} 路径，但不会重复注册
 * ticket（玩家已经在 ticket 系统里了）。
 */
public final class ClientLevelReload {
    private ClientLevelReload() {
    }

    /** 给所有在线玩家发一遍原生重载序列。 */
    public static void reloadAll(MinecraftServer server) {
        List<ServerPlayer> players = new ArrayList<>(server.getPlayerList().getPlayers());
        if (players.isEmpty()) {
            return;
        }
        Rewind.LOGGER.info("Rewind: natively reloading {} client(s) after the rollback", players.size());
        for (ServerPlayer player : players) {
            try {
                reload(server, player);
            } catch (Throwable t) {
                Rewind.LOGGER.error("Rewind: failed to reload the client of {}", player.getScoreboardName(), t);
            }
        }
    }

    /** 一个玩家的重载序列；顺序与原版 {@code ServerPlayer.changeDimension} 对齐。 */
    private static void reload(MinecraftServer server, ServerPlayer player) {
        ServerLevel level = player.serverLevel();
        // 1. 换客户端世界：flags = 0，客户端丢掉全部旧状态后等新地形
        player.connection.send(new ClientboundRespawnPacket(player.createCommonSpawnInfo(level), (byte) 0));
        // 2. 能力 / 权限：flags=0 会把客户端的属性与能力重置成默认值
        player.connection.send(new ClientboundPlayerAbilitiesPacket(player.getAbilities()));
        server.getPlayerList().sendPlayerPermissionLevel(player);
        // 3. 位置：排在区块包之前（理由同原地回滚里的那一段：位置包在前，ack 只要一个客户端 tick）
        player.connection.teleport(player.getX(), player.getY(), player.getZ(), player.getYRot(), player.getXRot());
        player.connection.resetPosition();
        // 4. 世界信息全套：边界、时间、出生点、天气、LEVEL_CHUNKS_LOAD_START、等级附件
        server.getPlayerList().sendLevelInfo(player, level);
        // 5. 背包 / 快捷栏 / 效果 / 属性：客户端那份是全新的，全部重推
        server.getPlayerList().sendAllPlayerInfo(player);
        server.getPlayerList().sendActivePlayerEffects(player);
        player.connection.send(new ClientboundSetCarriedItemPacket(player.getInventory().selected));
        player.connection.send(new ClientboundUpdateAttributesPacket(player.getId(),
                player.getAttributes().getSyncableAttributes()));
        // 6. 重置区块跟踪：先按原版「移除玩家」的语义清掉登记，再按「加入玩家」重来一遍——
        //    PlayerMap 与 PLAYER ticket 都重新落在当前位置上，视距内的区块全部重新标成待发。
        //    等价于原版跨维度时 addDuringTeleport 走的那条 updatePlayerStatus(player, true)，
        //    但不会留下「玩家已移动、旧位置的 ticket 还在」的泄漏（我们复用的是同一个玩家对象）。
        ChunkMapAccess access = (ChunkMapAccess) (Object) level.getChunkSource().chunkMap;
        access.rewind$updatePlayerStatus(player, false);
        access.rewind$updatePlayerStatus(player, true);
    }
}
