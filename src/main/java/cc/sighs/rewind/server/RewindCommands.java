package cc.sighs.rewind.server;

import java.nio.file.Path;
import java.util.List;
import cc.sighs.rewind.api.RewindApi;
import cc.sighs.rewind.api.RewindResult;
import cc.sighs.rewind.common.RewindClientHook;
import cc.sighs.rewind.common.spi.RewindPlatform;
import cc.sighs.rewind.common.spi.RewindPlatforms;
import cc.sighs.rewind.server.RewindServerConfig;
import cc.sighs.rewind.snapshot.SnapshotInventory;
import cc.sighs.rewind.snapshot.SnapshotLayout;
import cc.sighs.rewind.snapshot.SnapshotMeta;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
//? if forge {
/*import net.minecraftforge.event.RegisterCommandsEvent;
*///?}
//? if neoforge {
import net.neoforged.neoforge.event.RegisterCommandsEvent;
//?}
//? if >=26.1 {
/*import net.minecraft.server.permissions.Permissions;
*///?}

/**
 * {@code /rewind} 命令：模组的主要功能都有一条命令（没有热键的场景、想写进 .mcfunction 的场合、
 * 以及**专用服务器**上唯一的入口）。
 *
 * <pre>
 * /rewind status                列出所有槽位、时间线的头与上一次操作
 * /rewind ui                    打开时间树（= 按 F9；只有客户端有）
 * /rewind snapshot [&lt;槽位&gt;]      建立 / 覆盖存档点（= 按 F7；不给槽位就是 quick）
 * /rewind restore [&lt;槽位&gt;]       回溯到存档点（= 按 F8；不给槽位就是 quick）
 * /rewind info &lt;槽位&gt;           看一个槽位的详情
 * /rewind delete &lt;槽位&gt;         删掉一个槽位
 * /rewind rename &lt;槽位&gt; &lt;名字&gt;   给槽位改名
 * /rewind autosave on|off       跟着原版自动保存建点
 * </pre>
 *
 * <p><b>权限门限</b>：OP 等级 2（{@code < 26.1} 是 {@code hasPermission(2)}，{@code >= 26.1} 是
 * {@code Permissions.COMMANDS_GAMEMASTER}——那边的 {@code hasPermission(int)} 已经没了）。
 * 控制台 source 天然拥有全部权限，不需要特判；单人存档的主机自己就有等级 4。
 *
 * <p><b>执行路径按 source 分流</b>（TODO4 §1.2）：单人（集成服务器、未开局域网）走 F7 / F8 那条
 * 带过渡的客户端管线（{@link RewindApi#requestCheckpoint} / {@link RewindApi#requestRollback}）；
 * **专用服务器与开局域网的集成服务器**走服务端路径——建点用
 * {@link RewindApi#createCheckpoint} 同步跑（命令本来就在服务端线程上），回滚用
 * {@link RewindApi#beginMultiplayerRollback} 起一个跨 tick 的切片作业（它不阻塞服务端线程，
 * 连接与心跳照常跑，结束时用原版 Respawn 流程重载全体在线客户端）。
 *
 * <p>注册入口按 loader 走：Fabric 是回调里直接拿 dispatcher，Forge / NeoForge 是
 * {@code RegisterCommandsEvent}（包名各异，方法都是 {@code getDispatcher()}）。
 * **注册发生在服务端入口（模组主类）而不是客户端入口**，这样专用服务器上也有这些命令；
 * 命令本体四版本一致，且不引用任何客户端类（{@code ui} 通过
 * {@link RewindClientHook} 这个中立小接口转交，专用服务器上它不存在，命令会如实报告）——
 * 放在 {@code server} 包而不是 {@code client} 包，正是为了提醒：它必须在专用服务器上也能加载。
 */
public final class RewindCommands {
    private RewindCommands() {
    }

