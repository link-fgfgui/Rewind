package cc.sighs.rewind.client;

import com.mojang.blaze3d.platform.InputConstants;
import cc.sighs.rewind.Rewind;
import cc.sighs.rewind.api.RewindApi;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
//? if fabric {
/*import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
*///?} elif forge {
/*import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.IEventBus;
*///?} else {
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
//?}
//? if >=26.1 {
/*import net.minecraft.resources.Identifier;
*///?}

/**
 * 客户端侧的注册入口。只在物理客户端加载（Fabric 由 {@code fabric.mod.json} 的 {@code client} 入口
 * 调进来，Forge/NeoForge 由主类拿 dist 判定后才调用），这样专用服务器不会因为引用客户端类而炸掉。
 *
 * <p>事件来源按 loader 走：Fabric 用 {@code KeyBindingHelper} + {@code ClientTickEvents} +
 * {@code CommandRegistrationCallback}，帧钩子与原始按键由 {@code cc.sighs.mixin} 下的注入提供；
 * Forge 1.20.1 没有 {@code RenderFrameEvent} / {@code ClientTickEvent.Post}，用
 * {@code TickEvent.RenderTickEvent/ClientTickEvent} + {@code Phase} 代替；NeoForge 直接用
 * {@code RenderFrameEvent.Pre/Post}。热键字段、按键分发、配置注册三家共用一套。
 *
 * <p>命令注册挂在客户端入口里：{@code /rewind} 只在客户端存在，专用服务器上没有它。
 */
public final class RewindClient {
//? if >=26.1 {
    /*// 热键分类：{@code key.category.rewind.keys}。26.1 起分类从字符串变成了 Category 对象，
    // 建 KeyMapping 之前要先 registerCategory，否则按键页里这一组的标题翻译不到。
    private static final KeyMapping.Category CATEGORY =
            new KeyMapping.Category(Identifier.fromNamespaceAndPath(Rewind.MOD_ID, "keys"));
*///?} else {
    private static final String CATEGORY = "key.categories.rewind";
//?}
    private static final int SNAPSHOT_REQUEST = 1;
    private static final int RESTORE_REQUEST = 2;
    private static final int TREE_REQUEST = 3;

    private static KeyMapping snapshotKey;
    private static KeyMapping restoreKey;
    private static KeyMapping treeKey;
    /** 待执行的热键请求：0 = 没有，见 {@link #flushPendingRequest()}。 */
    private static int pendingRequest;

    private RewindClient() {
    }

    // Fabric：客户端入口初始化时调用一次。按键走 KeyBindingHelper，tick 与命令走 Fabric 回调，
    // 帧钩子与原始按键由 mixin 直接调下面的 onFramePre/onFramePost/onRawKey 进来。
//? if fabric {
    /*public static void setup() {
        // 过渡参数（所有可调项）落在 config/rewind-client.properties
        RewindClientConfig.register();
        // 把「带过渡的异步入口」接到状态机上：RewindApi.requestCheckpoint/requestRollback 从此等价于按 F7/F8
        CheckpointController.installApiBridge();
        snapshotKey = KeyBindingHelper.registerKeyBinding(
                new KeyMapping("key.rewind.snapshot", InputConstants.KEY_F7, CATEGORY));
        restoreKey = KeyBindingHelper.registerKeyBinding(
                new KeyMapping("key.rewind.restore", InputConstants.KEY_F8, CATEGORY));
        treeKey = KeyBindingHelper.registerKeyBinding(
                new KeyMapping("key.rewind.tree", InputConstants.KEY_F9, CATEGORY));
        ClientTickEvents.END_CLIENT_TICK.register(RewindClient::onClientTick);
        CommandRegistrationCallback.EVENT.register(
                (dispatcher, registryAccess, environment) -> RewindCommands.register(dispatcher));
        Rewind.LOGGER.info("Rewind: registered key mappings (F7=checkpoint, F8=restore, F9=time tree)");
    }
*///?}

