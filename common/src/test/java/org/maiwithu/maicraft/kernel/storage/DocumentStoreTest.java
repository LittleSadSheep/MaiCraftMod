// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.kernel.storage;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 文档库的离线测试：全部走临时目录，不碰真实存档路径。 */
class DocumentStoreTest {
    private static final int LIMIT = 1024;

    @Test
    void writtenDocumentReadsBack(@TempDir Path temp) throws IOException {
        var store = new DocumentStore(temp.resolve("state.sqlite"));
        assertTrue(store.write("state", identity("world-a"), "checkpoint", "{\"hp\":10}", false));
        assertTrue(store.contains("state", identity("world-a"), "checkpoint"));
        assertEquals("{\"hp\":10}", store.read("state", identity("world-a"), "checkpoint", LIMIT));
    }

    @Test
    void missingStoreReadsAsAbsent(@TempDir Path temp) throws IOException {
        var store = new DocumentStore(temp.resolve("state.sqlite"));
        assertFalse(store.contains("state", identity("world-a"), "checkpoint"));
        assertNull(store.read("state", identity("world-a"), "checkpoint", LIMIT));
    }

    @Test
    void onlyIfAbsentKeepsExistingValue(@TempDir Path temp) throws IOException {
        var store = new DocumentStore(temp.resolve("state.sqlite"));
        store.write("state", identity("world-a"), "claim", "first", false);
        assertFalse(store.write("state", identity("world-a"), "claim", "second", true));
        assertEquals("first", store.read("state", identity("world-a"), "claim", LIMIT));
    }

    @Test
    void updatesAndDeletesInPlace(@TempDir Path temp) throws IOException {
        var store = new DocumentStore(temp.resolve("state.sqlite"));
        store.write("state", identity("world-a"), "counter", "1", false);
        assertEquals("2", store.update("state", identity("world-a"), "counter", LIMIT, s -> String.valueOf(Integer.parseInt(s) + 1)));
        store.delete("state", identity("world-a"), "counter");
        assertNull(store.read("state", identity("world-a"), "counter", LIMIT));
    }

    @Test
    void overBudgetRejectsWholeDocument(@TempDir Path temp) throws IOException {
        var store = new DocumentStore(temp.resolve("state.sqlite"));
        String big = "x".repeat(LIMIT + 1);
        store.write("state", identity("world-a"), "big", big, false);
        // 读取和更新时按字节数拒绝；超预算的大文档不会先整个读进内存。
        assertThrows(DocumentStore.OverBudget.class, () -> store.read("state", identity("world-a"), "big", LIMIT));
        // 更新里超预算时事务回滚，原文保持不动。
        store.write("state", identity("world-a"), "small", "keep", false);
        assertThrows(DocumentStore.OverBudget.class,
                () -> store.update("state", identity("world-a"), "small", LIMIT, s -> s + big));
        assertEquals("keep", store.read("state", identity("world-a"), "small", LIMIT));
    }

    @Test
    void worldsWithSameFileStayIsolated(@TempDir Path temp) throws IOException {
        var store = new DocumentStore(temp.resolve("state.sqlite"));
        store.write("state", identity("world-a"), "checkpoint", "A", false);
        assertNull(store.read("state", identity("world-b"), "checkpoint", LIMIT));
        assertFalse(store.contains("state", identity("world-b"), "checkpoint"));
        // 另一个世界写同一个键，互不覆盖。
        store.write("state", identity("world-b"), "checkpoint", "B", false);
        assertEquals("A", store.read("state", identity("world-a"), "checkpoint", LIMIT));
        assertEquals("B", store.read("state", identity("world-b"), "checkpoint", LIMIT));
    }

    @Test
    void identityDatabaseSitsBesideDirectory(@TempDir Path temp) {
        var identity = new StateIdentity(identity("world-a"), temp.resolve("state"));
        assertEquals(temp.resolve("state.sqlite"), identity.databaseFile());
    }

    @Test
    void identityKeyMustBeHexHash(@TempDir Path temp) {
        assertThrows(IllegalArgumentException.class, () -> new StateIdentity("not-a-hash", temp.resolve("state")));
    }

    /** 测试里直接用明文算出世界身份键，行为与世界身份判别用的一致。 */
    private static String identity(String world) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(world.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
