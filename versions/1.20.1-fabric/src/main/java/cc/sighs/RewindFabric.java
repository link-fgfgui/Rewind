package cc.sighs;

import cc.sighs.rewind.common.RewindState;
import cc.sighs.rewind.common.spi.RewindPlatforms;
import cc.sighs.rewind.server.FabricRewindPlatform;
import cc.sighs.rewind.server.RewindServerConfig;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;

/**
 * Fabric 1.20.1 的入口。
 *
 * <p>装平台实现（common 侧所有需要碰 Minecraft 的能力都经过它）与读服务端配置。客户端入口在
 * {@code cc.sighs.RewindFabricClient}（{@code ClientModInitializer}），本类不引用任何
 * {@code net.minecraft.client.*} / {@code cc.sighs.rewind.client}，所以专用服务器上也能加载。
 */
public final class RewindFabric implements ModInitializer {
    public static final String MOD_ID = "rewind";

    @Override
    public void onInitialize() {
        // 必须在任何 RewindApi 调用之前装好
        RewindPlatforms.install(new FabricRewindPlatform());
        // 服务端行为的配置两边都要有，所以放在主入口而不是客户端入口里
        RewindServerConfig.register();
        // 世界关掉之后账本键（种类，维度，坐标）会和下个世界撞车：停服时清掉全部暂态。
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> RewindState.onWorldUnloaded());
        // 启动自愈（TODO2 §4.2）：开服读完关卡即检查 GC 崩溃窗口，比任何建点/回滚都早。
        ServerLifecycleEvents.SERVER_STARTED.register(server -> RewindState.onServerStarted(server));
    }
}
