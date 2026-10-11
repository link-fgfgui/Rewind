package cc.sighs.rewind.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cc.sighs.rewind.common.spi.StorageKind;
import cc.sighs.rewind.common.spi.RewindPlatform;
import cc.sighs.rewind.common.spi.RewindPlatforms;
import cc.sighs.rewind.snapshot.SnapshotLayout;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link RewindState} 覆盖层状态机：武装 / 撤装 / 清账本。
 *
 * <p>每个用例结束都撤装并清账本——账本是进程级单例，不能把状态漏给别的测试。
 */
class RewindStateTest {

    private static final long POS = 0x0000000100000002L;

    @AfterEach
    void disarmAndClear() {
        RewindState.disarmStorageCover();
    }

    @Test
    void armedFlagTransitions() {
        assertFalse(RewindState.isStorageCoverArmed());
        RewindState.armStorageCover();
        assertTrue(RewindState.isStorageCoverArmed());
        RewindState.disarmStorageCover();
        assertFalse(RewindState.isStorageCoverArmed());
    }

    @Test
    void disarmClearsJournal() {
        RewindState.storageJournal().onWritten(StorageKind.CHUNK, "minecraft:overworld", POS, new Object());
        assertEquals(1, RewindState.storageJournal().size());
        RewindState.armStorageCover();
        RewindState.disarmStorageCover();
        assertFalse(RewindState.isStorageCoverArmed());
        assertEquals(0, RewindState.storageJournal().size());
        assertNull(RewindState.storageJournal().serve(StorageKind.CHUNK, "minecraft:overworld", POS));
    }

    @Test
    void checkpointClearKeepsArmedState() {
        RewindState.armStorageCover();
        RewindState.storageJournal().onWritten(StorageKind.ENTITY, "minecraft:overworld", POS, new Object());
        RewindState.clearStorageJournal();
        assertTrue(RewindState.isStorageCoverArmed());
        assertEquals(0, RewindState.storageJournal().size());
    }

    @Test
    void disarmWithoutArmIsSafe() {
        RewindState.disarmStorageCover();
        assertFalse(RewindState.isStorageCoverArmed());
    }

    @Test
    void disarmClearsDataCover() {
        RewindState.dataCover().onWritten("player|uuid", new Object());
        assertEquals(1, RewindState.dataCover().size());
        RewindState.armStorageCover();
        RewindState.disarmStorageCover();
        assertEquals(0, RewindState.dataCover().size());
    }

    @Test
    void recordChunkWriteRegistersJournal() {
        RewindState.recordChunkWrite(StorageKind.CHUNK, "minecraft:overworld", POS, new Object());
        assertEquals(1, RewindState.storageJournal().size());
        RewindState.clearStorageJournal();
        assertEquals(0, RewindState.storageJournal().size());
    }

    @TempDir
    Path worldRoot;

    /**
     * 开服自愈（TODO2 §4.2）：直接进世界不经过修复屏时，靠开服事件这一次把 6a 中继
     * 状态搬回来——平台桩只给世界根，不碰任何 Minecraft 类型。
     */
    @Test
    void serverStartedHealsRelayWindow() throws Exception {
        Path oldDir = SnapshotLayout.compactOldDir(worldRoot);
        Files.createDirectories(oldDir);
        byte[] pack = {(byte) 0x52, (byte) 0x57, (byte) 0x5A, (byte) 0x50};
        Files.write(oldDir.resolve("quick.zpack"), pack);
        RewindPlatform stub = new RewindPlatform() {
            @Override
            public String modVersion() {
                return "test";
            }

            @Override
            public Path worldRoot(Object server) {
                return worldRoot;
            }

            @Override
            public boolean isSameThread(Object server) {
                return true;
            }

            @Override
            public boolean isDedicatedServer(Object server) {
                return false;
            }

            @Override
            public boolean isPublished(Object server) {
                return false;
            }

            @Override
            public boolean hasPlayers(Object server) {
                return false;
            }

            @Override
            public cc.sighs.rewind.common.spi.FlushOutcome flushForCheckpoint(Object server, String slot,
                    String source) {
                return null;
            }

            @Override
            public boolean supportsInPlaceRollback() {
                return false;
            }

            @Override
            public cc.sighs.rewind.common.spi.RollbackOutcome rollbackInPlace(Object server, Path worldRoot,
                    String slot) {
                return null;
            }

            @Override
            public ClassLoader modClassLoader(String modId) {
                return null;
            }
        };
        RewindPlatforms.install(stub);
        try {
            // server 对象原样透传（common 不解读）：传个哑对象即可
            RewindState.onServerStarted(new Object());
        } finally {
            RewindPlatforms.install(null);
        }
        assertTrue(Files.isDirectory(SnapshotLayout.snapshotRoot(worldRoot)));
        assertArrayEquals(pack, Files.readAllBytes(
                SnapshotLayout.snapshotRoot(worldRoot).resolve("quick.zpack")));
    }
}