    //? if fabric {
    /*public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
    *///?} else {
    public static void register(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();
    //?}
        dispatcher.register(Commands.literal("rewind")
                // 加载期（数据包函数解析时，Brigadier 的 canUse 预检）拿到的是 getServer() 为 null 的 source，
                // 这里必须放行，否则任何引用 /rewind 的 .mcfunction 都会 NPE 加载失败
                .requires(source -> source.getServer() == null
                        //? if >=26.1 {
                        /*// 26.1 把权限等级换成了 PermissionSet（原来的 hasPermission(int) 没了）
                        || source.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER))
                        *///?} else {
                        || source.hasPermission(2))
                        //?}
                .then(Commands.literal("status").executes(context -> status(context.getSource())))
                .then(Commands.literal("ui").executes(context -> ui(context.getSource())))
                .then(Commands.literal("snapshot")
                        .executes(context -> snapshot(context.getSource(), RewindApi.DEFAULT_SLOT))
                        .then(slotArgument().executes(context -> snapshot(context.getSource(), slot(context)))))
                .then(Commands.literal("restore")
                        .executes(context -> restore(context.getSource(), RewindApi.DEFAULT_SLOT))
                        .then(slotArgument().executes(context -> restore(context.getSource(), slot(context)))))
                .then(Commands.literal("info")
                        .then(slotArgument().executes(context -> info(context.getSource(), slot(context)))))
                .then(Commands.literal("delete")
                        .then(slotArgument().executes(context -> delete(context.getSource(), slot(context)))))
                .then(Commands.literal("rename")
                        .then(slotArgument().then(
                                Commands.argument("name", StringArgumentType.greedyString())
                                        .executes(context -> rename(context.getSource(), slot(context),
                                                StringArgumentType.getString(context, "name"))))))
                .then(Commands.literal("autosave")
                        .then(Commands.literal("on").executes(context -> autosave(context.getSource(), true)))
                        .then(Commands.literal("off").executes(context -> autosave(context.getSource(), false)))));
    }

    // ------------------------------------------------------------------ 命令树

    /** 槽位参数：一个不含空格的词；补全用界面上那套槽位（自动 / 快速 / 手动槽位）。 */
    private static RequiredArgumentBuilder<CommandSourceStack, String> slotArgument() {
        return Commands.<String>argument("slot", StringArgumentType.word())
                .suggests((context, builder) -> SharedSuggestionProvider.suggest(RewindApi.slots(), builder));
    }

    private static String slot(CommandContext<CommandSourceStack> context) {
        return StringArgumentType.getString(context, "slot");
    }

    // ------------------------------------------------------------------ 动作

    private static int ui(CommandSourceStack source) {
        // 时间树只在客户端里有；专用服务器上没有这个钩子，如实报告
        RewindClientHook.Ui ui = RewindClientHook.get();
        if (ui == null) {
            source.sendFailure(Component.translatable("rewind.cmd.ui_unavailable"));
            return 0;
        }
        ui.openTree();
        return 1;
    }

    /** 建立 / 覆盖存档点，等价于按 F7。 */
    private static int snapshot(CommandSourceStack source, String slot) {
        Component blocker = worldActionBlocker(source);
        if (blocker != null) {
            source.sendFailure(blocker);
            return 0;
        }
        MinecraftServer server = source.getServer();
        if (serverPath(source)) {
            // 服务端路径：Brigadier 命令跑在服务端线程上，同步入口直接可用
            RewindResult result = RewindApi.createCheckpoint(server, slot, "command");
            if (!result.success) {
                source.sendFailure(Component.translatable("rewind.cmd.snapshot_failed",
                        String.valueOf(result.failure)));
                return 0;
            }
            source.sendSuccess(() -> Component.translatable("rewind.cmd.snapshot_started", slot), false);
            return 1;
        }
        if (!RewindApi.requestCheckpoint(slot, "command")) {
            source.sendFailure(noWorld());
            return 0;
        }
        source.sendSuccess(() -> Component.translatable("rewind.cmd.snapshot_started", slot), false);
        return 1;
    }

