package cc.sighs.rewind.server;

import cc.sighs.rewind.Rewind;
import cc.sighs.rewind.common.config.AutoCheckpointSettings;
import cc.sighs.rewind.common.config.RollbackSettings;
import cc.sighs.rewind.common.config.SlotSettings;
//? if fabric {
/*import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import net.fabricmc.loader.api.FabricLoader;
*///?} elif forge {
/*import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.config.ModConfigEvent;
*///?} else {
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.neoforge.common.ModConfigSpec;
//?}

/**
 * 服务端行为的配置。
 *
 * <p>目前分三组：{@code autoCheckpoint}（跟着原版自动保存建点、间隔分钟数）、{@code rollback}
 * （回溯时要不要重发配方书、要不要只同步读回玩家视野内的区块、死亡后要不要自动回溯到时间线的头、
 * 要不要走原地回滚，以及两次回溯之间的读档冷却）与
 * {@code slots}（界面上那排手动编号卡片 {@code s1}…{@code sN} 的数量）。它跟玩家有没有客户端
 * 没关系，两边都要有，是 COMMON 配置而不是 CLIENT。
 *
 * <p>默认值、可调范围与「分钟 → tick」的换算都在 common 的 {@link AutoCheckpointSettings} /
 * {@link RollbackSettings} / {@link SlotSettings} 里；本类只负责各 loader 的读写与落盘，
 * 界面/Mixin 读到的都是那一份共享值。
 *
 * <p>后端按 loader 走：Fabric 没有内置配置系统，用最小的 {@link java.util.Properties} 实现顶上，
 * 落在 {@code config/rewind-common.properties}，键名与主版本保持一致，只是文件格式从 TOML 换成了
 * properties；Forge / NeoForge 走各自的 ConfigSpec，落在 {@code run/config/rewind-common.toml}。
 */
