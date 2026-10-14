package cc.sighs.rewind.snapshot;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cc.sighs.rewind.common.config.RollbackSettings;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * {@link SnapshotLayout#isExcluded(String)} 对非 NBT/MCA 文件的开关行为。
 *
 * <p>只守谓词本身：开着时 `advancements/`、`stats/`、`icon.png` 被排除，
 * 关着走原路径；其余目录两种状态都不受影响。改完把静态开关复原，不污染别的测试。
 */
class SnapshotLayoutTest {

    @AfterEach
    void restoreDefaults() {
        RollbackSettings.apply(RollbackSettings.DEFAULT_SYNC_RECIPE_BOOK,
                RollbackSettings.DEFAULT_SYNC_CHUNKS_NEAR_PLAYER,
                RollbackSettings.DEFAULT_COOLDOWN_SECONDS,
                RollbackSettings.DEFAULT_ROLLBACK_ON_DEATH,
                RollbackSettings.DEFAULT_IN_PLACE_ROLLBACK,
                RollbackSettings.DEFAULT_SKIP_NON_NBT_MCA);
    }

    private static void applySkip(boolean value) {
        RollbackSettings.apply(RollbackSettings.DEFAULT_SYNC_RECIPE_BOOK,
                RollbackSettings.DEFAULT_SYNC_CHUNKS_NEAR_PLAYER,
                RollbackSettings.DEFAULT_COOLDOWN_SECONDS,
                RollbackSettings.DEFAULT_ROLLBACK_ON_DEATH,
                RollbackSettings.DEFAULT_IN_PLACE_ROLLBACK,
                value);
    }

    @Test
    void nonNbtMcaIncludedByDefault() {
        applySkip(false);
        assertFalse(SnapshotLayout.isExcluded("advancements/12345678-1234-1234-1234-123456789abc.json"));
        assertFalse(SnapshotLayout.isExcluded("stats/12345678-1234-1234-1234-123456789abc.json"));
        assertFalse(SnapshotLayout.isExcluded("icon.png"));
    }

    @Test
    void nonNbtMcaExcludedWhenEnabled() {
        applySkip(true);
        assertTrue(SnapshotLayout.isExcluded("advancements/12345678-1234-1234-1234-123456789abc.json"));
        assertTrue(SnapshotLayout.isExcluded("stats/12345678-1234-1234-1234-123456789abc.json"));
        assertTrue(SnapshotLayout.isExcluded("icon.png"));
    }

    @Test
    void otherEntriesUnaffectedByFlag() {
        for (boolean skip : new boolean[] {false, true}) {
            applySkip(skip);
            assertFalse(SnapshotLayout.isExcluded("playerdata/12345678-1234-1234-1234-123456789abc.dat"));
            assertFalse(SnapshotLayout.isExcluded("level.dat"));
            assertFalse(SnapshotLayout.isExcluded("region/r.0.0.mca"));
            assertFalse(SnapshotLayout.isExcluded("data/raids.dat"));
            assertFalse(SnapshotLayout.isExcluded("datapacks/pack.mcmeta"));
            assertFalse(SnapshotLayout.isExcluded("serverconfig/mod.toml"));
            assertTrue(SnapshotLayout.isExcluded("session.lock"));
            assertTrue(SnapshotLayout.isExcluded(".compact_tmp"));
            assertTrue(SnapshotLayout.isExcluded(".compact_tmp/sub/file.dat"));
            assertTrue(SnapshotLayout.isExcluded(".compact_old"));
            assertTrue(SnapshotLayout.isExcluded(".compact_old/sub/file.dat"));
        }
    }
}
