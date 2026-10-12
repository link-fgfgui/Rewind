package cc.sighs.rewind.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import cc.sighs.rewind.common.RewindState;
import cc.sighs.rewind.common.spi.EncodedSector;
import cc.sighs.rewind.common.spi.FlushOutcome;
import cc.sighs.rewind.common.spi.RewindPlatform;
import cc.sighs.rewind.common.spi.RewindPlatforms;
import cc.sighs.rewind.common.spi.RollbackJob;
import cc.sighs.rewind.common.spi.RollbackOutcome;
import cc.sighs.rewind.common.spi.StorageKind;
import cc.sighs.rewind.snapshot.SnapshotIndex;
import cc.sighs.rewind.snapshot.SnapshotLayout;
import cc.sighs.rewind.snapshot.SnapshotMeta;

/**
 * 跨 tick 的多人回滚调度器（{@code MultiplayerRollback}）与两个回调钩子的接线。
 *
 * <p>守着四件事：切片作业按 tick 推进到结束才收尾；开始钩子能取消（取消时作业一次都不跑、
 * 没有结束钩子、没有客户端重载）；作业抛异常 / 超总预算时按失败收尾并调 {@code abort()}；
 * 收尾必须把回滚窗口与「多人回滚中」标记都清掉。
 *
 * <p>版本侧真正的引擎（{@code InPlaceRollback.Job}）依赖 Minecraft 类，common 的纯 JVM 测试装不进来，
 * 所以这里用一个假的 {@link RollbackJob} 验调度与收尾的语义；引擎本身靠上机验收（起专用服务器）。
 */
class MultiplayerRollbackTest {

    @TempDir
    Path tempRoot;

    private Path world;

    @AfterEach
    void cleanup() {
        // 有的用例故意留着一个永不结束的作业（验「同一次只能有一个」）：把总预算压到 1ns 逼它中止，
        // 别把「正在跑」的静态状态泄漏给下一个用例。
        if (MultiplayerRollback.isActive()) {
            MultiplayerRollback.totalBudgetNanos = 1L;
            MultiplayerRollback.tick("server");
        }
        RewindPlatforms.install(null);
        MultiplayerRollback.totalBudgetNanos = 120_000_000_000L;
        RewindState.onWorldUnloaded();
    }

    /** 假作业：按 {@link #stepsNeeded} 片推进，可选在某一篇抛异常 / 永不结束。 */
    private static final class FakeJob implements RollbackJob {
        int steps;
        int stepsNeeded = 3;
        boolean never;
        int aborted;
        boolean finished;
        Throwable failure;
        RollbackOutcome outcome;
        boolean failAtStep = false;

        @Override
        public String describe() {
            return "fake";
        }

        @Override
        public boolean step(long budgetNanos) {
            if (never) {
                return false;
            }
            if (failAtStep && steps == 1) {
                throw new IllegalStateException("engine blew up");
            }
            steps++;
            if (steps >= stepsNeeded) {
                finished = true;
                outcome = new RollbackOutcome("copied=7", 7, 0, 7, 5L);
            }
            return finished;
        }

        @Override
        public RollbackOutcome outcome() {
            return finished ? outcome : null;
        }

        @Override
        public Throwable failure() {
            return failure;
        }

        @Override
        public boolean finished() {
            return finished;
        }

        @Override
        public void abort() {
            aborted++;
            finished = true;
        }
    }

    private final class FakePlatform implements RewindPlatform {
        FakeJob job = new FakeJob();
        boolean cancel;
        String reason = "";
        RollbackRequest started;
        RollbackRequest ended;
        RewindResult reported;
        int announcements;
        int reloads;
        boolean sliced = true;

        FakePlatform() {
            RewindPlatforms.install(this);
        }

        @Override
        public String modVersion() {
            return "test";
        }

        @Override
        public Path worldRoot(Object server) {
            return world;
        }

        @Override
        public boolean isSameThread(Object server) {
            return true;
        }