public final class RewindServerConfig {
    /** 「跟着原版自动保存建点」的默认值。 */
    public static final boolean DEFAULT_AUTO_CHECKPOINT = AutoCheckpointSettings.DEFAULT_AUTO_CHECKPOINT;
    /** 原版自动保存间隔的默认值（分钟）——原版自己就是 5 分钟。 */
    public static final int DEFAULT_AUTO_SAVE_INTERVAL_MINUTES = AutoCheckpointSettings.DEFAULT_AUTO_SAVE_INTERVAL_MINUTES;
    /** 自动保存间隔的可调范围（分钟）。 */
    public static final int MIN_AUTO_SAVE_INTERVAL_MINUTES = AutoCheckpointSettings.MIN_AUTO_SAVE_INTERVAL_MINUTES;
    public static final int MAX_AUTO_SAVE_INTERVAL_MINUTES = AutoCheckpointSettings.MAX_AUTO_SAVE_INTERVAL_MINUTES;
    /** 一分钟多少 tick（原版 20 tps）；{@link #autoSaveIntervalTicks()} 就是「分钟数 × 这个」。 */
    public static final int TICKS_PER_MINUTE = AutoCheckpointSettings.TICKS_PER_MINUTE;

//? if fabric {
    /*private static final String FILE_NAME = "rewind-common.properties";
    private static final String KEY_ENABLED = "autoCheckpoint.enabled";
    private static final String KEY_INTERVAL_MINUTES = "autoCheckpoint.intervalMinutes";
    private static final String KEY_SYNC_RECIPE_BOOK = "rollback.syncRecipeBook";
    private static final String KEY_SYNC_CHUNKS_NEAR_PLAYER = "rollback.syncChunksNearPlayer";
    private static final String KEY_ROLLBACK_ON_DEATH = "rollback.rollbackOnDeath";
    private static final String KEY_IN_PLACE_ROLLBACK = "rollback.inPlaceRollback";
    private static final String KEY_SKIP_NON_NBT_MCA = "rollback.skipNonNbtMca";
    private static final String KEY_ROLLBACK_COOLDOWN_SECONDS = "rollback.cooldownSeconds";
    private static final String KEY_MANUAL_SLOT_COUNT = "slots.manualSlotCount";
    // 写进文件开头的说明；一行一条，写的时候前面各加一个 `# `。
    private static final List<String> HEADER_LINES = Arrays.asList(
            "自动存档点。",
            "Auto checkpoints.",
            KEY_ENABLED + "：跟着原版自动保存建点。",
            KEY_ENABLED + ": Create a checkpoint after each vanilla autosave.",
            KEY_INTERVAL_MINUTES + "：原版自动保存间隔（分钟，1-60）。",
            KEY_INTERVAL_MINUTES + ": Vanilla autosave interval in minutes (1-60).",
            "回溯行为。",
            "Rollback behaviour.",
            KEY_SYNC_RECIPE_BOOK + "：回溯时重新同步玩家的配方书。",
            KEY_SYNC_RECIPE_BOOK + ": Re-sync the player's recipe book on rollback.",
            KEY_SYNC_CHUNKS_NEAR_PLAYER + "：回溯时只同步读回玩家视野内的区块。",
            KEY_SYNC_CHUNKS_NEAR_PLAYER + ": On rollback, sync-load only chunks near the player.",
            KEY_ROLLBACK_ON_DEATH + "：死亡后自动回溯到时间线上最近的那个节点。",
            KEY_ROLLBACK_ON_DEATH + ": Roll back to the nearest node on the timeline after death.",
            KEY_IN_PLACE_ROLLBACK + "：原地回滚：世界不关、不重登，直接在活着的服务器里倒回去。模组数据看起来没回滚就关掉它。",
            KEY_IN_PLACE_ROLLBACK + ": In-place rollback: rewind without closing the world. Turn off when mod data seems not to roll back.",
            KEY_SKIP_NON_NBT_MCA + "：建点/回溯跳过非 NBT/MCA 文件（advancements/、stats/、icon.png；.mcc 算 MCA 一边）。",
            KEY_SKIP_NON_NBT_MCA + ": Skip non-NBT/MCA files (advancements/, stats/, icon.png; .mcc counts as MCA) in checkpoints and rollbacks.",
            KEY_ROLLBACK_COOLDOWN_SECONDS + "：读档冷却（秒），0 = 关闭。",
            KEY_ROLLBACK_COOLDOWN_SECONDS + ": Rollback cooldown in seconds, 0 = off.",
            "槽位布局。",
            "Slot layout.",
            KEY_MANUAL_SLOT_COUNT + "：手动槽位数量。",
            KEY_MANUAL_SLOT_COUNT + ": Number of manual slots.");

    // 配置文件里那一份值：setAutoCheckpointEnabled / setAutoSaveIntervalMinutes 落盘时写的就是它。
    private static volatile boolean storedAutoCheckpointEnabled = DEFAULT_AUTO_CHECKPOINT;
    private static volatile int storedAutoSaveIntervalMinutes = DEFAULT_AUTO_SAVE_INTERVAL_MINUTES;
    // 回溯行为的几份值：setSyncRecipeBook / setSyncChunksNearPlayer / setRollbackOnDeath /
    // setRollbackCooldownSeconds（以及只从文件读、界面上没有开关的原地回滚）落盘时写的就是它们。
    private static volatile boolean storedSyncRecipeBook = RollbackSettings.DEFAULT_SYNC_RECIPE_BOOK;
    private static volatile boolean storedSyncChunksNearPlayer = RollbackSettings.DEFAULT_SYNC_CHUNKS_NEAR_PLAYER;
    private static volatile boolean storedRollbackOnDeath = RollbackSettings.DEFAULT_ROLLBACK_ON_DEATH;
    private static volatile boolean storedInPlaceRollback = RollbackSettings.DEFAULT_IN_PLACE_ROLLBACK;
    private static volatile boolean storedSkipNonNbtMca = RollbackSettings.DEFAULT_SKIP_NON_NBT_MCA;
    private static volatile int storedRollbackCooldownSeconds = RollbackSettings.DEFAULT_COOLDOWN_SECONDS;
    // 手动槽位数量那一份值：setManualSlotCount 落盘时写的就是它。
    private static volatile int storedManualSlotCount = SlotSettings.DEFAULT_MANUAL_SLOT_COUNT;
*///?} elif forge {
    /*private static ForgeConfigSpec.BooleanValue autoCheckpointEnabled;
    private static ForgeConfigSpec.IntValue autoSaveIntervalMinutes;
    private static ForgeConfigSpec.BooleanValue syncRecipeBook;
    private static ForgeConfigSpec.BooleanValue syncChunksNearPlayer;
    private static ForgeConfigSpec.BooleanValue rollbackOnDeath;
    private static ForgeConfigSpec.BooleanValue inPlaceRollback;
    private static ForgeConfigSpec.BooleanValue skipNonNbtMca;
    private static ForgeConfigSpec.IntValue rollbackCooldownSeconds;
    private static ForgeConfigSpec.IntValue manualSlotCount;
    private static ForgeConfigSpec spec;
    // 保存下来的配置对象：界面上改完开关要立刻落盘。
    private static volatile ModConfig modConfig;
*///?} else {
    private static ModConfigSpec.BooleanValue autoCheckpointEnabled;
    private static ModConfigSpec.IntValue autoSaveIntervalMinutes;
    private static ModConfigSpec.BooleanValue syncRecipeBook;
    private static ModConfigSpec.BooleanValue syncChunksNearPlayer;
    private static ModConfigSpec.BooleanValue rollbackOnDeath;
    private static ModConfigSpec.BooleanValue inPlaceRollback;
    private static ModConfigSpec.BooleanValue skipNonNbtMca;
    private static ModConfigSpec.IntValue rollbackCooldownSeconds;
    private static ModConfigSpec.IntValue manualSlotCount;
    private static ModConfigSpec spec;
    /** 保存下来的配置对象：界面上改完开关要立刻落盘。 */
    private static volatile ModConfig modConfig;
//?}