    // Forge：按键走模组事件总线的 RegisterKeyMappingsEvent，其余全挂游戏总线，显式写优先级。
//? if forge {
    /*public static void setup(IEventBus modBus) {
        // 过渡参数（所有可调项）落在 run/config/rewind-client.toml
        RewindClientConfig.register(modBus);
        // 把「带过渡的异步入口」接到状态机上：RewindApi.requestCheckpoint/requestRollback 从此等价于按 F7/F8
        CheckpointController.installApiBridge();
        modBus.addListener(RewindClient::onRegisterKeyMappings);
        MinecraftForge.EVENT_BUS.addListener(EventPriority.NORMAL, false,
                TickEvent.ClientTickEvent.class, RewindClient::onClientTick);
        MinecraftForge.EVENT_BUS.addListener(EventPriority.NORMAL, false,
                RegisterCommandsEvent.class, RewindCommands::register);
        // 存档点的封面：建点的人（服务端线程）留一条待办，这里在下一帧把画面抓下来。
        // 帧开始这一下要把当前界面与 HUD 临时摘掉，所以必须排在这一帧渲染之前——RenderTickEvent 的
        // START 正是 gameRenderer.render 之前那一点（语义对应当代的 RenderFrameEvent.Pre）。
        MinecraftForge.EVENT_BUS.addListener(EventPriority.NORMAL, false,
                TickEvent.RenderTickEvent.class, RewindClient::onRenderTickStart);
        // 抓帧要在 HIGHEST：必须抢在下面的过渡后处理之前读那一帧，否则封面会带上存档过渡的饱和度
        MinecraftForge.EVENT_BUS.addListener(EventPriority.HIGHEST, false,
                TickEvent.RenderTickEvent.class, RewindClient::onRenderTickEndCapture);
        // 过渡后处理：每帧结束时重采样整幅画面（存档饱和度 / 回溯高斯模糊）
        MinecraftForge.EVENT_BUS.addListener(EventPriority.NORMAL, false,
                TickEvent.RenderTickEvent.class, RewindClient::onRenderTickEndTransition);
        // 过渡期间拦掉原版加载屏，保证全程不出现任何界面
        MinecraftForge.EVENT_BUS.addListener(EventPriority.NORMAL, false,
                ScreenEvent.Opening.class, RewindScreens::onScreenOpening);
        // F7 / F8 / F9 走原始按键输入，而不是 KeyMapping 的点击计数：原版只在没有界面时才累计
        // 点击（KeyboardHandler 里 flag4 = screen == null），界面开着就永远收不到。
        MinecraftForge.EVENT_BUS.addListener(EventPriority.NORMAL, false,
                InputEvent.Key.class, RewindClient::onKeyInput);
    }
*///?}

    // NeoForge：与 Forge 同一套订阅顺序，只是事件类与总线换成 NeoForge 的，监听写法也更短。
//? if neoforge {
    public static void setup(IEventBus modBus) {
        // 过渡参数（所有可调项）落在 run/config/rewind-client.toml
        ModList.get().getModContainerById(Rewind.MOD_ID).ifPresent(container -> RewindClientConfig.register(container, modBus));
        // 把「带过渡的异步入口」接到状态机上：RewindApi.requestCheckpoint/requestRollback 从此等价于按 F7/F8
        CheckpointController.installApiBridge();
        modBus.addListener(RegisterKeyMappingsEvent.class, RewindClient::onRegisterKeyMappings);
        NeoForge.EVENT_BUS.addListener(ClientTickEvent.Post.class, RewindClient::onClientTick);
        NeoForge.EVENT_BUS.addListener(RegisterCommandsEvent.class, RewindCommands::register);
        // 存档点的封面：建点的人（服务端线程）留一条待办，这里在下一帧把画面抓下来
        NeoForge.EVENT_BUS.addListener(RenderFrameEvent.Pre.class,
                event -> CoverCapture.onFramePre(Minecraft.getInstance()));
        // 抓帧要在 HIGHEST：必须抢在过渡后处理之前读那一帧，否则封面会带上存档过渡的饱和度
        NeoForge.EVENT_BUS.addListener(EventPriority.HIGHEST, RenderFrameEvent.Post.class,
                event -> CoverCapture.onFramePost(Minecraft.getInstance()));
        // 过渡后处理：每帧结束时重采样整幅画面（存档饱和度 / 回溯高斯模糊）
        NeoForge.EVENT_BUS.addListener(RenderFrameEvent.Post.class, RewindClient::onRenderFramePost);
        // 过渡期间拦掉原版加载屏，保证全程不出现任何界面
        NeoForge.EVENT_BUS.addListener(ScreenEvent.Opening.class, RewindScreens::onScreenOpening);
        // F7 / F8 / F9 走原始按键输入，而不是 KeyMapping 的点击计数：原版只在没有界面时才累计
        // 点击（KeyboardHandler 里 flag4 = screen == null），界面开着就永远收不到。
        NeoForge.EVENT_BUS.addListener(InputEvent.Key.class, RewindClient::onKeyInput);
    }
//?}

    public static KeyMapping snapshotKey() {
        return snapshotKey;
    }

    public static KeyMapping restoreKey() {
        return restoreKey;
    }

    public static KeyMapping treeKey() {
        return treeKey;
    }

    // Fabric 的帧钩子（由 ClientFrameMixins 调进来）：整帧绘制前摘界面与 HUD，画完未上屏时抓帧。
//? if fabric {
    /*public static void onFramePre(Minecraft minecraft) {
        CoverCapture.onFramePre(minecraft);
    }

    // 两句的先后顺序是硬约定——封面必须抢在过渡后处理之前读那一帧，否则封面会带上存档过渡的饱和度。
    public static void onFramePost(Minecraft minecraft) {
        CoverCapture.onFramePost(minecraft);
        RewindTransitionRenderer.onRenderFramePost(minecraft);
    }
*///?}

    // Forge 用 RenderTickEvent 的相位区分帧开始/结束（语义对应当代的 RenderFrameEvent.Pre/Post）。
//? if forge {
    /*private static void onRenderTickStart(TickEvent.RenderTickEvent event) {
        if (event.phase != TickEvent.Phase.START) {
            return;
        }
        CoverCapture.onFramePre(Minecraft.getInstance());
    }

    private static void onRenderTickEndCapture(TickEvent.RenderTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        CoverCapture.onFramePost(Minecraft.getInstance());
    }

    private static void onRenderTickEndTransition(TickEvent.RenderTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        RewindTransitionRenderer.onRenderFramePost(Minecraft.getInstance());
    }
*///?}

