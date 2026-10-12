package cc.sighs;

import cc.sighs.rewind.client.RewindClient;
import cc.sighs.rewind.api.RewindApi;
import cc.sighs.rewind.common.RewindState;
import cc.sighs.rewind.common.spi.RewindPlatforms;
import cc.sighs.rewind.server.NeoForgeRewindPlatform;
import cc.sighs.rewind.server.RewindCommands;
import cc.sighs.rewind.server.RewindServerConfig;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

@Mod(RewindNeoForge121.MOD_ID)
public final class RewindNeoForge121 {
    public static final String MOD_ID = "rewind";

    public RewindNeoForge121(IEventBus modBus) {
        // common 侧所有需要碰 Minecraft 的能力都通过这个平台实现接入；必须在任何 RewindApi 调用之前装好。
        RewindPlatforms.install(new NeoForgeRewindPlatform());
        // 服务端行为的配置两边都要有（COMMON），所以放在 dist 判定之外
        ModList.get().getModContainerById(MOD_ID)
                .ifPresent(container -> RewindServerConfig.register(container, modBus));
        // 客户端专属注册放在这里做 dist 判定：专用服务器上 RewindClient 不会被加载，
        // 也就不会去解析 net.minecraft.client.* / neoforge.client.event.*。
        if (FMLEnvironment.dist == Dist.CLIENT) {
            RewindClient.setup(modBus);
        }
        // 世界关掉之后账本键（种类，维度，坐标）会和下个世界撞车：停服时清掉全部暂态。
        // 新世界从干净账本起步；槽位块映射在盘上，首建点照常走增量（journal 为空即全沿用 + 松散镜像）。
        // /rewind 命令注册在**服务端入口**：专用服务器上也要有这些命令（TODO4 阶段 0）。
        NeoForge.EVENT_BUS.addListener(RegisterCommandsEvent.class, RewindCommands::register);
        // 多人在线回滚（跨 tick 切片）的推进点：每 tick 推一片，连接与心跳照常跑。
        NeoForge.EVENT_BUS.addListener(ServerTickEvent.Post.class,
                event -> RewindApi.tickMultiplayerRollback(event.getServer()));
        NeoForge.EVENT_BUS.addListener(ServerStoppedEvent.class, event -> RewindState.onWorldUnloaded());
        // 启动自愈（TODO2 §4.2）：开服读完关卡即检查 GC 崩溃窗口，比任何建点/回滚都早。
        NeoForge.EVENT_BUS.addListener(ServerStartedEvent.class,
                event -> RewindState.onServerStarted(event.getServer()));
    }
}