    private RewindServerConfig() {
    }

    // Fabric：读配置。必须在模组构造阶段调用，否则 Mixin 里的自动建点会赶不上第一轮自动保存。
    // 文件不存在时写一份默认值出来；读不动就用默认值顶上，不让一次 I/O 失败挡住整个模组初始化。
//? if fabric {
    /*public static void register() {
        Path file = configFile();
        boolean enabled = DEFAULT_AUTO_CHECKPOINT;
        int minutes = DEFAULT_AUTO_SAVE_INTERVAL_MINUTES;
        boolean recipeBook = RollbackSettings.DEFAULT_SYNC_RECIPE_BOOK;
        boolean chunksNearPlayer = RollbackSettings.DEFAULT_SYNC_CHUNKS_NEAR_PLAYER;
        boolean rollbackOnDeath = RollbackSettings.DEFAULT_ROLLBACK_ON_DEATH;
        boolean inPlaceRollback = RollbackSettings.DEFAULT_IN_PLACE_ROLLBACK;
        boolean skipNonNbtMca = RollbackSettings.DEFAULT_SKIP_NON_NBT_MCA;
        int cooldownSeconds = RollbackSettings.DEFAULT_COOLDOWN_SECONDS;
        int manualSlots = SlotSettings.DEFAULT_MANUAL_SLOT_COUNT;
        try {
            if (file != null && Files.isRegularFile(file)) {
                Properties properties = new Properties();
                try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                    properties.load(reader);
                }
                enabled = parseBoolean(properties.getProperty(KEY_ENABLED), DEFAULT_AUTO_CHECKPOINT);
                minutes = parseMinutes(properties.getProperty(KEY_INTERVAL_MINUTES));
                recipeBook = parseBoolean(properties.getProperty(KEY_SYNC_RECIPE_BOOK),
                        RollbackSettings.DEFAULT_SYNC_RECIPE_BOOK);
                chunksNearPlayer = parseBoolean(properties.getProperty(KEY_SYNC_CHUNKS_NEAR_PLAYER),
                        RollbackSettings.DEFAULT_SYNC_CHUNKS_NEAR_PLAYER);
                rollbackOnDeath = parseBoolean(properties.getProperty(KEY_ROLLBACK_ON_DEATH),
                        RollbackSettings.DEFAULT_ROLLBACK_ON_DEATH);
                inPlaceRollback = parseBoolean(properties.getProperty(KEY_IN_PLACE_ROLLBACK),
                        RollbackSettings.DEFAULT_IN_PLACE_ROLLBACK);
                skipNonNbtMca = parseBoolean(properties.getProperty(KEY_SKIP_NON_NBT_MCA),
                        RollbackSettings.DEFAULT_SKIP_NON_NBT_MCA);
                cooldownSeconds = parseCooldownSeconds(properties.getProperty(KEY_ROLLBACK_COOLDOWN_SECONDS));
                manualSlots = parseManualSlotCount(properties.getProperty(KEY_MANUAL_SLOT_COUNT));
            }
        } catch (Throwable t) {
            Rewind.LOGGER.error("Rewind: failed to read {}; falling back to the defaults", file, t);
            enabled = DEFAULT_AUTO_CHECKPOINT;
            minutes = DEFAULT_AUTO_SAVE_INTERVAL_MINUTES;
            recipeBook = RollbackSettings.DEFAULT_SYNC_RECIPE_BOOK;
            chunksNearPlayer = RollbackSettings.DEFAULT_SYNC_CHUNKS_NEAR_PLAYER;
            rollbackOnDeath = RollbackSettings.DEFAULT_ROLLBACK_ON_DEATH;
            inPlaceRollback = RollbackSettings.DEFAULT_IN_PLACE_ROLLBACK;
            skipNonNbtMca = RollbackSettings.DEFAULT_SKIP_NON_NBT_MCA;
            cooldownSeconds = RollbackSettings.DEFAULT_COOLDOWN_SECONDS;
            manualSlots = SlotSettings.DEFAULT_MANUAL_SLOT_COUNT;
        }

        storedAutoCheckpointEnabled = enabled;
        storedAutoSaveIntervalMinutes = AutoCheckpointSettings.clampMinutes(minutes);
        storedSyncRecipeBook = recipeBook;
        storedSyncChunksNearPlayer = chunksNearPlayer;
        storedRollbackOnDeath = rollbackOnDeath;
        storedInPlaceRollback = inPlaceRollback;
        storedSkipNonNbtMca = skipNonNbtMca;
        storedRollbackCooldownSeconds = RollbackSettings.clampCooldownSeconds(cooldownSeconds);
        storedManualSlotCount = SlotSettings.clamp(manualSlots);
        AutoCheckpointSettings.apply(storedAutoCheckpointEnabled, storedAutoSaveIntervalMinutes);
        RollbackSettings.apply(storedSyncRecipeBook, storedSyncChunksNearPlayer, storedRollbackCooldownSeconds,
                storedRollbackOnDeath, storedInPlaceRollback, storedSkipNonNbtMca);
        SlotSettings.apply(storedManualSlotCount);
        // 无论刚才是读出来的还是兜底出来的，都写回去一次：文件缺失时补上，值被夹过时纠正
        save();

        Rewind.LOGGER.info(
                "Rewind: auto checkpoint enabled={} interval={} min, rollback syncRecipeBook={} syncChunksNearPlayer={} "
                        + "rollbackOnDeath={} inPlaceRollback={} cooldownSeconds={}, slots manualSlotCount={}",
                AutoCheckpointSettings.autoCheckpointEnabled(), AutoCheckpointSettings.autoSaveIntervalMinutes(),
                RollbackSettings.syncRecipeBook(), RollbackSettings.syncChunksNearPlayer(),
                RollbackSettings.rollbackOnDeath(),
                RollbackSettings.inPlaceRollback(),
                RollbackSettings.cooldownSeconds(),
                SlotSettings.manualSlotCount());
    }
*///?}