        @Override
        public boolean isDedicatedServer(Object server) {
            return true;
        }

        @Override
        public boolean isPublished(Object server) {
            return false;
        }

        @Override
        public boolean hasPlayers(Object server) {
            return true;
        }

        @Override
        public FlushOutcome flushForCheckpoint(Object server, String slot, String source) {
            throw new UnsupportedOperationException("not needed by this test");
        }

        @Override
        public boolean supportsInPlaceRollback() {
            return true;
        }

        @Override
        public RollbackOutcome rollbackInPlace(Object server, Path worldRoot, String slot) {
            throw new UnsupportedOperationException("not needed by this test");
        }

        @Override
        public ClassLoader modClassLoader(String modId) {
            return null;
        }

        @Override
        public EncodedSector encodeChunkSector(Object tag) {
            throw new UnsupportedOperationException("not needed by this test");
        }

        @Override
        public Object decodeChunkSector(byte[] uncompressed) {
            throw new UnsupportedOperationException("not needed by this test");
        }

        @Override
        public byte[] encodeRawNbt(Object tag) {
            throw new UnsupportedOperationException("not needed by this test");
        }

        @Override
        public String chunkDirRelative(StorageKind kind, String dimensionId) {
            return null;
        }

        @Override
        public boolean beforeRollback(RollbackRequest request) {
            started = request;
            if (cancel) {
                request.cancelReason = reason;
            }
            return !cancel;
        }

        @Override
        public void afterRollback(RollbackRequest request, RewindResult result) {
            ended = request;
            reported = result;
        }

        @Override
        public boolean supportsSlicedRollback() {
            return sliced;
        }

        @Override
        public RollbackJob beginRollback(Object server, Path worldRoot, String slot) {
            return job;
        }

        @Override
        public void announceRollback(Object server, String slot, String source) {
            announcements++;
        }

        @Override
        public void reloadClientsAfterRollback(Object server) {
            reloads++;
        }
    }

    private Path freshWorld() throws IOException {
        world = Files.createDirectories(tempRoot.resolve("world"));
        return world;
    }

    private static void writeMeta(Path worldRoot, String slot) throws IOException {
        Path file = SnapshotLayout.indexFile(worldRoot);
        SnapshotIndex index = SnapshotIndex.load(file);
        SnapshotMeta meta = new SnapshotMeta();
        meta.slot = slot;
        meta.status = SnapshotLayout.STATUS_COMPLETE;
        meta.savedAtMillis = 100L;
        index.put(meta, slot);
        index.save(file);
    }

    /** 一直推到调度器说「没在跑」为止（上限防死循环）。 */
    private static void pump() {
        for (int i = 0; i < 100 && MultiplayerRollback.isActive(); i++) {
            MultiplayerRollback.tick("server");
        }
    }

    @Test
    void aSlicedRollbackRunsAcrossTicksAndThenReloads() throws IOException {
        freshWorld();
        writeMeta(world, "s1");
        FakePlatform platform = new FakePlatform();
        platform.job.stepsNeeded = 25;

        RewindResult started = RewindApi.beginMultiplayerRollback("server", "s1", "command");

        assertTrue(started.success, String.valueOf(started.failure));
        assertTrue(MultiplayerRollback.isActive());
        assertTrue(RewindApi.isBusy(), "回滚进行中要把忙标记显出来");
        assertTrue(RewindState.isMultiplayerRollbacking());
        assertTrue(RewindState.isDiscarding(), "回滚窗口从开始就打开");
        assertEquals(1, platform.announcements, "广播要排在窗口打开之前");

        pump();

        assertFalse(MultiplayerRollback.isActive());
        assertFalse(RewindApi.isBusy());
        assertFalse(RewindState.isMultiplayerRollbacking(), "收尾必须清掉多人标记");
        assertFalse(RewindState.isDiscarding(), "收尾必须关掉回滚窗口");
        assertTrue(platform.job.steps >= 25, "作业要真的被推到结束");
        assertNotNull(platform.reported);
        assertTrue(platform.reported.success, String.valueOf(platform.reported.failure));
        assertSame(platform.started, platform.ended, "同一次回溯复用同一个请求对象");
        assertEquals(1, platform.reloads, "成功后要重载客户端");
        assertEquals("s1", RewindApi.lastResult().slot);
        assertEquals("s1", RewindApi.currentSlot(world), "时间线的头移到被回溯到的槽位");
    }

