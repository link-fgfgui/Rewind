package cc.sighs.rewind.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import cc.sighs.rewind.common.spi.StorageKind;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * 首次写前捕获（Copy-on-First-Write）：读时登记、首次写冻结、之后重记。
 */
class CowBaselineTest {

    private static final long POS = 0x0000000500000006L;
    private static final String DIM = "minecraft:overworld";

    @AfterEach
    void clean() {
        RewindState.disarmStorageCover();
        RewindState.frozenBases().clear();
    }

    @Test
    void firstWriteFreezesTheBaseline() {
        Object baseline = new Object();
        Object written = new Object();
        RewindState.noteColdBaseline(StorageKind.CHUNK, DIM, POS, baseline);
        RewindState.recordChunkWrite(StorageKind.CHUNK, DIM, POS, written);

        assertSame(written, RewindState.storageJournal().serve(StorageKind.CHUNK, DIM, POS));
        assertSame(baseline, RewindState.frozenBase(StorageKind.CHUNK, DIM, POS),
                "首次写必须把读时基线冻成回滚基底");
    }

    @Test
    void secondWriteKeepsTheFirstBaseline() {
        Object baseline = new Object();
        Object first = new Object();
        Object second = new Object();
        RewindState.noteColdBaseline(StorageKind.CHUNK, DIM, POS, baseline);
        RewindState.recordChunkWrite(StorageKind.CHUNK, DIM, POS, first);
        RewindState.recordChunkWrite(StorageKind.CHUNK, DIM, POS, second);

        assertSame(second, RewindState.storageJournal().serve(StorageKind.CHUNK, DIM, POS));
        assertSame(baseline, RewindState.frozenBase(StorageKind.CHUNK, DIM, POS),
                "第二次写不得覆盖冻结基底");
    }

    @Test
    void writeWithoutBaselineFreezesNothing() {
        RewindState.recordChunkWrite(StorageKind.CHUNK, DIM, POS, new Object());
        assertNull(RewindState.frozenBase(StorageKind.CHUNK, DIM, POS));
        assertEquals(1, RewindState.storageJournal().size());
    }

    @Test
    void discardingWindowWritesNothing() {
        RewindState.beginDiscard();
        try {
            RewindState.noteColdBaseline(StorageKind.CHUNK, DIM, POS, new Object());
            RewindState.recordChunkWrite(StorageKind.CHUNK, DIM, POS, new Object());
        } finally {
            RewindState.endDiscard();
        }
        // 基线登记本身不检查窗口（读包装已跳过），但写账本必须跳过
        assertEquals(0, RewindState.storageJournal().size());
        assertNull(RewindState.frozenBase(StorageKind.CHUNK, DIM, POS));
    }

    @Test
    void checkpointClearKeepsFrozenForOldSlots() {
        Object baseline = new Object();
        RewindState.noteColdBaseline(StorageKind.CHUNK, DIM, POS, baseline);
        RewindState.recordChunkWrite(StorageKind.CHUNK, DIM, POS, new Object());
        RewindState.clearStorageJournal();
        assertEquals(0, RewindState.storageJournal().size());
        assertSame(baseline, RewindState.frozenBase(StorageKind.CHUNK, DIM, POS),
                "建点清账本不得丢掉旧槽位的冻结基底");
    }
}