    // 注册配置。必须在模组构造阶段调用，否则会赶不上配置加载事件。
    // 中间建表链 Forge/NeoForge 一字不差，只头（签名、Builder 类型）尾（注册与监听）几行按 loader 走。
//? if !fabric {
//? if forge {
    /*// Forge 1.20.1 的 ModContainer 上没有 registerConfig，走 ModLoadingContext 的当前容器。
    public static void register(IEventBus modBus) {
    ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();
*///?} else {
    public static void register(ModContainer container, IEventBus modBus) {
    ModConfigSpec.Builder builder = new ModConfigSpec.Builder();
//?}
        builder.comment("自动存档点。", "Auto checkpoints.").push("autoCheckpoint");

        autoCheckpointEnabled = builder
                .comment("跟着原版自动保存建点。",
                        "Create a checkpoint after each vanilla autosave.")
                .define("enabled", DEFAULT_AUTO_CHECKPOINT);

        autoSaveIntervalMinutes = builder
                .comment("原版自动保存间隔（分钟，1-60）。",
                        "Vanilla autosave interval in minutes (1-60).")
                .defineInRange("intervalMinutes", DEFAULT_AUTO_SAVE_INTERVAL_MINUTES,
                        MIN_AUTO_SAVE_INTERVAL_MINUTES, MAX_AUTO_SAVE_INTERVAL_MINUTES);

        builder.pop();

        builder.comment("回溯行为。", "Rollback behaviour.").push("rollback");
        syncRecipeBook = builder
                .comment("回溯时重新同步玩家的配方书。",
                        "Re-sync the player's recipe book on rollback.")
                .define("syncRecipeBook", RollbackSettings.DEFAULT_SYNC_RECIPE_BOOK);

        syncChunksNearPlayer = builder
                .comment("回溯时只同步读回玩家视野内的区块。",
                        "On rollback, sync-load only chunks near the player.")
                .define("syncChunksNearPlayer", RollbackSettings.DEFAULT_SYNC_CHUNKS_NEAR_PLAYER);

        rollbackOnDeath = builder
                .comment("死亡后自动回溯到时间线上最近的那个节点。",
                        "Roll back to the nearest node on the timeline after death.")
                .define("rollbackOnDeath", RollbackSettings.DEFAULT_ROLLBACK_ON_DEATH);

        inPlaceRollback = builder
                .comment("原地回滚：世界不关、不重登，直接在活着的服务器里倒回去。模组数据看起来没回滚就关掉它。",
                        "In-place rollback: rewind without closing the world. Turn off when mod data seems not to roll back.")
                .define("inPlaceRollback", RollbackSettings.DEFAULT_IN_PLACE_ROLLBACK);
        skipNonNbtMca = builder
                .comment("建点/回溯跳过非 NBT/MCA 文件（advancements/、stats/、icon.png；.mcc 算 MCA 一边）：开着这些不进存档点、不跟着回滚，原版读写照常。playerdata/datapacks/serverconfig 不受影响。",
                        "Skip non-NBT/MCA files (advancements/, stats/, icon.png; .mcc counts as MCA) in checkpoints and rollbacks. Vanilla IO is untouched; playerdata, datapacks and serverconfig are unaffected.")
                .define("skipNonNbtMca", RollbackSettings.DEFAULT_SKIP_NON_NBT_MCA);

        rollbackCooldownSeconds = builder
                .comment("读档冷却（秒），0 = 关闭。",
                        "Rollback cooldown in seconds, 0 = off.")
                .defineInRange("cooldownSeconds", RollbackSettings.DEFAULT_COOLDOWN_SECONDS,
                        RollbackSettings.MIN_COOLDOWN_SECONDS, RollbackSettings.MAX_COOLDOWN_SECONDS);
        builder.pop();

        builder.comment("槽位布局。", "Slot layout.").push("slots");
        manualSlotCount = builder
                .comment("手动槽位数量。",
                        "Number of manual slots.")
                .defineInRange("manualSlotCount", SlotSettings.DEFAULT_MANUAL_SLOT_COUNT,
                        SlotSettings.MIN_MANUAL_SLOT_COUNT, SlotSettings.MAX_MANUAL_SLOT_COUNT);
        builder.pop();

        spec = builder.build();
//? if forge {
        /*ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, spec);
        // Forge 的事件总线没有 NeoForge 那个 addListener(Class, Consumer) 重载：监听哪个事件
        // 是从 consumer 的泛型参数反推出来的。所以这里按事件类各写一个包装方法。
        modBus.addListener(RewindServerConfig::onConfigLoading);
        modBus.addListener(RewindServerConfig::onConfigReloading);
*///?} else {
        container.registerConfig(ModConfig.Type.COMMON, spec);
        modBus.addListener(ModConfigEvent.Loading.class, RewindServerConfig::onConfigEvent);
        modBus.addListener(ModConfigEvent.Reloading.class, RewindServerConfig::onConfigEvent);
//?}
    }

//? if forge {
    /*private static void onConfigLoading(ModConfigEvent.Loading event) {
        onConfigEvent(event);
    }

    private static void onConfigReloading(ModConfigEvent.Reloading event) {
        onConfigEvent(event);
    }
*///?}
//?}

//? if !fabric {
    private static void onConfigEvent(ModConfigEvent event) {
        // 这个事件对所有配置都会来一次，先认一下是不是自己那份
        if (event.getConfig().getSpec() != spec) {
            return;
        }
        modConfig = event.getConfig();
        apply();
    }

