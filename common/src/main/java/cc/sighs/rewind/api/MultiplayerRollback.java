package cc.sighs.rewind.api;

import java.nio.file.Path;
import javax.annotation.Nullable;
import cc.sighs.rewind.common.RewindLog;
import cc.sighs.rewind.common.RewindState;
import cc.sighs.rewind.common.spi.RewindPlatform;
import cc.sighs.rewind.common.spi.RewindPlatforms;
import cc.sighs.rewind.common.spi.RollbackJob;
import cc.sighs.rewind.common.spi.RollbackOutcome;
import cc.sighs.rewind.snapshot.SnapshotLayout;

/**
 * 跨 tick 的多人在线回滚（TODO4 阶段 2 的「方案 A：回滚切片化」）的调度器。
 *
 * <p>**决议（动手前定案，见 AGENT.md「多人在线回滚（Dedicated Server / LAN）」一节）**：
 * 多人在线回滚走方案 A——版本侧的回滚引擎（{@code InPlaceRollback}）改成有状态的分片机
 * （{@link RollbackJob}），本类每 tick 拿时间预算推它一片；整体还有一个总预算，超时立即中止。
 *
 * <p>为什么这样能保住 keep-alive：服务端线程不再被整段回滚占住，每个 tick 都会回到
 * {@code MinecraftServer.tickChildren}，连接层（{@code ServerConnection.tick}）与玩家列表
 * （{@code PlayerList.tick}）照常跑，心跳应答就会按时处理；同时
 * {@link RewindState#isMultiplayerRollbacking()} 置位让共享 Mixin 冻住世界刻、丢掉操作包，
 * 回滚期间的世界状态和同步路径一样不会被玩家动作改脏。
 *
 * <p>线程：{@link #begin} / {@link #tick} 必须在服务端线程上；{@link #isActive()} / 结果读取
 * 可以从别的线程看（volatile）。
 */
final class MultiplayerRollback {
    /** 每 tick 给回滚的工作预算（纳秒）：12ms——留足一个 20 TPS tick 的剩余时间给连接与别的东西。 */
    private static final long TICK_BUDGET_NANOS = 12_000_000L;
    /** 整段回滚的总预算（纳秒）：120 秒，超时立即中止并按失败收尾。 */
    private static final long TOTAL_BUDGET_NANOS = 120_000_000_000L;
    /**
     * 生效的总预算（纳秒）。默认就是上面那个 120 秒；单元测试把它调小来验超时路径，
     * 别的地方不要动它。
     */
    static volatile long totalBudgetNanos = TOTAL_BUDGET_NANOS;

    private static volatile boolean active;
    private static volatile RollbackJob job;
    private static volatile RollbackRequest request;
    private static volatile RewindResult result;
    private static long startedNanos;

    private MultiplayerRollback() {
    }

    /** 现在有没有一次跨 tick 回滚在跑（含刚开始、还没结束的）。 */
    static boolean isActive() {
        return active;
    }

    /** 正在回滚的槽位；没在跑时返回 null。 */
    @Nullable
    static String activeSlot() {
        RollbackRequest current = request;
        return current == null ? null : current.slot;
    }

    /** 完成后回填的结果；还没结束时为 null。 */
    @Nullable
    static RewindResult result() {
        return result;
    }

    /**
     * 开始一次跨 tick 的原地回滚。**必须在服务端线程上调用。**
     *
     * @return 一条结果：{@code success=true} 表示已经**开始**（真正的结果随后写在
     *         {@link RewindApi#lastResult()}）；{@code success=false} 表示没开始（不支持 / 冷却 /
     *         槽位不对 / 被开始事件取消），失败原因在 {@code failure} 里
     */
    static RewindResult begin(Object server, String slot, String source) {
        RewindPlatform platform = RewindPlatforms.get();
        if (platform == null) {
            return failure(slot, new IllegalStateException("Rewind platform has not been installed"));
        }
        if (!platform.supportsSlicedRollback()) {
            return failure(slot,
                    new UnsupportedOperationException("tick-sliced rollback is not supported on this platform"));
        }
        if (!platform.isSameThread(server)) {
            return failure(slot, new IllegalStateException("must be called on the server thread"));
        }
        if (!SnapshotLayout.isValidSlotName(slot)) {
            return failure(slot, new IllegalArgumentException("invalid slot name: " + slot));
        }
        if (active) {
            return failure(slot, new IllegalStateException(
                    "another multiplayer rollback is already running (" + activeSlot() + ")"));
        }
        Path worldRoot = RewindApi.worldRootUnchecked(server);
        if (worldRoot == null) {
            return failure(slot, new IllegalStateException("the world is not loaded"));
        }
        RewindResult invalid = RewindApi.invalidSlot(RewindResult.Kind.ROLLBACK, slot);
        if (invalid != null) {
            return invalid;
        }
        RewindResult cooling = RewindApi.cooldownFailure(worldRoot, slot);
        if (cooling != null) {
            RewindLog.LOGGER.warn("Rewind: ignoring the rollback to {}: still on cooldown", slot);
            return cooling;
        }
        RollbackRequest pending = new RollbackRequest(worldRoot, slot, source, true,
                RewindApi.describe(worldRoot, slot));
        if (!platform.beforeRollback(pending)) {
            return RewindApi.canceled(pending);
        }
        // 通知必须排在回滚窗口打开**之前**：窗口开着的时候不再发我们自己产生的包。
        try {
            platform.announceRollback(server, slot, source);
        } catch (Throwable t) {
            RewindLog.LOGGER.warn("Rewind: failed to announce the multiplayer rollback", t);
        }
        RewindState.beginMultiplayerRollback();
        RewindState.beginDiscard();
        RollbackJob created = null;
        try {
            created = platform.beginRollback(server, worldRoot, slot);
        } catch (Throwable t) {
            RewindLog.LOGGER.error("Rewind: failed to start the multiplayer rollback engine", t);
        }
        if (created == null) {
            // 起不来：把刚置的标记全部收回，什么都不做
            RewindState.endDiscard();
            RewindState.endMultiplayerRollback();
            RewindResult failed = RewindApi.fail(RewindResult.Kind.ROLLBACK, slot,
                    new IllegalStateException("the platform could not create a rollback job"));
            platform.afterRollback(pending, failed);
            return failed;
        }
        job = created;
        request = pending;
        result = null;
        startedNanos = System.nanoTime();
        active = true;
        RewindLog.LOGGER.info("Rewind: multiplayer rollback to {} started (source={}, {})",
                slot, source, created.describe());
        return RewindResult.rollback(slot, true, "started " + created.describe(), 0L, 0, 0, 0);
    }

