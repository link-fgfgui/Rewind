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
    void checkpointClearKeepsTheBaseForTheSameBaseline() {
        Object baseline = new Object();
        RewindState.noteColdBaseline(StorageKind.CHUNK, DIM, POS, baseline);
        RewindState.recordChunkWrite(StorageKind.CHUNK, DIM, POS, new Object());
        RewindState.clearStorageJournal();
        assertEquals(0, RewindState.storageJournal().size());
        assertSame(baseline, RewindState.frozenBase(StorageKind.CHUNK, DIM, POS),
                "清账本本身不换世代：同一存档点内的基底继续可用");
    }

    @Test
    void frozenBaseIsScopedToTheBaselineItBelongsTo() {
        Object baseline = new Object();
        RewindState.advanceBaseline("s1");
        RewindState.noteColdBaseline(StorageKind.CHUNK, DIM, POS, baseline);
        RewindState.recordChunkWrite(StorageKind.CHUNK, DIM, POS, new Object());
        assertSame(baseline, RewindState.frozenBase(StorageKind.CHUNK, DIM, POS),
                "与当前基线同世代：冻结基底可用");

        // 又建了点（世界站到 s2 上）：s1 世代的基底内容对应的是建点前的状态，不得再被采纳
        RewindState.advanceBaseline("s2");
        assertNull(RewindState.frozenBase(StorageKind.CHUNK, DIM, POS),
                "跨存档点不得采纳旧世代基底（防多槽位串数据）");

        // 回滚到 s1：世代切回，s1 世代的基底重新可用
        RewindState.selectBaseline("s1");
        assertSame(baseline, RewindState.frozenBase(StorageKind.CHUNK, DIM, POS),
                "切回目标槽位的世代后基底重新可用");

        // 本会话没建过点的槽位：全部失配，回落快照解码
        RewindState.selectBaseline("unknown");
        assertNull(RewindState.frozenBase(StorageKind.CHUNK, DIM, POS));
    }
}