    private static void apply() {
        try {
            AutoCheckpointSettings.apply(
                    autoCheckpointEnabled == null ? DEFAULT_AUTO_CHECKPOINT : autoCheckpointEnabled.get(),
                    autoSaveIntervalMinutes == null
                            ? DEFAULT_AUTO_SAVE_INTERVAL_MINUTES
                            : autoSaveIntervalMinutes.get());
            RollbackSettings.apply(
                    syncRecipeBook == null ? RollbackSettings.DEFAULT_SYNC_RECIPE_BOOK : syncRecipeBook.get(),
                    syncChunksNearPlayer == null
                            ? RollbackSettings.DEFAULT_SYNC_CHUNKS_NEAR_PLAYER
                            : syncChunksNearPlayer.get(),
                    rollbackCooldownSeconds == null
                            ? RollbackSettings.DEFAULT_COOLDOWN_SECONDS
                            : rollbackCooldownSeconds.get(),
                    rollbackOnDeath == null
                            ? RollbackSettings.DEFAULT_ROLLBACK_ON_DEATH
                            : rollbackOnDeath.get(),
                    inPlaceRollback == null
                            ? RollbackSettings.DEFAULT_IN_PLACE_ROLLBACK
                            : inPlaceRollback.get(),
                    skipNonNbtMca == null
                            ? RollbackSettings.DEFAULT_SKIP_NON_NBT_MCA
                            : skipNonNbtMca.get());
            SlotSettings.apply(manualSlotCount == null
                    ? SlotSettings.DEFAULT_MANUAL_SLOT_COUNT
                    : manualSlotCount.get());
        } catch (Throwable t) {
            AutoCheckpointSettings.apply(DEFAULT_AUTO_CHECKPOINT, DEFAULT_AUTO_SAVE_INTERVAL_MINUTES);
            RollbackSettings.apply(RollbackSettings.DEFAULT_SYNC_RECIPE_BOOK,
                    RollbackSettings.DEFAULT_SYNC_CHUNKS_NEAR_PLAYER,
                    RollbackSettings.DEFAULT_COOLDOWN_SECONDS,
                    RollbackSettings.DEFAULT_ROLLBACK_ON_DEATH,
                    RollbackSettings.DEFAULT_IN_PLACE_ROLLBACK,
                    RollbackSettings.DEFAULT_SKIP_NON_NBT_MCA);
            SlotSettings.apply(SlotSettings.DEFAULT_MANUAL_SLOT_COUNT);
        }
        Rewind.LOGGER.info(
                "Rewind: auto checkpoint enabled={} interval={} min, rollback syncRecipeBook={} syncChunksNearPlayer={} "
                        + "rollbackOnDeath={} inPlaceRollback={} cooldown={}s, slots manualSlotCount={}",
                AutoCheckpointSettings.autoCheckpointEnabled(), AutoCheckpointSettings.autoSaveIntervalMinutes(),
                RollbackSettings.syncRecipeBook(), RollbackSettings.syncChunksNearPlayer(),
                RollbackSettings.rollbackOnDeath(),
                RollbackSettings.inPlaceRollback(),
                RollbackSettings.cooldownSeconds(),
                SlotSettings.manualSlotCount());
    }
//?}