    /**
     * 推一片回滚。由各版本节点的服务端 tick 钩子每 tick 调一次（服务端线程）。
     *
     * <p>一个 tick 里会反复调用 {@link RollbackJob#step(long)} 直到这一 tick 的工作预算用完
     * 或者整段回滚结束；结束后立刻收尾（关窗口、清标记、派发结束事件、成功时重载客户端）。
     */
    static void tick(Object server) {
        RollbackJob current = job;
        if (!active || current == null) {
            return;
        }
        long deadline = System.nanoTime() + TICK_BUDGET_NANOS;
        boolean finished = false;
        try {
            do {
                finished = current.step(Math.max(1L, deadline - System.nanoTime()));
            } while (!finished && System.nanoTime() < deadline);
        } catch (Throwable t) {
            RewindLog.LOGGER.error("Rewind: multiplayer rollback threw", t);
            abortAndFinish(server, t);
            return;
        }
        if (finished) {
            finish(server, current);
            return;
        }
        long elapsed = System.nanoTime() - startedNanos;
        long budget = totalBudgetNanos;
        if (elapsed > budget) {
            RewindLog.LOGGER.error(
                    "Rewind: multiplayer rollback exceeded the {}ms budget, aborting it",
                    budget / 1_000_000L);
            abortAndFinish(server, new IllegalStateException(
                    "rollback exceeded the " + budget / 1_000_000L + "ms budget"));
        }
    }

    /** 正常结束（step 返回 true）：成败都在作业自己的 outcome / failure 里。 */
    private static void finish(Object server, RollbackJob current) {
        RollbackOutcome outcome = current.outcome();
        Throwable failure = current.failure();
        RollbackRequest finished = request;
        // 结束事件与索引收尾都必须排在窗口关闭**之后**：afterRollback 的语义统一成「世界已经不再被冻住」。
        RewindState.endDiscard();
        RewindState.endMultiplayerRollback();
        clearJob();
        RewindResult result;
        if (failure != null || outcome == null) {
            result = RewindApi.fail(RewindResult.Kind.ROLLBACK,
                    finished == null ? "" : finished.slot, failure != null ? failure
                            : new IllegalStateException("the rollback job finished without an outcome"));
        } else {
            result = RewindResult.rollback(finished == null ? "" : finished.slot, true, outcome.summary,
                    outcome.totalMs, outcome.mirrorCopied, outcome.mirrorSkipped, outcome.mirrorFiles);
            if (finished != null) {
                RewindApi.markRolledBack(finished.worldRoot, finished.slot);
            }
        }
        MultiplayerRollback.result = result;
        MultiplayerRollback.request = finished;
        RewindApi.recordResult(result);
        if (finished != null) {
            RewindPlatforms.require().afterRollback(finished, result);
        }
        if (result.success && finished != null) {
            // 回滚成功：用原版原生流程把全体在线客户端的旧世界丢掉、重新下载地形。
            try {
                RewindState.beginNativeReload();
                RewindPlatforms.require().reloadClientsAfterRollback(server);
            } catch (Throwable t) {
                RewindLog.LOGGER.error("Rewind: failed to reload clients after the rollback", t);
            } finally {
                RewindState.endNativeReload();
            }
            RewindLog.LOGGER.info("Rewind: multiplayer rollback to {} finished: {}", finished.slot, result.summary);
        } else {
            RewindLog.LOGGER.error("Rewind: multiplayer rollback failed: {}", result.summary);
        }
    }

    /** 超时 / 抛异常：清干净并按失败收尾。 */
    private static void abortAndFinish(Object server, Throwable failure) {
        RollbackJob current = job;
        if (current != null) {
            try {
                current.abort();
            } catch (Throwable t) {
                RewindLog.LOGGER.warn("Rewind: rollback abort cleanup failed", t);
            }
        }
        RollbackRequest aborted = request;
        RewindState.endDiscard();
        RewindState.endMultiplayerRollback();
        clearJob();
        RewindResult result = RewindApi.fail(RewindResult.Kind.ROLLBACK,
                aborted == null ? "" : aborted.slot, failure);
        MultiplayerRollback.result = result;
        MultiplayerRollback.request = aborted;
        RewindApi.recordResult(result);
        if (aborted != null) {
            try {
                RewindPlatforms.require().afterRollback(aborted, result);
            } catch (Throwable t) {
                RewindLog.LOGGER.warn("Rewind: failed to dispatch the rollback end event", t);
            }
        }
    }

    /** 清掉「正在跑」的引用，但保留 request / result 给调用方读。 */
    private static void clearJob() {
        active = false;
        job = null;
        startedNanos = 0L;
    }

    private static RewindResult failure(String slot, Throwable t) {
        RewindLog.LOGGER.error("Rewind: cannot start the multiplayer rollback for {}", slot, t);
        return RewindApi.fail(RewindResult.Kind.ROLLBACK, slot, t);
    }
}
