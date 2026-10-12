package cc.sighs.mixin;

import cc.sighs.rewind.common.RewindState;
import io.netty.channel.ChannelHandlerContext;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
//? if <1.21 {
/*import net.minecraft.network.protocol.game.ServerboundAcceptTeleportationPacket;
import net.minecraft.network.protocol.game.ServerboundKeepAlivePacket;
*///?} else {
import net.minecraft.network.protocol.common.ServerboundKeepAlivePacket;
import net.minecraft.network.protocol.game.ServerboundAcceptTeleportationPacket;
//?}
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 多人在线回滚期（{@link RewindState#isMultiplayerRollbacking()}）的两道屏障：
 * 世界模拟冻住、玩家操作包丢掉。四版本注入目标一致，共用一份（唯一的小差异是 1.20.1 的
 * {@code ServerboundKeepAlivePacket} 还在 {@code game} 包里，用 Stonecutter 的版本分支消化）。
 *
 * <p><b>为什么冻世界</b>：切片回滚跨 tick 跑，其间服务端 tick 循环照常转。世界刻要是继续跑，
 * 实体 / 方块刻就会在「已经回滚过」与「还没回滚」的区块之间改状态，最后被直写落盘——回滚不干净。
 * 冻法是在 {@code ServerLevel.tick} 的入口直接取消：它一跳过，方块刻 / 流体刻 / 实体刻 / 村庄
 * 袭击 / 区块源 tick 全停；而 {@code MinecraftServer.tickChildren} 里排在它后面的
 * {@code ServerConnection.tick()}（心跳应答）与 {@code PlayerList.tick()}（心跳发送）不受影响，
 * 所以连接不会被 30 秒的 keep-alive 超时掐掉。
 *
 * <p><b>为什么要丢包</b>：世界冻着，但玩家手里还能按键。移动 / 交互 / 放置这些包如果照样处理，
 * 会直接改到正在被回滚的内存状态（有些包的处理不经过世界刻）。所以除心跳应答与传送确认
 * （前者断连、后者会卡住服务端的 {@code awaitingPositionFromClient}）之外，PLAY 阶段的玩家包在
 * {@code Connection.channelRead0} 这一层统一丢掉——那里是包进服务端线程之前唯一的公共入口，
 * 而且只认 {@link ServerGamePacketListenerImpl}（登录 / 配置阶段握手不受影响，回滚窗口里新玩家
 * 仍然能连进来）。
 *
 * <p>非多人回滚（单人过渡路径）完全不经过这里：那时 {@code isMultiplayerRollbacking()} 恒 false。
 */
public final class MultiplayerRollbackMixins {
    private MultiplayerRollbackMixins() {
    }

    /** 回滚期冻住世界刻（连接与心跳在别处，照常跑）。 */
    @Mixin(ServerLevel.class)
    public abstract static class LevelFreeze {
        @Inject(method = "tick(Ljava/util/function/BooleanSupplier;)V", at = @At("HEAD"), cancellable = true)
        private void rewind$freezeDuringMultiplayerRollback(CallbackInfo ci) {
            if (RewindState.isMultiplayerRollbacking()) {
                ci.cancel();
            }
        }
    }

    /** 回滚期丢掉 PLAY 阶段的玩家操作包；心跳应答与传送确认放行。 */
    @Mixin(Connection.class)
    public abstract static class GameplayPacketDrop {
        @Inject(
                method = "channelRead0(Lio/netty/channel/ChannelHandlerContext;Lnet/minecraft/network/protocol/Packet;)V",
                at = @At("HEAD"),
                cancellable = true)
        private void rewind$dropGameplayPackets(ChannelHandlerContext context, Packet<?> packet,
                CallbackInfo ci) {
            if (!RewindState.isMultiplayerRollbacking()) {
                return;
            }
            PacketListener listener = ((Connection) (Object) this).getPacketListener();
            if (!(listener instanceof ServerGamePacketListenerImpl)) {
                // 还没进世界（登录 / 配置阶段）：不挡，回滚窗口里新玩家照样能连进来
                return;
            }
            if (packet instanceof ServerboundKeepAlivePacket
                    || packet instanceof ServerboundAcceptTeleportationPacket) {
                return;
            }
            ci.cancel();
        }
    }
}