    /** 跟着原版自动保存建点是不是开着。 */
    public static boolean autoCheckpointEnabled() {
        return AutoCheckpointSettings.autoCheckpointEnabled();
    }

    /** 配置里的自动保存间隔（分钟，已夹到范围内）。 */
    public static int autoSaveIntervalMinutes() {
        return AutoCheckpointSettings.autoSaveIntervalMinutes();
    }

    /**
     * 原版自动保存的间隔（tick）；<b>0 表示不碰原版</b>（让它按自己的 5 分钟走）。
     *
     * <p>只有「跟着原版自动保存建点」开着的时候才返回有效值——关着的时候改这个间隔没有意义。
     * 读的是内存里的普通字段，不查配置文件。
     */
    public static int autoSaveIntervalTicks() {
        return AutoCheckpointSettings.autoSaveIntervalTicks();
    }

    /** 改这个开关并立刻落盘（界面上「自动存档」卡的「设置」按钮走这里）。 */
    public static void setAutoCheckpointEnabled(boolean enabled) {
        AutoCheckpointSettings.setAutoCheckpointEnabled(enabled);
        //? if fabric {
        /*storedAutoCheckpointEnabled = enabled;
        *///?} else {
        if (autoCheckpointEnabled != null) {
            autoCheckpointEnabled.set(enabled);
        }
        //?}
        save();
        Rewind.LOGGER.info("Rewind: auto checkpoint enabled={} (toggled)", enabled);
    }

    /** 改自动保存间隔（分钟）并立刻落盘。值会被夹到 {@link #MIN_AUTO_SAVE_INTERVAL_MINUTES} - {@link #MAX_AUTO_SAVE_INTERVAL_MINUTES}。 */
    public static void setAutoSaveIntervalMinutes(int minutes) {
        AutoCheckpointSettings.setAutoSaveIntervalMinutes(minutes);
        int clamped = AutoCheckpointSettings.autoSaveIntervalMinutes();
        //? if fabric {
        /*storedAutoSaveIntervalMinutes = clamped;
        *///?} else {
        if (autoSaveIntervalMinutes != null) {
            autoSaveIntervalMinutes.set(clamped);
        }
        //?}
        save();
        Rewind.LOGGER.info("Rewind: auto save interval = {} min ({} ticks)",
                clamped, AutoCheckpointSettings.autoSaveIntervalTicks());
    }

    /** 回溯时要不要处理配方书。 */
    public static boolean syncRecipeBook() {
        return RollbackSettings.syncRecipeBook();
    }