    @Test
    void aCancelledRollbackNeverStartsTheJob() throws IOException {
        freshWorld();
        writeMeta(world, "s1");
        FakePlatform platform = new FakePlatform();
        platform.cancel = true;
        platform.reason = "boss fight";

        RewindResult result = RewindApi.beginMultiplayerRollback("server", "s1", "command");

        assertFalse(result.success);
        assertTrue(result.failure instanceof RollbackCanceledException, String.valueOf(result.failure));
        assertEquals("boss fight", result.failure.getMessage());
        assertEquals(0, platform.job.steps, "被取消就不该推任何一片");
        assertEquals(0, platform.announcements, "取消时连广播都不该发");
        assertNull(platform.ended, "没有开始就没有结束");
        assertEquals(0, platform.reloads);
        assertFalse(RewindState.isDiscarding());
        assertFalse(RewindState.isMultiplayerRollbacking());
        assertFalse(RewindApi.isBusy());
    }

    @Test
    void aThrowingJobFailsCleanly() throws IOException {
        freshWorld();
        writeMeta(world, "s1");
        FakePlatform platform = new FakePlatform();
        platform.job.stepsNeeded = 10;
        platform.job.failAtStep = true;

        RewindApi.beginMultiplayerRollback("server", "s1", "command");
        pump();

        assertFalse(MultiplayerRollback.isActive());
        assertEquals(1, platform.job.aborted, "抛异常要中止作业");
        assertNotNull(platform.reported);
        assertFalse(platform.reported.success);
        assertEquals(0, platform.reloads, "失败不做客户端重载");
        assertFalse(RewindState.isDiscarding());
        assertFalse(RewindState.isMultiplayerRollbacking());
    }

    @Test
    void anOverBudgetRollbackAborts() throws IOException {
        freshWorld();
        writeMeta(world, "s1");
        FakePlatform platform = new FakePlatform();
        platform.job.never = true;
        MultiplayerRollback.totalBudgetNanos = 1L;

        RewindApi.beginMultiplayerRollback("server", "s1", "command");
        pump();

        assertFalse(MultiplayerRollback.isActive());
        assertEquals(1, platform.job.aborted, "超预算要中止作业");
        assertNotNull(platform.reported);
        assertFalse(platform.reported.success);
        assertEquals(0, platform.reloads, "超时不做客户端重载");
        assertFalse(RewindState.isDiscarding(), "中止也要把窗口关掉");
        assertFalse(RewindState.isMultiplayerRollbacking());
    }

    @Test
    void aSecondRollbackIsRefusedWhileOneRuns() throws IOException {
        freshWorld();
        writeMeta(world, "s1");
        writeMeta(world, "s2");
        FakePlatform platform = new FakePlatform();
        platform.job.never = true;

        assertTrue(RewindApi.beginMultiplayerRollback("server", "s1", "command").success);
        RewindResult second = RewindApi.beginMultiplayerRollback("server", "s2", "command");

        assertFalse(second.success, "同一次只能有一个切片回滚在跑");
        assertEquals("s1", platform.started.slot);
    }

    @Test
    void anUnsupportedPlatformFallsBackWithAFailure() throws IOException {
        freshWorld();
        writeMeta(world, "s1");
        FakePlatform platform = new FakePlatform();
        platform.sliced = false;

        RewindResult result = RewindApi.beginMultiplayerRollback("server", "s1", "command");

        assertFalse(result.success);
        assertTrue(result.failure instanceof UnsupportedOperationException, String.valueOf(result.failure));
        assertEquals(0, platform.reloads);
    }
}