    // NeoForge：过渡后处理挂在 RenderFrameEvent.Post 上。
//? if neoforge {
    private static void onRenderFramePost(RenderFrameEvent.Post event) {
        RewindTransitionRenderer.onRenderFramePost(Minecraft.getInstance());
    }
//?}

    // Forge/NeoForge 共用：按键分类事件（26.1 要先 registerCategory，之前版本没有这个调用）。
//? if !fabric {
    private static void onRegisterKeyMappings(RegisterKeyMappingsEvent event) {
        //? if >=26.1 {
        /*// 分类要在建 KeyMapping 之前进事件，否则按键页里这一组的标题翻译不到
        event.registerCategory(CATEGORY);
        *///?}
        snapshotKey = new KeyMapping("key.rewind.snapshot", InputConstants.KEY_F7, CATEGORY);
        restoreKey = new KeyMapping("key.rewind.restore", InputConstants.KEY_F8, CATEGORY);
        treeKey = new KeyMapping("key.rewind.tree", InputConstants.KEY_F9, CATEGORY);
        event.register(snapshotKey);
        event.register(restoreKey);
        event.register(treeKey);
        Rewind.LOGGER.info("Rewind: registered key mappings (F7=checkpoint, F8=restore, F9=time tree)");
    }
//?}

    // Fabric：tick 回调直接给实例；其余走事件。
//? if fabric {
    /*private static void onClientTick(Minecraft minecraft) {
        CheckpointController.tick(minecraft);
        flushPendingRequest();
    }
*///?}

    // Forge：ClientTickEvent 在游戏总线上，用 Phase.END 过滤。
//? if forge {
    /*private static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        CheckpointController.tick(minecraft);
        flushPendingRequest();
    }
*///?}

    // NeoForge：ClientTickEvent.Post 本身就是 END 相位。
//? if neoforge {
    private static void onClientTick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        CheckpointController.tick(minecraft);
        flushPendingRequest();
    }
//?}

    // Fabric：原始按键由 ClientInputMixins 调进来（无论有没有界面都会走到这里）。
//? if fabric {
    /*public static void onRawKey(long window, int key, int scanCode, int action) {
        if (action != InputConstants.PRESS) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft != null && minecraft.getWindow() != null
                && window != minecraft.getWindow().getWindow()) {
            // 不是主窗口的按键（例如别的 GLFW 窗口）不认
            return;
        }
        if (snapshotKey != null && snapshotKey.matches(key, scanCode)) {
            pendingRequest = SNAPSHOT_REQUEST;
        } else if (restoreKey != null && restoreKey.matches(key, scanCode)) {
            pendingRequest = RESTORE_REQUEST;
        } else if (treeKey != null && treeKey.matches(key, scanCode)) {
            pendingRequest = TREE_REQUEST;
        }
    }
*///?}

    // Forge/NeoForge：原始按键走 InputEvent.Key（原版只在 screen == null 时给 KeyMapping 累计点击，
    // 所以 F7/F8/F9 在 GUI 里也能生效）。用 KeyMapping#matches 判定，玩家改绑依然有效。
    // 26.1 起按键信息包进了 KeyEvent，原来 getKey()/getScanCode() 的判定换成 matches(getKeyEvent())。
//? if !fabric {
    private static void onKeyInput(InputEvent.Key event) {
        if (event.getAction() != InputConstants.PRESS) {
            return;
        }
        //? if >=26.1 {
        /*if (snapshotKey != null && snapshotKey.matches(event.getKeyEvent())) {
            pendingRequest = SNAPSHOT_REQUEST;
        } else if (restoreKey != null && restoreKey.matches(event.getKeyEvent())) {
            pendingRequest = RESTORE_REQUEST;
        } else if (treeKey != null && treeKey.matches(event.getKeyEvent())) {
            pendingRequest = TREE_REQUEST;
        }
        *///?} else {
        if (snapshotKey != null && snapshotKey.matches(event.getKey(), event.getScanCode())) {
            pendingRequest = SNAPSHOT_REQUEST;
        } else if (restoreKey != null && restoreKey.matches(event.getKey(), event.getScanCode())) {
            pendingRequest = RESTORE_REQUEST;
        } else if (treeKey != null && treeKey.matches(event.getKey(), event.getScanCode())) {
            pendingRequest = TREE_REQUEST;
        }
        //?}
    }
//?}

    private static void flushPendingRequest() {
        if (pendingRequest == 0 || RewindTransition.isActive() || RewindApi.isBusy()) {
            return;
        }
        int request = pendingRequest;
        pendingRequest = 0;
        if (request == SNAPSHOT_REQUEST) {
            RewindApi.requestCheckpoint("key");
        } else if (request == RESTORE_REQUEST) {
            RewindApi.requestRollback("key");
        } else {
            RewindTreeScreen.open();
        }
    }
}