    /** 回溯时是不是只同步读回玩家视野内的受影响区块。 */
    public static boolean syncChunksNearPlayer() {
        return RollbackSettings.syncChunksNearPlayer();
    }

    /** 改「只同步读视野内区块」开关并立刻落盘。 */
    public static void setSyncChunksNearPlayer(boolean value) {
        RollbackSettings.setSyncChunksNearPlayer(value);
        //? if fabric {
        /*storedSyncChunksNearPlayer = value;
        *///?} else {
        if (syncChunksNearPlayer != null) {
            syncChunksNearPlayer.set(value);
        }
        //?}
        save();
        Rewind.LOGGER.info("Rewind: rollback syncChunksNearPlayer={} (toggled)", value);
    }

    /** 改这个开关并立刻落盘。 */
    public static void setSyncRecipeBook(boolean value) {
        RollbackSettings.setSyncRecipeBook(value);
        //? if fabric {
        /*storedSyncRecipeBook = value;
        *///?} else {
        if (syncRecipeBook != null) {
            syncRecipeBook.set(value);
        }
        //?}
        save();
        Rewind.LOGGER.info("Rewind: rollback syncRecipeBook={} (toggled)", value);
    }

    /** 死亡后要不要自动回溯到时间线上最近的那个节点。 */
    public static boolean rollbackOnDeath() {
        return RollbackSettings.rollbackOnDeath();
    }

    /** 回溯是不是走原地回滚（关掉就退回「关世界 → 覆盖 → 重开」）。只从配置文件读，界面上没有对应开关。 */
    public static boolean inPlaceRollback() {
        return RollbackSettings.inPlaceRollback();
    }

    /** 建点/回溯要不要跳过非 NBT/MCA 文件（{@code advancements/}、{@code stats/}、{@code icon.png}）。只从配置文件读，界面上没有对应开关。 */
    public static boolean skipNonNbtMca() {
        return RollbackSettings.skipNonNbtMca();
    }

    /** 改「死亡后自动回溯」开关并立刻落盘。 */
    public static void setRollbackOnDeath(boolean value) {
        RollbackSettings.setRollbackOnDeath(value);
        //? if fabric {
        /*storedRollbackOnDeath = value;
        *///?} else {
        if (rollbackOnDeath != null) {
            rollbackOnDeath.set(value);
        }
        //?}
        save();
        Rewind.LOGGER.info("Rewind: rollback rollbackOnDeath={} (toggled)", value);
    }

    /** 读档冷却时长（秒；0 表示不冷却）。 */
    public static int rollbackCooldownSeconds() {
        return RollbackSettings.cooldownSeconds();
    }

    /** 改读档冷却时长并立刻落盘。值会被夹到 {@link RollbackSettings#MIN_COOLDOWN_SECONDS} - {@link RollbackSettings#MAX_COOLDOWN_SECONDS}。 */
    public static void setRollbackCooldownSeconds(int value) {
        RollbackSettings.setCooldownSeconds(value);
        int clamped = RollbackSettings.cooldownSeconds();
        //? if fabric {
        /*storedRollbackCooldownSeconds = clamped;
        *///?} else {
        if (rollbackCooldownSeconds != null) {
            rollbackCooldownSeconds.set(clamped);
        }
        //?}
        save();
        Rewind.LOGGER.info("Rewind: rollback cooldownSeconds={} (toggled)", clamped);
    }

    /** 界面上的手动槽位数量（COMMON 配置可调，已夹到范围内）。 */
    public static int manualSlotCount() {
        return SlotSettings.manualSlotCount();
    }