    /** 回溯到存档点，等价于按 F8。 */
    private static int restore(CommandSourceStack source, String slot) {
        Component blocker = worldActionBlocker(source);
        if (blocker != null) {
            source.sendFailure(blocker);
            return 0;
        }
        Path world = worldRoot(source);
        if (!RewindApi.hasCheckpoint(world, slot)) {
            source.sendFailure(Component.translatable("rewind.cmd.slot_missing", slot));
            return 0;
        }
        if (RewindApi.rollbackCooldownRemainingMillis(world) > 0L) {
            source.sendFailure(Component.translatable("rewind.error.rollback_cooldown"));
            return 0;
        }
        MinecraftServer server = source.getServer();
        if (serverPath(source)) {
            // 服务端路径：起一个跨 tick 的切片回滚，命令立刻返回；结果与客户端重载随后自动完成
            RewindResult started = RewindApi.beginMultiplayerRollback(server, slot, "command");
            if (!started.success) {
                source.sendFailure(Component.translatable("rewind.cmd.restore_failed",
                        String.valueOf(started.failure)));
                return 0;
            }
            source.sendSuccess(() -> Component.translatable("rewind.cmd.restore_started", slot), false);
            return 1;
        }
        if (!RewindApi.requestRollback(slot, "command")) {
            source.sendFailure(noWorld());
            return 0;
        }
        source.sendSuccess(() -> Component.translatable("rewind.cmd.restore_started", slot), false);
        return 1;
    }

    /** 看一个槽位的详情：一行元数据 + 背包快照装了几格。 */
    private static int info(CommandSourceStack source, String slot) {
        Path world = worldRoot(source);
        if (world == null) {
            source.sendFailure(noWorld());
            return 0;
        }
        SnapshotMeta meta = RewindApi.describe(world, slot);
        if (meta == null) {
            source.sendFailure(Component.translatable("rewind.cmd.slot_missing", slot));
            return 0;
        }
        source.sendSuccess(() -> Component.literal(meta.describe()), false);
        SnapshotInventory inventory = RewindApi.readInventory(world, slot);
        source.sendSuccess(() -> Component.translatable("rewind.cmd.inventory",
                inventory.size(), SnapshotInventory.SLOT_COUNT), false);
        return 1;
    }

    private static int delete(CommandSourceStack source, String slot) {
        Component blocker = busyBlocker();
        if (blocker != null) {
            source.sendFailure(blocker);
            return 0;
        }
        Path world = worldRoot(source);
        if (world == null) {
            source.sendFailure(noWorld());
            return 0;
        }
        if (!RewindApi.deleteCheckpoint(world, slot)) {
            source.sendFailure(Component.translatable("rewind.cmd.slot_missing", slot));
            return 0;
        }
        source.sendSuccess(() -> Component.translatable("rewind.cmd.deleted", slot), false);
        return 1;
    }

    private static int rename(CommandSourceStack source, String slot, String name) {
        Component blocker = busyBlocker();
        if (blocker != null) {
            source.sendFailure(blocker);
            return 0;
        }
        Path world = worldRoot(source);
        if (world == null) {
            source.sendFailure(noWorld());
            return 0;
        }
        String trimmed = name.trim();
        if (!SnapshotLayout.isValidDisplayName(trimmed)) {
            source.sendFailure(Component.translatable("rewind.cmd.name_invalid", SnapshotLayout.NAME_MAX_LENGTH));
            return 0;
        }
        if (!RewindApi.renameCheckpoint(world, slot, trimmed)) {
            source.sendFailure(Component.translatable("rewind.cmd.slot_missing", slot));
            return 0;
        }
        source.sendSuccess(() -> Component.translatable("rewind.cmd.renamed", slot, trimmed), false);
        return 1;
    }

    /**
     * 开关「跟着原版自动保存建点」（界面上那张「自动存档」卡的设置，同一个配置项）。
     *
     * <p>它只写 COMMON 配置、不碰世界，所以没有别的命令那几道判定。
     */
    private static int autosave(CommandSourceStack source, boolean enabled) {
        RewindServerConfig.setAutoCheckpointEnabled(enabled);
        source.sendSuccess(() -> Component.translatable(
                enabled ? "rewind.cmd.autosave_on" : "rewind.cmd.autosave_off"), false);
        return enabled ? 1 : 2;
    }

