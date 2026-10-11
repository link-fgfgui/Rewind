package cc.sighs.rewind.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cc.sighs.rewind.common.spi.StorageKind;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

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
    void disarmClearsBaselinesAndDataCover() {
        RewindState.noteColdBaseline(StorageKind.CHUNK, "minecraft:overworld", POS, new Object());
        RewindState.dataCover().onWritten("player|uuid", new Object());
        assertEquals(1, RewindState.coldBaselines().size());
        assertEquals(1, RewindState.dataCover().size());
        RewindState.armStorageCover();
        RewindState.disarmStorageCover();
        assertEquals(0, RewindState.coldBaselines().size());
        assertEquals(0, RewindState.dataCover().size());
    }

    @Test
    void checkpointClearDropsBaselinesButKeepsFrozen() {
        Object base = new Object();
        RewindState.noteColdBaseline(StorageKind.CHUNK, "minecraft:overworld", POS, base);
        RewindState.recordChunkWrite(StorageKind.CHUNK, "minecraft:overworld", POS, new Object());
        RewindState.clearStorageJournal();
        assertEquals(0, RewindState.coldBaselines().size());
        assertSame(base, RewindState.frozenBase(StorageKind.CHUNK, "minecraft:overworld", POS));
        RewindState.frozenBases().clear();
    }
}