    /** 改手动槽位数量并立刻落盘。值会被夹到 {@link SlotSettings#MIN_MANUAL_SLOT_COUNT} - {@link SlotSettings#MAX_MANUAL_SLOT_COUNT}。 */
    public static void setManualSlotCount(int value) {
        SlotSettings.setManualSlotCount(value);
        int clamped = SlotSettings.manualSlotCount();
        //? if fabric {
        /*storedManualSlotCount = clamped;
        *///?} else {
        if (manualSlotCount != null) {
            manualSlotCount.set(clamped);
        }
        //?}
        save();
        Rewind.LOGGER.info("Rewind: slots manualSlotCount={} (toggled)", clamped);
    }

//? if fabric {
    /*// 解析 slots.manualSlotCount：非法值回默认，越界夹到范围内。
    private static int parseManualSlotCount(String raw) {
        if (raw == null) {
            return SlotSettings.DEFAULT_MANUAL_SLOT_COUNT;
        }
        try {
            return SlotSettings.clamp(Integer.parseInt(raw.trim()));
        } catch (NumberFormatException e) {
            Rewind.LOGGER.warn("Rewind: \"{}\" is not a valid manual slot count; using the default {}",
                    raw, SlotSettings.DEFAULT_MANUAL_SLOT_COUNT);
            return SlotSettings.DEFAULT_MANUAL_SLOT_COUNT;
        }
    }

    // 解析 rollback.cooldownSeconds：非法值回默认，越界夹到范围内。
    private static int parseCooldownSeconds(String raw) {
        if (raw == null) {
            return RollbackSettings.DEFAULT_COOLDOWN_SECONDS;
        }
        try {
            return RollbackSettings.clampCooldownSeconds(Integer.parseInt(raw.trim()));
        } catch (NumberFormatException e) {
            Rewind.LOGGER.warn("Rewind: \"{}\" is not a valid rollback cooldown; using the default {} seconds",
                    raw, RollbackSettings.DEFAULT_COOLDOWN_SECONDS);
            return RollbackSettings.DEFAULT_COOLDOWN_SECONDS;
        }
    }

    // 配置文件的位置；取不到配置目录时返回 null（读写的调用方各自兜底）。
    private static Path configFile() {
        try {
            return FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME);
        } catch (Throwable t) {
            Rewind.LOGGER.error("Rewind: could not resolve the config directory", t);
            return null;
        }
    }

    private static boolean parseBoolean(String raw, boolean fallback) {
        return raw == null ? fallback : Boolean.parseBoolean(raw.trim());
    }

    private static int parseMinutes(String raw) {
        if (raw == null) {
            return DEFAULT_AUTO_SAVE_INTERVAL_MINUTES;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            Rewind.LOGGER.warn("Rewind: \"{}\" is not a valid auto save interval; using the default {} minutes",
                    raw, DEFAULT_AUTO_SAVE_INTERVAL_MINUTES);
            return DEFAULT_AUTO_SAVE_INTERVAL_MINUTES;
        }
    }

    // 落盘入口：三家同名，setter 与 Fabric 注册尾共用这一个调用；写不动就只写一条日志（值本身已经在内存里生效了）。
    // 手写而不是 Properties.store：store 会把中文注释里的每个字转义成 Unicode 转义序列，人读不了
    private static void save() {
        Path file = configFile();
        if (file == null) {
            return;
        }
        List<String> lines = new ArrayList<>();
        for (String comment : HEADER_LINES) {
            lines.add("# " + comment);
        }
        lines.add(KEY_ENABLED + "=" + storedAutoCheckpointEnabled);
        lines.add(KEY_INTERVAL_MINUTES + "=" + storedAutoSaveIntervalMinutes);
        lines.add(KEY_SYNC_RECIPE_BOOK + "=" + storedSyncRecipeBook);
        lines.add(KEY_SYNC_CHUNKS_NEAR_PLAYER + "=" + storedSyncChunksNearPlayer);
        lines.add(KEY_ROLLBACK_ON_DEATH + "=" + storedRollbackOnDeath);
        lines.add(KEY_IN_PLACE_ROLLBACK + "=" + storedInPlaceRollback);
        lines.add(KEY_SKIP_NON_NBT_MCA + "=" + storedSkipNonNbtMca);
        lines.add(KEY_ROLLBACK_COOLDOWN_SECONDS + "=" + storedRollbackCooldownSeconds);
        lines.add(KEY_MANUAL_SLOT_COUNT + "=" + storedManualSlotCount);
        try {
            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.write(file, lines, StandardCharsets.UTF_8);
        } catch (Throwable t) {
            Rewind.LOGGER.error("Rewind: failed to save {}", file, t);
        }
    }
*///?}

//? if forge {
    /*private static void save() {
        ModConfig current = modConfig;
        if (current == null) {
            return;
        }
        try {
            // Forge 1.20.1 的 ModConfig 自己就有 save()
            current.save();
        } catch (Throwable t) {
            Rewind.LOGGER.error("Rewind: failed to save the config after a settings change", t);
        }
    }
*///?}

//? if neoforge {
    private static void save() {
        ModConfig current = modConfig;
        if (current == null) {
            return;
        }
        try {
            // FML 的 ModConfig 上没有 save()，落盘入口在 loadedConfig 上
            current.getLoadedConfig().save();
        } catch (Throwable t) {
            Rewind.LOGGER.error("Rewind: failed to save the config after a settings change", t);
        }
    }
//?}
}