    // ------------------------------------------------------------------ 查询与判定

    private static int status(CommandSourceStack source) {
        Path world = worldRoot(source);
        if (world == null) {
            source.sendFailure(noWorld());
            return 0;
        }
        List<SnapshotMeta> metas = RewindApi.listCheckpoints(world);
        String head = RewindApi.currentSlot(world);
        source.sendSuccess(() -> Component.literal("head=" + head), false);
        if (metas.isEmpty()) {
            source.sendSuccess(() -> Component.translatable("rewind.cmd.status_empty"), false);
            return 0;
        }
        for (SnapshotMeta meta : metas) {
            source.sendSuccess(() -> Component.literal(meta.describe()), false);
        }
        RewindResult last = RewindApi.lastResult();
        String phase = RewindApi.isMultiplayerRollbackActive() ? "MULTIPLAYER_ROLLBACK" : clientPhase();
        source.sendSuccess(() -> Component.literal("phase=" + phase
                + " last=" + clientOutcome()
                + (last == null ? "" : " " + last.describe())
                + " " + clientMessage()), false);
        return metas.size();
    }

    /** 命令只在世界里有意义：数据包函数解析期的 source 连 server 都没有。 */
    private static Path worldRoot(CommandSourceStack source) {
        return source.getServer() == null ? null : RewindApi.worldRoot(source.getServer());
    }

    /**
     * 走服务端路径还是客户端过渡路径。
     *
     * <p>专用服务器与「开局域网的集成服务器」都走服务端路径：那边有别的玩家，世界不能被整段冻住，
     * 而且他们要靠原生的 Respawn 流程重载世界。单人（未开局域网）继续走 F7 / F8 那条带过渡的
     * 客户端管线——那是它唯一的过渡画面。
     */
    private static boolean serverPath(CommandSourceStack source) {
        MinecraftServer server = source.getServer();
        if (server == null) {
            return false;
        }
        RewindPlatform platform = RewindPlatforms.get();
        if (platform == null) {
            return true;
        }
        return platform.isDedicatedServer(server) || platform.isPublished(server);
    }

    /** 忙着的时候什么都别干：槽位管理与建点 / 回溯会写同一张索引。 */
    private static Component busyBlocker() {
        return RewindApi.isBusy() ? Component.translatable("rewind.error.busy") : null;
    }

    /**
     * 建点 / 回溯还要额外满足：世界在、不忙。
     *
     * <p>局域网不再被一刀切拒绝（TODO4 §1.2）：LAN 与专用服务器都走服务端路径，那边有切片回滚
     * 与保活屏障兜住远端玩家，所以这里只剩「有没有世界」这条判定。
     */
    private static Component worldActionBlocker(CommandSourceStack source) {
        Component busy = busyBlocker();
        if (busy != null) {
            return busy;
        }
        return source.getServer() == null ? noWorld() : null;
    }

    /** 「不在世界里」与「没有客户端桥」（专用服务器 / 还没 setup）共用一句：两种情况下都只能这么办。 */
    private static Component noWorld() {
        return Component.translatable("rewind.error.no_world");
    }

    /** 客户端状态机的阶段（只有装了客户端钩子时才有）。 */
    private static String clientPhase() {
        RewindClientHook.Ui ui = RewindClientHook.get();
        return ui == null ? "-" : ui.phase();
    }

    /** 客户端状态机的最近一次结果（只有装了客户端钩子时才有）。 */
    private static String clientOutcome() {
        RewindClientHook.Ui ui = RewindClientHook.get();
        return ui == null ? "-" : ui.outcome();
    }

    /** 客户端状态机的最近一条日志（只有装了客户端钩子时才有）。 */
    private static String clientMessage() {
        RewindClientHook.Ui ui = RewindClientHook.get();
        return ui == null ? "" : ui.message();
    }
}
