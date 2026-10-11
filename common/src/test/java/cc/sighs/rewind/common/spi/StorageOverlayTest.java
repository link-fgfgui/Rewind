package cc.sighs.rewind.common.spi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * {@link StorageOverlay} 的账本语义：记、查、丢弃、隔离。
 *
 * <p>tag 用普通 Object 冒充版本相关的 NBT——common 侧本来就看不到 NBT 类型，
 * 这里只验证「原样存、原样还」。
 */
class StorageOverlayTest {

    private static final long POS = 0x0000000100000002L;

    @Test
    void coverThenServeReturnsSameHandle() {
        StorageOverlay overlay = new StorageOverlay();
        Object tag = new Object();
        overlay.onWritten(StorageKind.CHUNK, "minecraft:overworld", POS, tag);
        assertSame(tag, overlay.serve(StorageKind.CHUNK, "minecraft:overworld", POS));
        assertEquals(1, overlay.size());
    }

    @Test
    void missingEntryServesNull() {
        StorageOverlay overlay = new StorageOverlay();
        assertNull(overlay.serve(StorageKind.CHUNK, "minecraft:overworld", POS));
        assertNull(overlay.serve(StorageKind.ENTITY, "minecraft:overworld", POS));
    }

    @Test
    void overwriteReplaces() {
        StorageOverlay overlay = new StorageOverlay();
        Object first = new Object();
        Object second = new Object();
        overlay.onWritten(StorageKind.ENTITY, "minecraft:overworld", POS, first);
        overlay.onWritten(StorageKind.ENTITY, "minecraft:overworld", POS, second);
        assertSame(second, overlay.serve(StorageKind.ENTITY, "minecraft:overworld", POS));
        assertEquals(1, overlay.size());
    }

    @Test
    void entriesAreIsolatedByKindAndDimension() {
        StorageOverlay overlay = new StorageOverlay();
        Object chunk = new Object();
        Object entity = new Object();
        Object nether = new Object();
        overlay.onWritten(StorageKind.CHUNK, "minecraft:overworld", POS, chunk);
        overlay.onWritten(StorageKind.ENTITY, "minecraft:overworld", POS, entity);
        overlay.onWritten(StorageKind.CHUNK, "minecraft:the_nether", POS, nether);
        assertSame(chunk, overlay.serve(StorageKind.CHUNK, "minecraft:overworld", POS));
        assertSame(entity, overlay.serve(StorageKind.ENTITY, "minecraft:overworld", POS));
        assertSame(nether, overlay.serve(StorageKind.CHUNK, "minecraft:the_nether", POS));
        assertEquals(3, overlay.size());
    }

    @Test
    void discardRemoves() {
        StorageOverlay overlay = new StorageOverlay();
        overlay.onWritten(StorageKind.POI, "minecraft:overworld", POS, new Object());
        assertTrue(overlay.discard(StorageKind.POI, "minecraft:overworld", POS));
        assertNull(overlay.serve(StorageKind.POI, "minecraft:overworld", POS));
        assertFalse(overlay.discard(StorageKind.POI, "minecraft:overworld", POS));
    }

    @Test
    void clearEmpties() {
        StorageOverlay overlay = new StorageOverlay();
        overlay.onWritten(StorageKind.CHUNK, "minecraft:overworld", POS, new Object());
        overlay.onWritten(StorageKind.ENTITY, "minecraft:overworld", POS, new Object());
        overlay.clear();
        assertEquals(0, overlay.size());
        assertNull(overlay.serve(StorageKind.CHUNK, "minecraft:overworld", POS));
    }

    @Test
    void nullWriteRecordsTombstone() {
        StorageOverlay overlay = new StorageOverlay();
        overlay.onWritten(StorageKind.CHUNK, "minecraft:overworld", POS, null);
        assertTrue(overlay.covers(StorageKind.CHUNK, "minecraft:overworld", POS));
        assertNull(overlay.serve(StorageKind.CHUNK, "minecraft:overworld", POS));
        assertEquals(1, overlay.size());
        assertEquals(0, overlay.serveHits());
        assertFalse(overlay.covers(StorageKind.ENTITY, "minecraft:overworld", POS));
    }

    @Test
    void nullArgumentsRejected() {
        StorageOverlay overlay = new StorageOverlay();
        Object tag = new Object();
        assertThrows(NullPointerException.class,
                () -> overlay.onWritten(null, "minecraft:overworld", POS, tag));
        assertThrows(NullPointerException.class,
                () -> overlay.onWritten(StorageKind.CHUNK, null, POS, tag));
        assertThrows(NullPointerException.class,
                () -> overlay.serve(null, "minecraft:overworld", POS));
        assertThrows(NullPointerException.class,
                () -> overlay.discard(StorageKind.CHUNK, null, POS));
    }

    @Test
    void serveHitsCountedAndResetOnClear() {
        StorageOverlay overlay = new StorageOverlay();
        assertEquals(0, overlay.serveHits());
        assertNull(overlay.serve(StorageKind.CHUNK, "minecraft:overworld", POS));
        assertEquals(0, overlay.serveHits());
        overlay.onWritten(StorageKind.CHUNK, "minecraft:overworld", POS, new Object());
        overlay.serve(StorageKind.CHUNK, "minecraft:overworld", POS);
        overlay.serve(StorageKind.CHUNK, "minecraft:overworld", POS);
        assertEquals(2, overlay.serveHits());
        overlay.clear();
        assertEquals(0, overlay.serveHits());
    }
}
